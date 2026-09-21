package com.info.platform.application.push;

import com.info.platform.domain.aggregation.SourceAdapter;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.SourceStatus;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.push.AnomalyDetectedEvent;
import com.info.platform.domain.push.AnomalyRecord;
import com.info.platform.domain.push.AnomalyRepository;
import com.info.platform.domain.push.AnomalyType;
import com.info.platform.domain.subscription.WatchlistItem;
import com.info.platform.domain.subscription.WatchlistRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 异动检测引擎（应用层，对齐技术方案 §4.3 流程 3 + ADR-0006）。
 *
 * <p>编排：@Scheduled 每 10s（可配 {@code anomaly.detect-interval-millis}）遍历全部活跃 watchlist_item → 对每项取
 * Subject → 调 QUOTE 行情 adapter 取数（复用 adapter 享 5s TTL 缓存 + 弹性降级，多 item 同标的缓存命中）→ 取 changePct →
 * {@code |changePct| >= 阈值} 时查重（existsByBusinessKey=subjectId+type+当日）→ 未存在则 INSERT anomaly_event +
 * 发布 {@link AnomalyDetectedEvent}（Spring {@link ApplicationEventPublisher}）→ T14 PushService 消费推送。
 *
 * <p>容错：任一 item 行情不可用（MISSING/FAILED）或 fetch 抛异常 → 记 WARN/DEBUG 跳过该 item，不阻断其他 item、不抛
 * 出（实时行情热路径，单源缺失不该拖垮整轮）。约定异动触发到推送 <=15s（轮询 10s + 处理 5s 内）。
 *
 * <p>测试：@Scheduled 不在 {@code @SpringBootTest} 触发（本类与调度开关同受 {@code anomaly.detect.enabled} 约束， 测试
 * profile 置 false）；单测直接调 {@link #detectForItem} 验证阈值/去重/发事件/降级各分支。
 */
@Component
@ConditionalOnProperty(name = "anomaly.detect.enabled", havingValue = "true")
public class AnomalyDetectionJob {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectionJob.class);

    /** 行情规范化字段名（FieldMapper 映射后）：涨跌幅 % / 现价。 */
    private static final String FIELD_CHANGE_PCT = "changePct";

    private static final String FIELD_PRICE = "price";

    private final WatchlistRepository watchlistRepository;
    private final SubjectRepository subjectRepository;
    private final SourceAdapter quoteAdapter;
    private final AnomalyRepository anomalyRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public AnomalyDetectionJob(
            WatchlistRepository watchlistRepository,
            SubjectRepository subjectRepository,
            List<SourceAdapter> adapters,
            AnomalyRepository anomalyRepository,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.watchlistRepository = watchlistRepository;
        this.subjectRepository = subjectRepository;
        this.quoteAdapter =
                adapters.stream()
                        .filter(a -> a.sourceCode() == SourceCode.QUOTE)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("未装配 QUOTE 行情 SourceAdapter"));
        this.anomalyRepository = anomalyRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * 异动检测轮询入口（@Scheduled 每 10s）。
     *
     * <p>遍历全部活跃清单项；每项独立 try-catch，单项异常不阻断整轮。
     */
    @Scheduled(fixedDelayString = "${anomaly.detect-interval-millis:10000}")
    public void detect() {
        List<WatchlistItem> items = watchlistRepository.findAllActiveItems();
        if (items.isEmpty()) {
            return;
        }
        log.debug("异动检测轮询开始，活跃清单项 {} 个", items.size());
        for (WatchlistItem item : items) {
            try {
                detectForItem(item);
            } catch (Exception e) {
                // 实时热路径：单 item 异常不拖垮整轮，记 WARN 跳过
                log.warn(
                        "异动检测异常，跳过 itemId={} subjectId={}: {}",
                        item.getId(),
                        item.getSubjectId(),
                        e.toString());
            }
        }
    }

    /**
     * 单标的异动检测（包级可见，便于单测直调）。
     *
     * <p>判定链：标的不存在 → 跳过；行情非 OK → 跳过；缺 changePct → 跳过；未达阈值 → 跳过；同日已记 → 去重跳过； 否则入库 + 发事件。
     */
    void detectForItem(WatchlistItem item) {
        Long subjectId = item.getSubjectId();
        Optional<Subject> subjectOpt = subjectRepository.findById(subjectId);
        if (subjectOpt.isEmpty()) {
            log.warn("异动检测：标的不存在，跳过 subjectId={}", subjectId);
            return;
        }
        Subject subject = subjectOpt.get();

        SourceResult result = quoteAdapter.fetch(subject);
        if (result.getStatus() != SourceStatus.OK) {
            log.debug("异动检测：行情不可用，跳过 subjectId={} status={}", subjectId, result.getStatus());
            return;
        }
        BigDecimal changePct = extractDecimal(result.getData(), FIELD_CHANGE_PCT);
        if (changePct == null) {
            log.warn("异动检测：行情缺 changePct，跳过 subjectId={}", subjectId);
            return;
        }
        BigDecimal currentPrice = extractDecimal(result.getData(), FIELD_PRICE);
        BigDecimal threshold =
                item.getAnomalyThreshold() != null
                        ? item.getAnomalyThreshold()
                        : WatchlistItem.DEFAULT_THRESHOLD;

        // >= 阈值触发（边界 = 阈值也触发，对齐 04 测试矩阵「异动阈值边界（=3.00 触发）」）
        if (changePct.abs().compareTo(threshold) < 0) {
            return;
        }

        Instant triggerTime = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        LocalDate triggerDate = LocalDate.now(clock); // clock 为 systemUTC，故为 UTC 自然日（交易时段不过 UTC 零点）
        if (anomalyRepository.existsByBusinessKey(
                subjectId, AnomalyType.PRICE_CHANGE, triggerDate)) {
            log.debug("异动检测：同日已记录，去重跳过 subjectId={} date={}", subjectId, triggerDate);
            return;
        }

        AnomalyRecord record =
                AnomalyRecord.create(
                        subjectId,
                        AnomalyType.PRICE_CHANGE,
                        changePct,
                        currentPrice,
                        triggerTime,
                        buildDetail(changePct, currentPrice, threshold));
        AnomalyRecord saved = anomalyRepository.save(record);
        eventPublisher.publishEvent(
                new AnomalyDetectedEvent(
                        saved.getSubjectId(),
                        saved.getAnomalyType(),
                        saved.getChangePct().orElse(null),
                        saved.getCurrentPrice().orElse(null),
                        saved.getTriggerTime()));
        log.info(
                "异动触发: subjectId={} changePct={} threshold={} triggerTime={}",
                subjectId,
                changePct,
                threshold,
                triggerTime);
    }

    /** 从规范化行情字段提取 BigDecimal（FieldMapper.toDecimal 已产出 BigDecimal，此处对 Number/String 兼容兜底）。 */
    private static BigDecimal extractDecimal(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String string) {
            try {
                return new BigDecimal(string.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String buildDetail(
            BigDecimal changePct, BigDecimal currentPrice, BigDecimal threshold) {
        StringBuilder sb = new StringBuilder();
        sb.append("日涨跌幅 ").append(changePct.toPlainString()).append("%");
        sb.append(" 触发阈值 ").append(threshold.toPlainString()).append("%");
        if (currentPrice != null) {
            sb.append("（现价 ").append(currentPrice.toPlainString()).append("）");
        }
        return sb.toString();
    }
}
