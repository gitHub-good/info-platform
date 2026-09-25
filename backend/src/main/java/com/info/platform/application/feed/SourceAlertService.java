package com.info.platform.application.feed;

import com.info.platform.domain.push.SourceAlertEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 源异常告警评估服务（M14 T115，REQ-20260925-11 故事 3）：轮次结束后评估双阈值并发布 {@link SourceAlertEvent}， 经 {@code
 * PushService} 消费走通知中心 SSE 链路。
 *
 * <h2>双阈值（缺省 5 轮 / 30 分钟，取先到）</h2>
 *
 * <ul>
 *   <li>轮数阈值：consecutiveFailures ≥ {@code feed.alert.failure-threshold}（缺省 5）——快讯源主通道；
 *   <li>时间阈值：本轮退避窗 ≥ {@code feed.alert.backoff-threshold-minutes}（缺省 30 分钟）——低频源（30~60min
 *       官方/宏观）连续失败数攒得慢，靠退避指数扩张先行触达「30 分钟无产出」语义（ADR-0045 裁量）。
 * </ul>
 *
 * <h2>节流与恢复</h2>
 *
 * 同源告警后<b>恢复前不重复</b>（告警态集合原子占位）；恢复成功发一条 SOURCE_RECOVERED（Should， {@code
 * feed.alert.recovered-enabled} 缺省开）并重新武装——新一轮失败达阈值再次告警（episodeKey 取轮次时刻，幂等键不跨episode误吞）。
 * 告警态在内存（重启丢失：仍失败的源下一轮重新告警一次，个人平台可容忍——ADR-0045）。
 *
 * <h2>旁路契约</h2>
 *
 * 本服务任何异常不外抛（轮次主链路不被拖垮，REQ 场景 4）；发布失败记 ERROR。
 */
@Service
public class SourceAlertService {

    private static final Logger log = LoggerFactory.getLogger(SourceAlertService.class);

    /** 缺省连续失败阈值（REQ 故事 3 场景 1：可配，缺省 5 轮）。 */
    static final int DEFAULT_FAILURE_THRESHOLD = 5;

    /** 缺省退避窗阈值分钟（REQ：或 30 分钟，取先到）。 */
    static final long DEFAULT_BACKOFF_THRESHOLD_MINUTES = 30;

    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final int failureThreshold;
    private final long backoffThresholdMinutes;
    private final boolean recoveredEnabled;

    /** 告警态登记：sourceCode → 告警时连续失败数（恢复通知文案用）；恢复后移除。 */
    private final Map<String, Integer> alertingSources = new ConcurrentHashMap<>();

    public SourceAlertService(
            ApplicationEventPublisher publisher,
            Clock clock,
            @Value("${feed.alert.failure-threshold:5}") int failureThreshold,
            @Value("${feed.alert.backoff-threshold-minutes:30}") long backoffThresholdMinutes,
            @Value("${feed.alert.recovered-enabled:true}") boolean recoveredEnabled) {
        this.publisher = publisher;
        this.clock = clock;
        this.failureThreshold = failureThreshold > 0 ? failureThreshold : DEFAULT_FAILURE_THRESHOLD;
        this.backoffThresholdMinutes =
                backoffThresholdMinutes > 0
                        ? backoffThresholdMinutes
                        : DEFAULT_BACKOFF_THRESHOLD_MINUTES;
        this.recoveredEnabled = recoveredEnabled;
    }

    /**
     * 轮次结束回调（成功/失败统一入口；FeedIngestService 在状态落库后调用，异常自吞）。
     *
     * @param round 轮次结果（见 {@link SourceAlertRound}）
     */
    public void onRoundFinished(SourceAlertRound round) {
        try {
            evaluate(round);
        } catch (Exception e) {
            log.error(
                    "源告警评估异常（旁路不上抛） source={} success={}: {}",
                    round.sourceCode(),
                    round.success(),
                    e.toString(),
                    e);
        }
    }

    private void evaluate(SourceAlertRound round) {
        if (round.success()) {
            Integer failuresAtAlert = alertingSources.remove(round.sourceCode());
            if (failuresAtAlert != null && recoveredEnabled) {
                publisher.publishEvent(
                        new SourceAlertEvent(
                                SourceAlertEvent.Kind.RECOVERED,
                                round.sourceCode(),
                                round.sourceName(),
                                failuresAtAlert,
                                null,
                                null,
                                round.roundAt()));
                log.info("源恢复通知已发布 source={} 此前连续失败={} 轮", round.sourceCode(), failuresAtAlert);
            }
            return;
        }
        if (!thresholdHit(round)) {
            return;
        }
        // 节流：告警后恢复前同源不重复（原子占位，占位失败即已有告警在案）
        if (alertingSources.putIfAbsent(round.sourceCode(), round.consecutiveFailures()) != null) {
            return;
        }
        publisher.publishEvent(
                new SourceAlertEvent(
                        SourceAlertEvent.Kind.ALERT,
                        round.sourceCode(),
                        round.sourceName(),
                        round.consecutiveFailures(),
                        round.lastSuccessAt(),
                        round.errorSummary(),
                        round.roundAt()));
        log.warn(
                "源异常告警已发布 source={} consecutiveFailures={} backoffUntil={}",
                round.sourceCode(),
                round.consecutiveFailures(),
                round.backoffUntil());
    }

    /** 双阈值取先到：轮数达标或本轮退避窗达标。 */
    private boolean thresholdHit(SourceAlertRound round) {
        if (round.consecutiveFailures() >= failureThreshold) {
            return true;
        }
        return round.backoffUntil() != null
                && Duration.between(round.roundAt(), round.backoffUntil()).toMinutes()
                        >= backoffThresholdMinutes;
    }

    /**
     * 轮次结果值对象（FeedIngestService 装配）。
     *
     * @param sourceCode 源稳定代码
     * @param sourceName 源展示名（通知文案）
     * @param success true = 本轮成功（恢复评估）；false = 失败（阈值评估）
     * @param consecutiveFailures 失败轮自增后的连续失败数（成功轮为 0）
     * @param roundAt 轮次结束时刻（告警 episodeKey 与退避窗基准）
     * @param backoffUntil 本轮退避截止（成功轮为 null）
     * @param lastSuccessAt 最近成功时刻（告警「持续时长」文案基准；可为 null）
     * @param errorSummary 最近失败摘要（成功轮为 null）
     */
    public record SourceAlertRound(
            String sourceCode,
            String sourceName,
            boolean success,
            int consecutiveFailures,
            Instant roundAt,
            Instant backoffUntil,
            Instant lastSuccessAt,
            String errorSummary) {}
}
