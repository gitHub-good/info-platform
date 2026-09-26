package com.info.platform.interfaces.analysis;

import com.info.platform.application.analysis.NorthStarService;
import com.info.platform.application.analysis.NorthStarView;
import com.info.platform.domain.common.UserContext;
import com.info.platform.interfaces.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 北极星指标接口（M18 T158，REQ 拍板四）：{@code GET /api/v1/north-star}（Bearer JWT）。
 *
 * <p>六指标一端点只读聚合（ns-v1 口径版本化随响应下发），供大盘 2.0 北极星区块消费；采纳率按当前用户 adopt-v1 统计（单人平台运营者即当前用户）。前端 30
 * 秒轮询沿大盘机制；窗口首测（≥3 工作日 P50 / 7 天序列 / ≥30 卡采纳样本） 走 V2.0 收口报告文档态快照，不走本端点（ADR-0057 现算 + 留档裁量）。
 */
@RestController
@RequestMapping("/api/v1/north-star")
public class NorthStarController {

    private static final Logger log = LoggerFactory.getLogger(NorthStarController.class);

    private final NorthStarService northStarService;

    public NorthStarController(NorthStarService northStarService) {
        this.northStarService = northStarService;
    }

    /** 北极星六指标 + 7 天入库趋势（实时态驾驶舱仪表）。 */
    @GetMapping
    public Result<NorthStarView> northStar() {
        NorthStarView view = northStarService.northStar(currentUserId());
        log.debug(
                "北极星聚合: latencyStatus={} coverageStatus={} stableStatus={} intakeStatus={}"
                        + " adoptStatus={} costStatus={}",
                view.latency().status(),
                view.coverage().status(),
                view.stableSources().status(),
                view.dailyIntake().status(),
                view.adoptRate().status(),
                view.costGuard().status());
        return Result.ok(view);
    }

    private static long currentUserId() {
        UserContext.Principal principal = UserContext.get();
        return principal == null ? 0L : principal.userId();
    }
}
