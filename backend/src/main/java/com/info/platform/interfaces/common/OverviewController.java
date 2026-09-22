package com.info.platform.interfaces.common;

import com.info.platform.application.common.OverviewService;
import com.info.platform.application.common.OverviewService.OverviewView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 概览仪表盘接口（T42，方案 §4.4.4 overview 组，REQ 故事 5）：登录后默认落地页的五卡片聚合数据。
 *
 * <p>受 JWT 保护（不在 {@code JwtAuthFilter} 白名单）。取数口径与卡片级容错见 {@link OverviewService}； 单卡取数失败该卡返回 error
 * 字段，其余卡片正常（前端单卡错误态 + 独立重试）。
 */
@RestController
@RequestMapping("/api/v1/overview")
public class OverviewController {

    private final OverviewService overviewService;

    public OverviewController(OverviewService overviewService) {
        this.overviewService = overviewService;
    }

    /** 一屏五卡片聚合（成本水位/今日异动/政策 24h/任务健康/数据源健康）。 */
    @GetMapping
    public Result<OverviewView> overview() {
        return Result.ok(overviewService.view());
    }
}
