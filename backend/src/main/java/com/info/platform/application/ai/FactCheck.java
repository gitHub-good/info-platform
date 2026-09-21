package com.info.platform.application.ai;

import com.info.platform.domain.ai.BriefFact;

/**
 * 单条事实的幻觉校验结果（值对象，T21）。
 *
 * @param fact 被校验的事实（含模型给出的 metric/value）
 * @param status 校验状态
 * @param reason 状态说明（如「数值不符：模型 15.0 vs 真值 14.0，相对误差 7.1%」），前端展示/日志用
 */
public record FactCheck(BriefFact fact, FactCheckStatus status, String reason) {

    public boolean needVerify() {
        return status == FactCheckStatus.NEED_VERIFY;
    }
}
