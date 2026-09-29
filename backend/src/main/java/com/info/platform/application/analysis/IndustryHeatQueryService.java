package com.info.platform.application.analysis;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.analysis.GuardLevel;
import com.info.platform.domain.analysis.HeatSnapshotRepository;
import com.info.platform.domain.analysis.HeatWindow;
import com.info.platform.domain.analysis.IndustryCategory;
import com.info.platform.domain.analysis.IndustryHeatSnapshot;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 行业热度读服务（应用层，M15 T123，方案 §4.8；M29 T255 market 参数化——缺省 A_SHARE 既有语义零回归）：榜单（市场 + 窗口参数 + 各市场枚举行的降序 +
 * basis 脚注 + 护栏徽章 + 口径标注）与行业下钻（news/events 双清单、beforeId 游标、对账 total——按 {@code l1_market}
 * 分桶不混桶）。参数校验：market/window/type/行业名非法 → 30076；limit 1~50 越界拒绝不截断（M9 口径）。
 */
@Service
public class IndustryHeatQueryService {

    /** 下钻页大小缺省（方案 §4.8：缺省 20）。 */
    static final int DEFAULT_ITEMS_LIMIT = 20;

    private static final int ITEMS_LIMIT_MAX = 50;

    private final HeatSnapshotRepository repository;
    private final PipelineGuardService guardService;
    private final Clock clock;

    public IndustryHeatQueryService(
            HeatSnapshotRepository repository, PipelineGuardService guardService, Clock clock) {
        this.repository = repository;
        this.guardService = guardService;
        this.clock = clock;
    }

    /** 热度榜（market 缺省 A_SHARE；window 缺省 H24）。 */
    public IndustryHeatBoardView board(String marketParam, String windowParam) {
        Market market = resolveMarket(marketParam);
        HeatWindow window = resolveWindow(windowParam);
        List<IndustryHeatSnapshot> rows = repository.findBoard(window, market);
        GuardLevel level = guardService.currentLevel();
        return IndustryHeatBoardView.of(
                market, window, rows, new IndustryHeatBoardView.PipelineBadgeView(level.name()));
    }

    /** 行业下钻（type 缺省 news；total 与榜单 news_count/event_count 对账相等——同市场口径）。 */
    public IndustryHeatItemsView items(
            String industry,
            String marketParam,
            String windowParam,
            String type,
            Long beforeId,
            Integer limit) {
        Market market = resolveMarket(marketParam);
        if (!IndustryCategory.isBoardIndustry(market, industry)) {
            throw new BusinessException(
                    ErrorCode.PIPELINE_CONFIG_INVALID,
                    "industry: 须为该市场进榜行业枚举（"
                            + IndustryCategory.industrySystemOf(market)
                            + "），当前值 "
                            + industry);
        }
        HeatWindow window = resolveWindow(windowParam);
        String listType = resolveType(type);
        int pageSize = resolveLimit(limit);
        java.time.Instant windowEnd = clock.instant();
        String from = windowEnd.minus(window.length()).toString();
        String to = windowEnd.toString();

        if ("events".equals(listType)) {
            return eventsItems(industry, window, market, from, to, beforeId, pageSize);
        }
        return newsItems(industry, window, market, from, to, beforeId, pageSize);
    }

    private IndustryHeatItemsView newsItems(
            String industry,
            HeatWindow window,
            Market market,
            String from,
            String to,
            Long beforeId,
            int limit) {
        List<HeatSnapshotRepository.IndustryNewsItem> page =
                repository.findIndustryNewsItems(industry, from, to, beforeId, limit, market);
        long total = repository.countIndustryNewsItems(industry, from, to, market);
        return new IndustryHeatItemsView(
                industry,
                window.name(),
                "news",
                total,
                page.stream().map(IndustryHeatItemsView.ItemView::ofNews).toList(),
                nextBeforeId(
                        page.size(),
                        limit,
                        total,
                        page.isEmpty() ? null : page.get(page.size() - 1).newsId()));
    }

    private IndustryHeatItemsView eventsItems(
            String industry,
            HeatWindow window,
            Market market,
            String from,
            String to,
            Long beforeEventId,
            int limit) {
        List<HeatSnapshotRepository.IndustryEventItem> page =
                repository.findIndustryEventItems(industry, from, to, beforeEventId, limit, market);
        long total = repository.countIndustryEventItems(industry, from, to, market);
        return new IndustryHeatItemsView(
                industry,
                window.name(),
                "events",
                total,
                page.stream().map(IndustryHeatItemsView.ItemView::ofEvent).toList(),
                nextBeforeId(
                        page.size(),
                        limit,
                        total,
                        page.isEmpty() ? null : page.get(page.size() - 1).eventId()));
    }

    private static Long nextBeforeId(int pageSize, int limit, long total, Long lastIdOfPage) {
        // BUG-02（M15 验收）：空页时调用方传 null，原始 long 形参拆箱 NPE——改包装类型（空页恒返 null）
        return pageSize == limit && total > limit ? lastIdOfPage : null;
    }

    /** 市场参数解析（缺省 A_SHARE；非法 30076——方案 §五 统一约定）。 */
    static Market resolveMarket(String marketParam) {
        if (marketParam == null || marketParam.isBlank()) {
            return Market.A_SHARE;
        }
        try {
            return Market.fromName(marketParam.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    ErrorCode.PIPELINE_CONFIG_INVALID,
                    "market: 须为 A_SHARE / HK / US，当前值 " + marketParam);
        }
    }

    private static HeatWindow resolveWindow(String windowParam) {
        if (windowParam == null || windowParam.isBlank()) {
            return HeatWindow.H24;
        }
        HeatWindow window = HeatWindow.fromName(windowParam);
        if (window == null) {
            throw new BusinessException(
                    ErrorCode.PIPELINE_CONFIG_INVALID, "window: 须为 H24 / D7，当前值 " + windowParam);
        }
        return window;
    }

    private static String resolveType(String type) {
        if (type == null || type.isBlank()) {
            return "news";
        }
        String normalized = type.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"news".equals(normalized) && !"events".equals(normalized)) {
            throw new BusinessException(
                    ErrorCode.PIPELINE_CONFIG_INVALID, "type: 须为 news / events，当前值 " + type);
        }
        return normalized;
    }

    private static int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_ITEMS_LIMIT;
        }
        if (limit < 1 || limit > ITEMS_LIMIT_MAX) {
            throw new BusinessException(
                    ErrorCode.PIPELINE_CONFIG_INVALID,
                    "limit: 须在 1~" + ITEMS_LIMIT_MAX + "（越界拒绝不截断），当前值 " + limit);
        }
        return limit;
    }
}
