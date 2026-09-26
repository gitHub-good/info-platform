package com.info.platform.application.valuation;

import com.info.platform.domain.valuation.FactorSnapshotRepository.SubjectRef;
import com.info.platform.domain.valuation.MarketDailySnapshotRepository;
import com.info.platform.domain.valuation.MarketDailySnapshotRepository.MarketDailyRow;
import com.info.platform.infrastructure.aggregation.TencentQuoteClient;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 行情估值日快照服务（应用层，M20 方案 §4.6 阶段 0 + ADR-0058 裁决 5）：复用既有 {@link TencentQuoteClient} （东财 f
 * 键中间结构）批量拉取全市场 A 股，映射 V30 {@code market_daily_snapshot} 列一次落库。
 *
 * <p>节奏（方案 §3.5 裁决 5.3 冻结）：50 只/请求（客户端块上限，Spike-A 已证 600 只/请求头部余量 12 倍）× 请求间 200ms 间隔（105 请求 ≈
 * 40s）。整段失败<b>降级不中止</b>：WARN 留痕返回失败报告，四维 Must 链照算（§5 降级预案）。
 *
 * <p>缺数口径：估值字段空（"-"）/≤0 落 NULL（亏损股 PE 空值 Spike-A 实测 24%）；响应行缺席的标的不落行 （NO_MARKET_DATA 由因子层如实标注）。
 */
@Service
public class MarketDataSnapshotService {

    /** 单请求标的数（= 客户端 {@code MAX_SYMBOLS_PER_REQUEST} 块上限，一次 fetchQuotes 调用即一次 HTTP 请求）。 */
    static final int REQUEST_BATCH_SIZE = 50;

    /** 相邻请求间隔（ms）——Spike-A 全轮姿态（§3.5 裁决 5.3：105 请求 × 200ms ≈ 40s）。 */
    static final long INTER_REQUEST_INTERVAL_MILLIS = 200L;

    private static final Logger log = LoggerFactory.getLogger(MarketDataSnapshotService.class);

    private final QuoteBatchClient quoteClient;

    private final MarketDailySnapshotRepository repository;

    public MarketDataSnapshotService(
            QuoteBatchClient quoteClient, MarketDailySnapshotRepository repository) {
        this.quoteClient = quoteClient;
        this.repository = repository;
    }

    /**
     * 拉取并 UPSERT 指定快照日的全市场行情（阶段 0；不抛——失败降级报告）。
     *
     * @param snapshotDate 快照口径日（Asia/Shanghai）
     * @param subjects 活跃标的名录（编排层已加载，复用免二读）
     */
    public RefreshReport refresh(LocalDate snapshotDate, List<SubjectRef> subjects) {
        String dateText = snapshotDate.toString();
        List<MarketDailyRow> rows = new ArrayList<>();
        try {
            List<SymbolRef> symbols = tencentSymbolsOf(subjects);
            for (int i = 0; i < symbols.size(); i += REQUEST_BATCH_SIZE) {
                List<SymbolRef> batch =
                        symbols.subList(i, Math.min(i + REQUEST_BATCH_SIZE, symbols.size()));
                fetchBatch(batch, dateText, rows);
                paceBetween(i, symbols.size());
            }
            int upserted = rows.isEmpty() ? 0 : repository.upsertAll(rows);
            log.info(
                    "行情日快照完成 date={} subjects={} rows={} upsert={}",
                    dateText,
                    subjects.size(),
                    rows.size(),
                    upserted);
            return new RefreshReport(true, subjects.size(), upserted);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return degrade(dateText, subjects.size(), e);
        } catch (Exception e) {
            return degrade(dateText, subjects.size(), e);
        }
    }

    private void fetchBatch(List<SymbolRef> batch, String dateText, List<MarketDailyRow> rows) {
        List<String> symbolTexts = batch.stream().map(SymbolRef::symbol).toList();
        Map<String, Map<String, Object>> fetched = quoteClient.fetchQuotes(symbolTexts);
        for (SymbolRef ref : batch) {
            Map<String, Object> fields = fetched.get(ref.symbol());
            if (fields == null) {
                continue; // 响应行缺席（无效代码/停牌）——不落行，NO_MARKET_DATA 口径由因子层判定
            }
            rows.add(toRow(ref.subjectId(), dateText, fields));
        }
    }

    /** 相邻块间隔（末块不停顿）。 */
    private static void paceBetween(int batchStart, int total) throws InterruptedException {
        if (batchStart + REQUEST_BATCH_SIZE < total) {
            Thread.sleep(INTER_REQUEST_INTERVAL_MILLIS);
        }
    }

    private RefreshReport degrade(String dateText, int totalSubjects, Exception cause) {
        log.warn("行情日快照整段失败（降级：四维照算 + F5 缺数中性）date={}: {}", dateText, cause.toString());
        return new RefreshReport(false, totalSubjects, 0);
    }

    /** f 键中间结构 → V30 行（估值 ≤0/缺失归 NULL）。 */
    private static MarketDailyRow toRow(long subjectId, String dateText, Map<String, Object> f) {
        return new MarketDailyRow(
                subjectId,
                dateText,
                decimalOf(f, "f43"),
                decimalOf(f, "f170"),
                decimalOf(f, "f168"),
                decimalOf(f, "f171"),
                decimalOf(f, "f47"),
                positiveOrNull(f, "f162"),
                positiveOrNull(f, "f167"),
                "tencent",
                textOf(f, "f30"));
    }

    private static Double decimalOf(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        return value instanceof Number number
                ? number.doubleValue()
                : null; // f43 等 BigDecimal、f47 long
    }

    private static Double positiveOrNull(Map<String, Object> fields, String key) {
        Double value = decimalOf(fields, key);
        return value != null && value > 0 ? value : null; // 空/≤0 = 缺数（V30 列注释）
    }

    private static String textOf(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private List<SymbolRef> tencentSymbolsOf(List<SubjectRef> subjects) {
        List<SymbolRef> symbols = new ArrayList<>();
        for (SubjectRef subject : subjects) {
            quoteClient
                    .toTencentSymbol(subject.code())
                    .ifPresent(value -> symbols.add(new SymbolRef(subject.id(), value)));
        }
        return symbols;
    }

    /** subject 与其腾讯符号。 */
    private record SymbolRef(long subjectId, String symbol) {}

    /** 阶段 0 报告：ok=false 表整段降级（F5 走缺数中性）；totalSubjects 参与标的数；rowsUpserted 落库行数。 */
    public record RefreshReport(boolean ok, int totalSubjects, int rowsUpserted) {}
}
