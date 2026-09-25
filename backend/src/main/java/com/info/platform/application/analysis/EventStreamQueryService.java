package com.info.platform.application.analysis;

import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventItemRepository;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.analysis.IndustryCategory;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * 事件流读服务（应用层，M15 T127，方案 §4.8 {@code GET /api/v1/events}）：四维可空筛选（type/industry/importance/
 * direction）+ beforeId 游标（id DESC）。参数校验：筛选枚举非法与 limit 越界 → 30079 字段级提示（400，越界拒绝不截断 ——M9 口径）；total
 * 与分页同口径（无筛选 = event_item 全量，§4.10 对账）。
 */
@Service
public class EventStreamQueryService {

    /** 事件流页大小缺省（方案 §4.8：缺省 20）。 */
    static final int DEFAULT_STREAM_LIMIT = 20;

    private static final int STREAM_LIMIT_MAX = 50;

    private final EventItemRepository repository;

    public EventStreamQueryService(EventItemRepository repository) {
        this.repository = repository;
    }

    /** 事件流分页（四维可空筛选 + 游标；total 与分页同口径）。 */
    public EventStreamView list(
            String type,
            String industry,
            String importance,
            String direction,
            Long beforeId,
            Integer limit) {
        EventItemRepository.EventStreamFilter filter =
                new EventItemRepository.EventStreamFilter(
                        resolveType(type),
                        resolveIndustry(industry),
                        resolveImportance(importance),
                        resolveDirection(direction));
        int pageSize = resolveLimit(limit);
        List<EventItemRepository.EventStreamItem> page =
                repository.findStreamItems(filter, beforeId, pageSize);
        long total = repository.countStreamItems(filter);
        return new EventStreamView(
                total,
                page.stream().map(EventStreamView.EventCardView::of).toList(),
                nextBeforeId(
                        page.size(),
                        pageSize,
                        total,
                        page.isEmpty() ? null : page.get(page.size() - 1).event().getId()));
    }

    private static Long nextBeforeId(int pageSize, int limit, long total, Long lastIdOfPage) {
        return pageSize == limit && total > limit ? lastIdOfPage : null;
    }

    private static EventType resolveType(String raw) {
        if (isAbsent(raw)) {
            return null;
        }
        EventType type = EventType.fromName(normalize(raw));
        if (type == null) {
            throw filterInvalid("type", "9 类事件枚举（如 POLICY_RELEASE）", raw);
        }
        return type;
    }

    private static String resolveIndustry(String raw) {
        if (isAbsent(raw)) {
            return null;
        }
        String industry = raw.trim();
        if (!IndustryCategory.isSwIndustry(industry)) {
            throw filterInvalid("industry", "申万一级行业枚举（31 选 1）", raw);
        }
        return industry;
    }

    private static Importance resolveImportance(String raw) {
        if (isAbsent(raw)) {
            return null;
        }
        Importance importance = Importance.fromName(normalize(raw));
        if (importance == null) {
            throw filterInvalid("importance", "HIGH / MEDIUM / LOW", raw);
        }
        return importance;
    }

    private static Direction resolveDirection(String raw) {
        if (isAbsent(raw)) {
            return null;
        }
        Direction direction = Direction.fromName(normalize(raw));
        if (direction == null) {
            throw filterInvalid("direction", "BULLISH / BEARISH / NEUTRAL", raw);
        }
        return direction;
    }

    private static int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_STREAM_LIMIT;
        }
        if (limit < 1 || limit > STREAM_LIMIT_MAX) {
            throw new BusinessException(
                    ErrorCode.EVENT_FILTER_INVALID,
                    "limit: 须在 1~" + STREAM_LIMIT_MAX + "（越界拒绝不截断），当前值 " + limit);
        }
        return limit;
    }

    private static boolean isAbsent(String raw) {
        return raw == null || raw.isBlank();
    }

    /** 枚举线格式归一（trim + 大写，HeatWindow.fromName 同款惯例）。 */
    private static String normalize(String raw) {
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private static BusinessException filterInvalid(String field, String expectation, String raw) {
        return new BusinessException(
                ErrorCode.EVENT_FILTER_INVALID, field + ": 须为 " + expectation + "，当前值 " + raw);
    }
}
