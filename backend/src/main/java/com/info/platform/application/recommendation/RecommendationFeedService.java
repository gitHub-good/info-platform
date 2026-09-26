package com.info.platform.application.recommendation;

import com.info.platform.application.recommendation.RecommendationAssociationService.AssociationContext;
import com.info.platform.application.recommendation.RecommendationAssociationService.AssociationResult;
import com.info.platform.application.recommendation.RecommendationCardService.GenerationOutcome;
import com.info.platform.domain.common.User;
import com.info.platform.domain.common.UserRepository;
import com.info.platform.domain.recommendation.CardGenMethod;
import com.info.platform.domain.recommendation.RecommendationCardRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 推荐事件消费编排（应用层，M16 T133，ADR-0051 裁决 2 / 方案 §3.2/§4.6）：每用户一轮——装配 {@link AssociationContext} （每用户每
 * tick 一次，逐事件复用）→ 扫未消费事件（LEFT JOIN recommendation_card 去重 + 20s 落库缓冲 + 24h 补跑窗 + news 标题 join）→
 * 触发/三级关联（无命中零落卡零留痕）→ {@link RecommendationCardService} 建卡（幂等）→ {@link RecommendationPushGate}
 * 推送闸门（含当日遗留 PENDING 重推语义）。
 *
 * <p><b>段式容错</b>：单用户异常记 ERROR 不阻断其他用户（整轮异常不上抛——ManagedJob 惯例）；JobRunStats 明细 {@code feed=scan:n;
 * trig:n; hit:n; card=llm:x/tpl:y; push:n; mute:n; quota:n}。
 */
@Service
public class RecommendationFeedService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationFeedService.class);

    /** 单用户单轮扫描上限（防御性；事件日增 ~130，24h 窗内未消费量有界）。 */
    public static final int FEED_SCAN_CAP = 200;

    private final UserRepository userRepository;

    private final RecommendationAssociationService associationService;

    private final RecommendationCardService cardService;

    private final RecommendationPushGate pushGate;

    private final RecommendationCardRepository cardRepository;

    private final RecommendationSettings settings;

    private final Clock clock;

    public RecommendationFeedService(
            UserRepository userRepository,
            RecommendationAssociationService associationService,
            RecommendationCardService cardService,
            RecommendationPushGate pushGate,
            RecommendationCardRepository cardRepository,
            RecommendationSettings settings,
            Clock clock) {
        this.userRepository = userRepository;
        this.associationService = associationService;
        this.cardService = cardService;
        this.pushGate = pushGate;
        this.cardRepository = cardRepository;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * 执行一轮消费（整轮异常不上抛——ManagedJob 惯例）。
     *
     * @return tick 报告（JobRunStats 留痕）
     */
    public FeedReport tick() {
        Instant now = clock.instant();
        Instant createdBefore = now.minus(Duration.ofSeconds(settings.feedBufferSeconds()));
        Instant createdSince = now.minus(Duration.ofHours(settings.scanWindowHours()));
        TickStats stats = new TickStats();
        for (User user : userRepository.findAll()) {
            try {
                consumeForUser(user.getId(), createdBefore, createdSince, stats);
            } catch (RuntimeException e) {
                // 单用户失败不阻断其他用户（热路径单点隔离，沿推送广播惯例）
                log.error("推荐消费单用户段失败: userId={} {}", user.getId(), e.toString(), e);
            }
        }
        String detail =
                "feed=scan:"
                        + stats.scan
                        + "; trig:"
                        + stats.trig
                        + "; hit:"
                        + stats.hit
                        + "; card=llm:"
                        + stats.llmCards
                        + "/tpl:"
                        + stats.templateCards
                        + "; push:"
                        + stats.pushed
                        + "; mute:"
                        + stats.muted
                        + "; quota:"
                        + stats.quotaSkipped;
        log.info("推荐消费 tick 完成: {}", detail);
        return new FeedReport(
                detail,
                stats.llmCards
                        + stats.templateCards
                        + stats.pushed
                        + stats.muted
                        + stats.quotaSkipped);
    }

    /** 单用户消费段：上下文装配一次 → 扫描 → 逐事件关联建卡 → 推送闸门。 */
    private void consumeForUser(
            long userId, Instant createdBefore, Instant createdSince, TickStats stats) {
        AssociationContext context = associationService.buildContext(userId);
        List<RecommendationCardRepository.FeedEvent> events =
                cardRepository.findUnconsumedEvents(
                        userId, createdBefore, createdSince, FEED_SCAN_CAP);
        stats.scan += events.size();
        for (RecommendationCardRepository.FeedEvent feedEvent : events) {
            stats.trig++;
            Optional<AssociationResult> association =
                    associationService.associate(feedEvent.event(), feedEvent.newsTitle(), context);
            if (association.isEmpty()) {
                continue; // 无命中：不生成卡片零留痕（事件流可查）
            }
            stats.hit++;
            GenerationOutcome outcome =
                    cardService.generate(
                            userId, feedEvent.event(), feedEvent.newsTitle(), association.get());
            if (!outcome.inserted()) {
                continue; // 幂等冲突（重复消费直返，不计卡）
            }
            if (outcome.genMethod() == CardGenMethod.LLM) {
                stats.llmCards++;
            } else {
                stats.templateCards++;
            }
        }
        RecommendationPushGate.GateReport gate = pushGate.run(userId);
        stats.pushed += gate.pushed();
        stats.muted += gate.muted();
        stats.quotaSkipped += gate.quotaSkipped();
    }

    /** tick 报告（JobRunStats 留痕）。 */
    public record FeedReport(String detail, int processed) {}

    /** 轮内累计计数（JobExecutor 同 jobKey CAS 守卫保证串行，普通字段即可）。 */
    private static class TickStats {
        int scan;
        int trig;
        int hit;
        int llmCards;
        int templateCards;
        int pushed;
        int muted;
        int quotaSkipped;
    }
}
