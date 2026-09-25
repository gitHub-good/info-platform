package com.info.platform.application.retention;

import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.retention.ExpiredLogDeleter;
import com.info.platform.domain.retention.RetentionLogTable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 留痕清理服务（T71，方案 §4.5）：四张留痕表的过期行分批循环删除 + 统计汇总。
 *
 * <p>执行流（每轮）：① 现读 {@code retention.global} 解析窗口（字段级回退防御）；② 逐表（枚举序固定，明细段序由此确定） cutoff = now −
 * 窗口（整秒截断）后分批循环删至返回值 &lt; 批大小；③ 单表失败 catch 续跑其余表，已删批部分计数仍入明细； ④ 轮末 INFO 单行摘要（行数 + 窗口 + 耗时，运维留档）；⑤
 * 有失败才汇总抛 {@link RetentionCleanupException}（→ 通道记 FAILED，成功表明细 + 失败原因同载 error_message）。
 *
 * <p>幂等：删除按「严格早于边界」判定，重复执行结果收敛（方案库 03）。
 */
@Service
public class RetentionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupService.class);

    /** 窗口配置键（ADR-0036 §1：独立 retention 域文档，与调度键 job.RETENTION_CLEANUP 分立）。 */
    public static final String CONFIG_KEY = "retention.global";

    private final RuntimeConfigService configService;
    private final ExpiredLogDeleter deleter;
    private final Clock clock;

    public RetentionCleanupService(
            RuntimeConfigService configService, ExpiredLogDeleter deleter, Clock clock) {
        this.configService = configService;
        this.deleter = deleter;
        this.clock = clock;
    }

    /**
     * 执行一轮清理（定时与手动触发共用入口，由 RetentionCleanupJob 委托）。
     *
     * @return 合计删除行数与四段明细（供 JobRunStats 上报 SUCCESS 留痕）
     * @throws RetentionCleanupException 存在失败表（其余表已尽力删除，成功侧信息在异常消息）
     */
    public CleanupResult runOnce() {
        long startedNanos = System.nanoTime();
        RetentionWindows windows = currentWindows();
        Map<RetentionLogTable, Long> counts = new EnumMap<>(RetentionLogTable.class);
        List<String> failures = new ArrayList<>();
        for (RetentionLogTable table : RetentionLogTable.values()) {
            long deletedTotal = 0;
            try {
                Instant cutoff = cutoffOf(windows.of(table));
                long deleted;
                do {
                    // 分批循环删至返回值 < 批大小即无过期行；计数累在外层——失败时已删批部分计数不丢
                    deleted =
                            deleter.deleteExpiredBefore(table, cutoff, RetentionPolicy.BATCH_SIZE);
                    deletedTotal += deleted;
                } while (deleted == RetentionPolicy.BATCH_SIZE);
            } catch (RuntimeException ex) {
                // 单表失败不停摆（PRD 故事 3 场景 4）：续跑其余表，轮末汇总
                log.error("留痕清理单表失败，续跑其余表 table={}", table.physicalName(), ex);
                failures.add(table.physicalName() + ": " + ex);
            } finally {
                counts.put(table, deletedTotal);
            }
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        String detail = detailOf(counts);
        // INFO 摘要四表窗口齐载（D2：readingEventDays 曾缺失，段序=枚举序，方案 §3.2「含窗口与耗时」）
        log.info(
                "留痕清理完成 共删除 {} 行（{}）窗口 {}/{}/{}/{} 天 耗时 {}ms",
                total,
                detail,
                windows.jobExecutionLogDays(),
                windows.dataSourceEventDays(),
                windows.llmCallLogDays(),
                windows.readingEventDays(),
                (System.nanoTime() - startedNanos) / 1_000_000);
        if (!failures.isEmpty()) {
            throw new RetentionCleanupException(detail + " | 失败: " + String.join("; ", failures));
        }
        return new CleanupResult(total, detail);
    }

    /** 每轮现读窗口（键缺失 → 全默认；字段非法 → 字段级回退，RetentionWindows 契约）。 */
    private RetentionWindows currentWindows() {
        return RetentionWindows.resolve(
                configService.read(CONFIG_KEY).map(RuntimeConfigEntry::document).orElse(null));
    }

    /** 删除界：now − 窗口天数，整秒截断（亚秒边界的字典序对齐由删除端口的比较下界处理，D1）。 */
    private Instant cutoffOf(int days) {
        return clock.instant().minus(Duration.ofDays(days)).truncatedTo(ChronoUnit.SECONDS);
    }

    /** 四段明细（段序=枚举序，全表恒四段）：{@code job_execution_log=n1; data_source_event=n2; …}。 */
    private static String detailOf(Map<RetentionLogTable, Long> counts) {
        return java.util.Arrays.stream(RetentionLogTable.values())
                .map(table -> table.physicalName() + "=" + counts.getOrDefault(table, 0L))
                .collect(Collectors.joining("; "));
    }

    /**
     * 单轮清理结果（RetentionCleanupJob 经 JobRunStats 上报：processed_count=合计、error_message=明细）。
     *
     * @param processedCount 四表删除合计
     * @param detail 四段明细（ADR-0036 §2 固定格式）
     */
    public record CleanupResult(long processedCount, String detail) {}
}
