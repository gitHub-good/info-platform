package com.info.platform.application.analysis;

import com.info.platform.domain.aggregation.Market;
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
 *
 * <p>M29 T254 补交付（P1-01 修复，方案 §5.4）：第五维 market 过滤——事件关联标的（subjects code 前缀）含该市场标的；缺省与显式
 * A_SHARE 等价全量（A 股视角零回归），仅 HK/US 有过滤语义；industry 校验随 market 口径切换（A 股申万 31 / 港 31 / 美 40——跨市场重名由
 * market 消歧不混桶）；页码模式响应附 {@code industryFilterGroups} 三市场行业分组。
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

    /** 事件流分页（五维可空筛选 + 游标；total 与分页同口径）。 */
    public EventStreamView list(
            String type,
            String industry,
            String importance,
            String direction,
            String marketParam,
            Long beforeId,
            Integer limit) {
        EventItemRepository.EventStreamFilter filter =
                buildFilter(type, industry, importance, direction, marketParam);
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

    /**
     * 事件流页码分页（M25 T220，V3.0 REQ-20260928-21 拍板四）：page/size offset 语义（M9 PageQuery 模式）。
     *
     * <p>五维筛选解析与游标模式同一套（非法枚举 → 30079 字段级）；total 沿 {@code countStreamItems}——两模式计数同源同口径；
     * 越界页返回空列表（如实回显 page/size，M9 §3.4）；响应恒附 {@code industryFilterGroups}（§5.4 契约增量）。
     */
    public EventStreamPageView listPaged(
            String type,
            String industry,
            String importance,
            String direction,
            String marketParam,
            int page,
            int size) {
        EventItemRepository.EventStreamFilter filter =
                buildFilter(type, industry, importance, direction, marketParam);
        List<EventItemRepository.EventStreamItem> rows =
                repository.findStreamItemsPaged(filter, page, size);
        long total = repository.countStreamItems(filter);
        return new EventStreamPageView(
                total,
                rows.stream().map(EventStreamView.EventCardView::of).toList(),
                page,
                size,
                EventStreamPageView.INDUSTRY_FILTER_GROUPS);
    }

    /** 五维筛选组装（market 先解析——industry 口径随 market）。 */
    private static EventItemRepository.EventStreamFilter buildFilter(
            String type, String industry, String importance, String direction, String marketParam) {
        Market market = resolveMarket(marketParam);
        return new EventItemRepository.EventStreamFilter(
                resolveType(type),
                resolveIndustry(industry, market),
                resolveImportance(importance),
                resolveDirection(direction),
                streamMarketOf(market));
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

    /**
     * 市场参数解析（M29 §5.4/§五 统一约定）：缺省/空白 → A_SHARE；显式 A_SHARE = 缺省全量语义（A 股零回归）；非法值（含
     * INDEX/SECTOR 非事件流口径）→ 30079 字段级。
     */
    static Market resolveMarket(String raw) {
        if (isAbsent(raw)) {
            return Market.A_SHARE;
        }
        Market market = parseStockMarket(raw);
        if (market == null) {
            throw filterInvalid("market", "A_SHARE / HK / US", raw);
        }
        return market;
    }

    /** 三市场枚举解析（null = 非个股三市场口径，含 INDEX/SECTOR 与未知值——事件流不接受）。 */
    private static Market parseStockMarket(String raw) {
        Market market;
        try {
            market = Market.fromName(normalize(raw));
        } catch (IllegalArgumentException e) {
            return null;
        }
        return market == Market.A_SHARE || market == Market.HK || market == Market.US
                ? market
                : null;
    }

    /** 过滤维市场（A_SHARE = 缺省全量语义归 null 不过滤；HK/US 保留传入仓储）。 */
    private static Market streamMarketOf(Market market) {
        return market == Market.A_SHARE ? null : market;
    }

    /** 行业筛选解析（口径随 market：A 股申万 31 / 港 31 直采 / 美 40 归并——isBoardIndustry 白名单）。 */
    private static String resolveIndustry(String raw, Market market) {
        if (isAbsent(raw)) {
            return null;
        }
        String industry = raw.trim();
        if (!IndustryCategory.isBoardIndustry(market, industry)) {
            throw filterInvalid("industry", industryExpectation(market), raw);
        }
        return industry;
    }

    /** 行业枚举口径提示（A 股文案沿既有——零回归；港美沿 industrySystemOf 口径标注）。 */
    private static String industryExpectation(Market market) {
        if (market == Market.HK) {
            return "港股行业枚举（31 直采，来源 F10 BELONG_INDUSTRY）";
        }
        if (market == Market.US) {
            return "美股行业枚举（归并 ≤40，来源 F10 BELONG_INDUSTRY）";
        }
        return "申万一级行业枚举（31 选 1）";
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
