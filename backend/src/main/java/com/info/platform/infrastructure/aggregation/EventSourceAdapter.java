package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.push.AnomalyRecord;
import com.info.platform.domain.push.AnomalyRepository;
import com.info.platform.infrastructure.common.CircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.SourceCache;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 事件源真实 adapter（T08，ADR-0013）：读本地 {@code anomaly_event} 表取某标的近期异动/事件记录。
 *
 * <p><b>实现偏差</b>：技术方案/Spike-1 §6.7 原方案「复用用户环境既有 event-monitor 技能取相关重大事件」，经核实该技能在当前环境不存在 （全局搜索确认）。而
 * T13 异动检测引擎已落地并将异动事件写入本地 {@code anomaly_event} 表， 故本 adapter 改读本地表——仍不直连外部源， 与原方案「本地取数」的契约要点一致（详见
 * ADR-0013）。
 *
 * <p><b>契约对齐</b>（Spike-1 §6.7）：本地调用 {@code noRetry(500ms)}；{@code sourceLabel = "事件监控"}。
 *
 * <p><b>取数与映射</b>：经 {@link AnomalyRepository} 端口（push 域）按 7 天窗（{@link #RECENT_WINDOW}，每请求现算）
 * 分页取数——M12 T91 起 {@code doFetch} 改调 {@link AnomalyRepository#findRecentPage}（SQL 窗口+截断，落 {@code
 * idx_anomaly_subject_time} 索引） 并在 data map 附 {@code total}（{@link
 * AnomalyRepository#countRecentBySubject} 窗内精确 count），供聚合 {@code sectionPagination.event} 首屏总数提取（方案
 * §4.3.3）； 原「全量查出内存过滤 + MAX_ITEMS 截断」升级为 SQL 窗口查询，{@link #PAGE_SIZE} 常量语义从「截断上限」变为「默认页大小」。窗口内无记录 →
 * {@link Optional#empty()} → MISSING（近期无事件，不阻断）。
 *
 * <p><b>列表项映射不经 FieldMapper JSON</b>：数据源是本地领域实体（{@link AnomalyRecord}）而非外部源原始字段， 字段名已规范（无外部命名脏数据），在
 * {@link #toItem} 内直接构建（KISS），无需 {@code field-mapping/*.json} 间接层； {@link #mappingConfig} 仅做 {@code
 * items/total} 透传（模板层 {@code fieldMapper.map} 作用于整张 data map）。
 *
 * <p><b>分页取数（M12 T91，ADR-0037 决策 2/D9）</b>：{@link #fetchPage} 绕过 SourceCache 走 {@code runGuarded}
 * 骨架（与聚合同口径三态），count + 切片两查询零外呼； 空窗（total=0）→ MISSING（维持聚合页现状 missing 兜底语义）； 越界页（total&gt;0 但切片空）→
 * OK + 空列表 + total 如实（沿 ADR-0035 越界页语义）。
 *
 * <p><b>降级</b>：本地读表异常（如 DB 不可用/数据损坏解析失败）走模板默认 {@code onDegraded} → MISSING（事件分区属「有则展示、无则 missing
 * 不阻断」语义，同新闻/政策源），不阻断详情页其他分区。
 *
 * <p>与 {@link MockEventSourceAdapter} 经 {@link RoutingSourceAdapter} 运行时路由共存（T36 热切换）。
 */
public class EventSourceAdapter extends AbstractSourceAdapter {

    /** 近期事件时间窗：7 天（与 PRD 场景 1「最近 7 天相关新闻」窗口口径一致；每请求现算，翻页只在窗内进行）。 */
    private static final Duration RECENT_WINDOW = Duration.ofDays(7);

    /** 默认页大小（M12 起 MAX_ITEMS 语义升级：首屏聚合取数页大小，截断逻辑移入 SQL LIMIT）。 */
    static final int PAGE_SIZE = 10;

    /** 本地调用超时（Spike-1 §6.7 契约要点：noRetry 500ms）。 */

    /**
     * 模板层 map 步骤用：整张 data map（{@code {"items":[...], "total":N}}）的 items 列表与 total 原样透传。
     * 逐条字段映射在取数路径内用 {@link #toItem} 完成。
     */
    private static final List<FieldMapping> ITEMS_PASSTHROUGH =
            List.of(
                    new FieldMapping("items", "items", Transform.NONE),
                    new FieldMapping("total", "total", Transform.NONE));

    private final AnomalyRepository anomalyRepository;

    public EventSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            AnomalyRepository anomalyRepository) {
        super(cache, fieldMapper, runner, breaker);
        this.anomalyRepository = anomalyRepository;
    }

    @Override
    public SourceCode sourceCode() {
        return SourceCode.EVENT;
    }

    @Override
    protected String sourceLabel() {
        return "事件监控";
    }

    @Override
    protected List<FieldMapping> mappingConfig() {
        return ITEMS_PASSTHROUGH;
    }

    @Override
    protected Optional<RawFetch> doFetch(Subject subject) {
        Instant since = Instant.now().minus(RECENT_WINDOW);
        long total = anomalyRepository.countRecentBySubject(subject.getId(), since);
        if (total == 0) {
            return Optional.empty();
        }
        List<Map<String, Object>> items =
                toItems(anomalyRepository.findRecentPage(subject.getId(), since, 0, PAGE_SIZE));
        return Optional.of(new RawFetch(pageData(items, total), sourceLabel(), Instant.now()));
    }

    /** 分区子端点分页取数（M12 T91，方案 §4.1.2/§4.3.3）：窗界每请求现算（与首屏同口径）， count 精确 + 切片 LIMIT/OFFSET，零外呼。 */
    @Override
    public SourceResult fetchPage(Subject subject, int page, int size) {
        return runGuarded(subject, () -> doFetchPage(subject, page, size));
    }

    private Optional<RawFetch> doFetchPage(Subject subject, int page, int size) {
        Instant since = Instant.now().minus(RECENT_WINDOW);
        long total = anomalyRepository.countRecentBySubject(subject.getId(), since);
        if (total == 0) {
            // 空窗 → MISSING（维持聚合页现状 missing 兜底语义，前端不渲染分页条）
            return Optional.empty();
        }
        int offset = (page - 1) * size;
        List<Map<String, Object>> items =
                toItems(anomalyRepository.findRecentPage(subject.getId(), since, offset, size));
        return Optional.of(new RawFetch(pageData(items, total), sourceLabel(), Instant.now()));
    }

    /**
     * 近期窗口判定（含边界）：{@code triggerTime >= now - 7d} 算近期。 trigger_time 由 DB 约束非空（V5 DDL NOT NULL），无
     * null 分支。窗口口径单点保留（M12 起主路径走 SQL 窗口，本方法供口径断言与遗留调用方复用）。
     */
    static boolean withinRecentWindow(AnomalyRecord record, Instant now) {
        return !record.getTriggerTime().isBefore(now.minus(RECENT_WINDOW));
    }

    private static List<Map<String, Object>> toItems(List<AnomalyRecord> records) {
        return records.stream().map(EventSourceAdapter::toItem).toList();
    }

    private static Map<String, Object> pageData(List<Map<String, Object>> items, long total) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", List.copyOf(items));
        data.put("total", total);
        return Collections.unmodifiableMap(data);
    }

    /**
     * 单条异动记录 → 列表项（字段名与其他列表分区 camelCase 口径一致）： anomalyType 枚举名（前端映射展示标签）、changePct / currentPrice
     * 触发时数值（可空，缺省不产出）、triggerTime ISO-8601 整秒串、detail 人读详情（可空）。
     */
    private static Map<String, Object> toItem(AnomalyRecord record) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("anomalyType", record.getAnomalyType().name());
        record.getChangePct().ifPresent(value -> item.put("changePct", value));
        record.getCurrentPrice().ifPresent(value -> item.put("currentPrice", value));
        item.put("triggerTime", record.getTriggerTime().toString());
        record.getDetail().ifPresent(value -> item.put("detail", value));
        return Collections.unmodifiableMap(item);
    }
}
