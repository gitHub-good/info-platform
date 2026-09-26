package com.info.platform.application.recommendation;

import com.info.platform.application.ai.ReadingEventService;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.ai.ReadingEventType;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.recommendation.FeedbackAction;
import com.info.platform.domain.recommendation.RecommendationCard;
import com.info.platform.domain.recommendation.RecommendationCardRepository;
import com.info.platform.domain.recommendation.RecommendationFeedback;
import com.info.platform.domain.recommendation.RecommendationFeedbackRepository;
import com.info.platform.domain.recommendation.RecommendationMute;
import com.info.platform.domain.recommendation.RecommendationMuteRepository;
import com.info.platform.domain.subscription.Watchlist;
import com.info.platform.domain.subscription.WatchlistRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 反馈闭环服务（应用层，M16 T134，方案 §4.7 / REQ 拍板六）：四动作 + 已读——
 *
 * <ul>
 *   <li><b>USEFUL</b>：流水留痕 + card.adopted 条件置 1 + ACT 埋点画像回流（subjectCode=标的区首标的——readStats 热度上调，后续卡
 *       profileCoef 上升）。
 *   <li><b>DISLIKE</b>：mute UPSERT（新组合 7 天 / LIFTED → reactivate 续 7 天）；<b>升级判定</b> = feedback 流水滚动
 *       escalateWindowDays(30) 天内该组合 DISLIKE ≥ escalateThreshold(3) 次（含本次）→ mute_days=30 静默 （Should
 *       条款）；负反馈不计负画像（REQ 拍板六-3，mute 表已承载避免双重惩罚）。
 *   <li><b>ADD_WATCHLIST</b>：subjectCode 必填（30081）+ 复用既有自选仓储（首个清单，无清单自动建「默认清单」； 已在清单幂等成功）+ adopted
 *       置 1 + ACT + 标的区 inWatchlist 回写刷新。
 *   <li><b>UNDO_MUTE</b>：mute → LIFTED（后续新卡恢复推送资格，历史 SKIPPED_MUTED 不补推）；LIFTED 再撤销 no-op。
 *   <li><b>read</b>：read=1 + adopted 条件置 1 + ACT（REQ 拍板六-4「展开即采纳」）；幂等 200 直返。
 * </ul>
 *
 * <p><b>幂等</b>：同卡同动作 1h 窗口内重复 → 200 直返不落第二条流水（沿 reading_event 去重惯例）；ACT 埋点与 adopted
 * 条件置位<b>同点写入</b>（先置位成功再落埋点——§4.7 对账恒等断言 {@code COUNT(adopted=1) == COUNT(DISTINCT ACT)}）。
 */
@Service
public class RecommendationFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationFeedbackService.class);

    /** 同卡同动作幂等窗口（1h——沿 ReadingEventService DEDUP_WINDOW 惯例）。 */
    static final Duration FEEDBACK_DEDUP_WINDOW = Duration.ofHours(1);

    /** 无清单用户自动创建的默认清单名（ADD_WATCHLIST 最小实现）。 */
    static final String DEFAULT_WATCHLIST_NAME = "默认清单";

    private final RecommendationCardRepository cardRepository;

    private final RecommendationFeedbackRepository feedbackRepository;

    private final RecommendationMuteRepository muteRepository;

    private final ReadingEventService readingEventService;

    private final WatchlistRepository watchlistRepository;

    private final SubjectRepository subjectRepository;

    private final RecommendationSettings settings;

    private final Clock clock;

    public RecommendationFeedbackService(
            RecommendationCardRepository cardRepository,
            RecommendationFeedbackRepository feedbackRepository,
            RecommendationMuteRepository muteRepository,
            ReadingEventService readingEventService,
            WatchlistRepository watchlistRepository,
            SubjectRepository subjectRepository,
            RecommendationSettings settings,
            Clock clock) {
        this.cardRepository = cardRepository;
        this.feedbackRepository = feedbackRepository;
        this.muteRepository = muteRepository;
        this.readingEventService = readingEventService;
        this.watchlistRepository = watchlistRepository;
        this.subjectRepository = subjectRepository;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * 四动作反馈（POST /recommendations/{id}/feedback）。
     *
     * @param actionName USEFUL / DISLIKE / ADD_WATCHLIST / UNDO_MUTE（非法 → 30081）
     * @param subjectCode ADD_WATCHLIST 必填标的代码
     * @throws BusinessException 30080 卡片不存在/非本人；30081 action 非法或 subjectCode 缺失/解析不到
     */
    public FeedbackResult feedback(
            long userId, long cardId, String actionName, String subjectCode) {
        FeedbackAction action = parseAction(actionName);
        RecommendationCard card = ownedCard(userId, cardId);
        Instant now = clock.instant();
        if (feedbackRepository.existsSince(
                userId, cardId, action, now.minus(FEEDBACK_DEDUP_WINDOW))) {
            log.info("反馈幂等直返（1h 窗口内重复）: userId={} cardId={} action={}", userId, cardId, action);
            return currentMuteOutcome(userId, card);
        }
        return switch (action) {
            case USEFUL -> useful(userId, card);
            case DISLIKE -> dislike(userId, card, now);
            case ADD_WATCHLIST -> addWatchlist(userId, card, subjectCode);
            case UNDO_MUTE -> undoMute(userId, card);
        };
    }

    /** 已读 + 隐式采纳（POST /recommendations/{id}/read；幂等 200 直返）。 */
    public void markRead(long userId, long cardId) {
        RecommendationCard card = ownedCard(userId, cardId);
        cardRepository.markRead(card.getId());
        adoptAndTrack(userId, card); // 首次置位成功才落 ACT（REQ 拍板六-4：展开即采纳）
        log.info("推荐卡已读: userId={} cardId={}", userId, cardId);
    }

    private FeedbackResult useful(long userId, RecommendationCard card) {
        feedbackRepository.append(
                RecommendationFeedback.append(userId, card.getId(), FeedbackAction.USEFUL, null));
        adoptAndTrack(userId, card);
        log.info("有用反馈: userId={} cardId={}", userId, card.getId());
        return new FeedbackResult(null, null);
    }

    private FeedbackResult dislike(long userId, RecommendationCard card, Instant now) {
        String comboKey = card.getComboKey();
        feedbackRepository.append(
                RecommendationFeedback.append(
                        userId, card.getId(), FeedbackAction.DISLIKE, comboKey));
        // 升级判定以流水滚动窗为准（含本次——append 先于计数）；trigger_count 仅留痕展示
        long recentDislikes =
                feedbackRepository.countDislikeByUserAndComboSince(
                        userId,
                        comboKey,
                        now.minus(Duration.ofDays(settings.escalateWindowDays())).toString());
        boolean escalated = recentDislikes >= settings.escalateThreshold();
        int muteDays = escalated ? settings.escalatedDays() : settings.mutedDays();
        RecommendationMute saved =
                muteRepository.upsert(buildMuteUpsert(userId, comboKey, muteDays, now));
        log.info(
                "不感兴趣反馈: userId={} cardId={} comboKey={} muteDays={} escalated={}",
                userId,
                card.getId(),
                comboKey,
                muteDays,
                escalated);
        return new FeedbackResult(saved.getMutedUntil().toString(), escalated);
    }

    /** UPSERT 入参组装：已有行（含 LIFTED）reactivate 覆盖续期 + triggerCount 增量；新组合 create。 */
    private RecommendationMute buildMuteUpsert(
            long userId, String comboKey, int muteDays, Instant now) {
        return muteRepository
                .findByUserAndCombo(userId, comboKey)
                .map(
                        existing ->
                                RecommendationMute.reconstruct(
                                        existing.getId(),
                                        userId,
                                        comboKey,
                                        muteDays,
                                        now.plus(Duration.ofDays(muteDays)),
                                        existing.getTriggerCount() + 1,
                                        now,
                                        com.info.platform.domain.recommendation.MuteStatus.ACTIVE,
                                        existing.getCreatedAt(),
                                        now))
                .orElseGet(() -> RecommendationMute.create(userId, comboKey, muteDays, now));
    }

    private FeedbackResult addWatchlist(long userId, RecommendationCard card, String subjectCode) {
        if (subjectCode == null || subjectCode.isBlank()) {
            throw new BusinessException(
                    ErrorCode.RECOMMENDATION_FEEDBACK_INVALID, "ADD_WATCHLIST 必须携带 subjectCode");
        }
        SubjectCode code = SubjectCode.of(subjectCode.trim());
        Subject subject =
                subjectRepository
                        .findByCode(code)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RECOMMENDATION_FEEDBACK_INVALID,
                                                "subjectCode 解析不到标的: " + subjectCode));
        Watchlist list = firstWatchlistOrCreate(userId);
        if (!watchlistRepository.existsItemByWatchlistAndSubject(list.getId(), subject.getId())) {
            watchlistRepository.saveItem(
                    com.info.platform.domain.subscription.WatchlistItem.create(
                            list.getId(), subject.getId(), null));
        }
        feedbackRepository.append(
                RecommendationFeedback.append(
                        userId, card.getId(), FeedbackAction.ADD_WATCHLIST, null));
        adoptAndTrack(userId, card);
        cardRepository.updateSubjects(card.getId(), withInWatchlist(card, code.value()));
        log.info("加自选反馈: userId={} cardId={} subject={}", userId, card.getId(), code.value());
        return new FeedbackResult(null, null);
    }

    private FeedbackResult undoMute(long userId, RecommendationCard card) {
        String comboKey = card.getComboKey();
        feedbackRepository.append(
                RecommendationFeedback.append(
                        userId, card.getId(), FeedbackAction.UNDO_MUTE, comboKey));
        int lifted = muteRepository.liftByUserAndCombo(userId, comboKey); // 已 LIFTED/未建 no-op
        log.info(
                "撤销降频: userId={} cardId={} comboKey={} lifted={}",
                userId,
                card.getId(),
                comboKey,
                lifted);
        return new FeedbackResult(null, null);
    }

    /** 采纳同点写入：条件置 1 成功（首次）才落 ACT 埋点——对账恒等（§4.7）与画像回流（subjectCode=标的区首标的）。 */
    private void adoptAndTrack(long userId, RecommendationCard card) {
        if (cardRepository.markAdopted(card.getId()) > 0) {
            readingEventService.record(
                    userId,
                    ReadingEventType.RECOMMENDATION_ACT.persistentName(),
                    String.valueOf(card.getId()),
                    firstSubjectCode(card),
                    null);
        }
    }

    /** 幂等直返时的回显（DISLIKE 重复 → 现查当前降频到期时刻；其余动作无态）。 */
    private FeedbackResult currentMuteOutcome(long userId, RecommendationCard card) {
        return muteRepository
                .findByUserAndCombo(userId, card.getComboKey())
                .filter(mute -> mute.isMuting(clock.instant()))
                .map(mute -> new FeedbackResult(mute.getMutedUntil().toString(), null))
                .orElse(new FeedbackResult(null, null));
    }

    /** 标的区首标的标准代码（画像回流 subjectId 解析入口；空标的区返回 null——政策类事件无标的关联）。 */
    private static String firstSubjectCode(RecommendationCard card) {
        return card.getSubjects().isEmpty() ? null : card.getSubjects().get(0).code();
    }

    /** 标的区快照刷新：命中标 的 inWatchlist 翻 true（其余条目原样保留）。 */
    private static List<RecommendationCard.CardSubject> withInWatchlist(
            RecommendationCard card, String subjectCode) {
        return card.getSubjects().stream()
                .map(
                        subject ->
                                subject.code() != null && subject.code().equals(subjectCode)
                                        ? new RecommendationCard.CardSubject(
                                                subject.code(),
                                                subject.name(),
                                                subject.industry(),
                                                true)
                                        : subject)
                .toList();
    }

    /** 用户首个启用清单；无清单自动建「默认清单」（ADD_WATCHLIST 最小实现——单用户平台默认收口一处）。 */
    private Watchlist firstWatchlistOrCreate(long userId) {
        Optional<Watchlist> first =
                watchlistRepository.findAllByOwnerId(userId).stream().findFirst();
        return first.orElseGet(
                () ->
                        watchlistRepository.save(
                                Watchlist.create(userId, DEFAULT_WATCHLIST_NAME, null)));
    }

    private static FeedbackAction parseAction(String actionName) {
        try {
            return FeedbackAction.fromName(
                    actionName == null ? null : actionName.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    ErrorCode.RECOMMENDATION_FEEDBACK_INVALID,
                    "action: 须为 USEFUL / DISLIKE / ADD_WATCHLIST / UNDO_MUTE，当前值 " + actionName);
        }
    }

    private RecommendationCard ownedCard(long userId, long cardId) {
        RecommendationCard card =
                cardRepository
                        .findById(cardId)
                        .orElseThrow(RecommendationFeedbackService::notFound);
        if (card.getUserId() != userId) {
            throw notFound(); // 非本人卡同一 404 语义（行级权限，不泄露存在性）
        }
        return card;
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.RECOMMENDATION_NOT_FOUND);
    }

    /**
     * 反馈响应（§4.8：{@code {muteUntil?, escalated?}}）。
     *
     * @param muteUntil DISLIKE 返回新的降频到期时刻（ISO-8601）；其余动作 null
     * @param escalated DISLIKE 返回是否触发升级静默（30 天）；幂等直返无该语义为 null
     */
    public record FeedbackResult(String muteUntil, Boolean escalated) {}
}
