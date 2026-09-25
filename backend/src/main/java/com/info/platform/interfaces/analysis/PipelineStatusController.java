package com.info.platform.interfaces.analysis;

import com.info.platform.application.analysis.PipelineStatusService;
import com.info.platform.application.analysis.PipelineStatusView;
import com.info.platform.interfaces.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管道状态接口（M15 T121 基础版，方案 §4.8）：{@code GET /api/v1/pipeline/status}（Bearer JWT）。
 *
 * <p>护栏与对账面数据源——降级横幅/成本报表/验收断言三处同源（T125 扩展 level/预算面后仍走本端点）。 基础版字段：当日三态计数（l0 PASS/NOISE/NEAR_DUP、l1
 * DONE/PENDING/FAILED）+ 最近批窗口 + 当日 L1 成本（scene=5 微元）。
 */
@RestController
@RequestMapping("/api/v1/pipeline")
public class PipelineStatusController {

    private final PipelineStatusService statusService;

    public PipelineStatusController(PipelineStatusService statusService) {
        this.statusService = statusService;
    }

    /** 管道状态（当日计数 / 最近批窗口 / 当日成本）。 */
    @GetMapping("/status")
    public Result<PipelineStatusView> status() {
        return Result.ok(statusService.status());
    }
}
