package com.info.platform.application.recommendation;

import com.info.platform.domain.analysis.EventItemRepository;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.recommendation.RecLevel;
import com.info.platform.domain.recommendation.RecommendationCard;
import com.info.platform.domain.recommendation.RecommendationCardRepository;
import com.info.platform.domain.recommendation.RecommendationMuteRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 推荐中心读服务（应用层，M16 T133，方案 §4.8 {@code GET /api/v1/recommendations} 两端点）：卡片流（level/eventType/
 * direction/read 四维可空筛选 + beforeId 游标 + limit 缺省 20 ≤50，id DESC；枚举非法/limit 越界 → 30082 字段级 400，
 * 越界拒绝不截断——M9 口径）与详情（logicInputs 抽检对账面）。卡片大字段（figures/quote/summary/eventTime/newsTitle/ newsUrl）由
 * event_item + news_item join 直出（卡片表不冗余）；muted 按卡 comboKey 现查（视图降频标记）。 userId
 * 由接口层传入（行级权限键，沿通知中心惯例）。
 */
@Service
public class RecommendationQueryService {

    /** 卡片流页大小缺省（方案 §4.8：缺省 20）。 */
    static final int DEFAULT_LIST_LIMIT = 20;

    private static final int LIST_LIMIT_MAX = 50;

    private final RecommendationCardRepository cardRepository;

    private final EventItemRepository eventRepository;

    private final RecommendationMuteRepository muteRepository;

    private final Clock clock;

    public RecommendationQueryService(
            RecommendationCardRepository cardRepository,
            EventItemRepository eventRepository,
            RecommendationMuteRepository muteRepository,
            Clock clock) {
        this.cardRepository = cardRepository;
        this.eventRepository = eventRepository;
        this.muteRepository = muteRepository;
        this.clock = clock;
    }

    /** 卡片流分页（四维可空筛选 + 游标；total 与分页同口径——无筛选 = 该用户全量卡，§4.11 对账基准）。 */
    public RecommendationCardListView list(
            long userId,
            String level,
            String eventType,
            String direction,
            String read,
            Long beforeId,
            Integer limit) {
        RecommendationCardRepository.CardFilter filter =
                new RecommendationCardRepository.CardFilter(
                        resolveLevel(level),
                        resolveEventType(eventType),
                        resolveDirection(direction),
                        resolveRead(read));
        int pageSize = resolveLimit(limit);
        List<RecommendationCard> page =
                cardRepository.findByUserCursor(userId, filter, beforeId, pageSize);
        long total = cardRepository.countByUser(userId, filter);
        return new RecommendationCardListView(
                total,
                page.stream().map(card -> toView(card, eventJoin(page))).toList(),
                nextBeforeId(
                        page.size(),
                        pageSize,
                        total,
                        page.isEmpty() ? null : page.get(page.size() - 1).getId()));
    }

    /** 卡片详情（logicInputs = 生成输入快照，抽检对账与复现面；gen_method/prompt_version 不对用户展示）。 */
    public RecommendationCardDetailView detail(long userId, long cardId) {
        RecommendationCard card =
                cardRepository.findById(cardId).orElseThrow(RecommendationQueryService::notFound);
        if (card.getUserId() != userId) {
            throw notFound(); // 非本人卡同一 404 语义（行级权限，不泄露存在性）
        }
        return new RecommendationCardDetailView(
                toView(card, eventJoin(List.of(card))), card.getLogicInputs());
    }

    /** 批量 join event + news（figures/quote/summary/eventTime/newsTitle/newsUrl 直出；缺失事件字段留空）。 */
    private Map<Long, EventItemRepository.EventStreamItem> eventJoin(
            List<RecommendationCard> cards) {
        List<Long> eventIds =
                cards.stream().map(RecommendationCard::getEventId).distinct().toList();
        Map<Long, EventItemRepository.EventStreamItem> byEventId = new LinkedHashMap<>();
        for (EventItemRepository.EventStreamItem item :
                eventRepository.findStreamItemsByIds(eventIds)) {
            byEventId.put(item.event().getId(), item);
        }
        return byEventId;
    }

    /** 卡片视图组装（event 缺失时 summary/figures 等留空——防御，不阻断列表）。 */
    private RecommendationCardListView.CardView toView(
            RecommendationCard card, Map<Long, EventItemRepository.EventStreamItem> joined) {
        EventItemRepository.EventStreamItem item = joined.get(card.getEventId());
        return new RecommendationCardListView.CardView(
                card.getId(),
                card.getEventId(),
                card.getEventType(),
                card.getImportance(),
                card.getDirection(),
                card.getLevel().name(),
                card.getIndustries(),
                card.getSubjects().stream()
                        .map(RecommendationCardListView.SubjectView::of)
                        .toList(),
                card.getLogicChain(),
                item == null ? null : item.event().getSummary(),
                item == null
                        ? List.of()
                        : item.event().getKeyFigures().stream()
                                .map(RecommendationCardListView.FigureView::of)
                                .toList(),
                item == null ? null : item.event().getQuote(),
                card.getNewsId(),
                item == null ? null : item.newsTitle(),
                item == null ? null : item.newsUrl(),
                item == null ? null : item.event().getEventTime(),
                card.getPushStatus().name(),
                card.getPushedAt(),
                card.getCreatedAt(),
                card.isRead(),
                isMuting(card),
                null); // feedbackAction：T134 反馈闭环填充（操作条已点动作回显）
    }

    /** 该卡组合当前是否降频中（视图降频标记 + 撤销入口）。 */
    private boolean isMuting(RecommendationCard card) {
        return muteRepository
                .findActiveByUserAndCombo(card.getUserId(), card.getComboKey())
                .map(mute -> mute.isMuting(clock.instant()))
                .orElse(false);
    }

    private static Long nextBeforeId(int pageSize, int limit, long total, Long lastIdOfPage) {
        return pageSize == limit && total > limit ? lastIdOfPage : null;
    }

    private static RecLevel resolveLevel(String raw) {
        if (isAbsent(raw)) {
            return null;
        }
        try {
            return RecLevel.fromName(normalize(raw));
        } catch (IllegalArgumentException e) {
            throw filterInvalid("level", "P1 / P2 / P3", raw);
        }
    }

    private static com.info.platform.domain.analysis.EventType resolveEventType(String raw) {
        if (isAbsent(raw)) {
            return null;
        }
        com.info.platform.domain.analysis.EventType type =
                com.info.platform.domain.analysis.EventType.fromName(normalize(raw));
        if (type == null) {
            throw filterInvalid("eventType", "9 类事件枚举（如 BUYBACK_CHANGE）", raw);
        }
        return type;
    }

    private static com.info.platform.domain.analysis.Direction resolveDirection(String raw) {
        if (isAbsent(raw)) {
            return null;
        }
        com.info.platform.domain.analysis.Direction direction =
                com.info.platform.domain.analysis.Direction.fromName(normalize(raw));
        if (direction == null) {
            throw filterInvalid("direction", "BULLISH / BEARISH / NEUTRAL", raw);
        }
        return direction;
    }

    private static Boolean resolveRead(String raw) {
        if (isAbsent(raw)) {
            return null;
        }
        if (raw.trim().equals("0")) {
            return false;
        }
        if (raw.trim().equals("1")) {
            return true;
        }
        throw filterInvalid("read", "0 / 1", raw);
    }

    private static int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIST_LIMIT;
        }
        if (limit < 1 || limit > LIST_LIMIT_MAX) {
            throw new BusinessException(
                    ErrorCode.RECOMMENDATION_FILTER_INVALID,
                    "limit: 须在 1~" + LIST_LIMIT_MAX + "（越界拒绝不截断），当前值 " + limit);
        }
        return limit;
    }

    private static boolean isAbsent(String raw) {
        return raw == null || raw.isBlank();
    }

    private static String normalize(String raw) {
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private static BusinessException filterInvalid(String field, String expectation, String raw) {
        return new BusinessException(
                ErrorCode.RECOMMENDATION_FILTER_INVALID,
                field + ": 须为 " + expectation + "，当前值 " + raw);
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.RECOMMENDATION_NOT_FOUND);
    }
}
