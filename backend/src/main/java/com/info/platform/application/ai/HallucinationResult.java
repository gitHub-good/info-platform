package com.info.platform.application.ai;

import java.util.List;

/**
 * 幻觉校验汇总结果（值对象，T21）。
 *
 * @param needVerify 是否有待核实事实——true 则 AIBriefService 置 {@code ai_brief.status=3 待核实}，false 则置 {@code
 *     status=1 完成}
 * @param checks 每条事实的校验结果（含理由，供日志/前端展示）
 */
public record HallucinationResult(boolean needVerify, List<FactCheck> checks) {

    public HallucinationResult {
        checks = checks == null ? List.of() : List.copyOf(checks);
    }
}
