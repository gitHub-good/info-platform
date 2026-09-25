package com.info.platform.interfaces.feed;

import com.info.platform.application.feed.FeedDashboardService;
import com.info.platform.application.feed.FeedDashboardView;
import com.info.platform.interfaces.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 抓取大盘接口（M14 T114，REQ-20260925-11 故事 2）：{@code GET /api/v1/feed-dashboard} 三区块一端点（Bearer JWT）。
 *
 * <p>只读聚合（global 全局统计 / sources 源维度表 / failures 近期失败列表），今日计数与源管理页同表对账； 感知延迟「仅增量轮」口径见 {@code
 * FeedDashboardView.LATENCY_BASIS}（ADR-0045）。前端 30 秒轮询（document.hidden 暂停），无需服务端游标。
 */
@RestController
@RequestMapping("/api/v1/feed-dashboard")
public class FeedDashboardController {

    private static final Logger log = LoggerFactory.getLogger(FeedDashboardController.class);

    private final FeedDashboardService dashboardService;

    public FeedDashboardController(FeedDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** 三区块一端点（只读；数据新鲜度由前端轮询节奏决定，蓝图 30s 建议值）。 */
    @GetMapping
    public Result<FeedDashboardView> dashboard() {
        FeedDashboardView view = dashboardService.dashboard();
        log.debug(
                "大盘聚合: sources={} failures={} todayNew={}",
                view.sources().size(),
                view.failures().size(),
                view.global().todayNewCount());
        return Result.ok(view);
    }
}
