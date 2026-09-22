package com.info.platform.domain.ai;

/**
 * LLM 调用结果状态（llm_call_log.status 持久化为枚举名字文本 SUCCESS/FAILED/REJECTED，T30）。
 *
 * <p>领域层纯净枚举（仅 JDK），与持久化层 {@code TEXT} 互转，对齐 {@code JobExecutionStatus} 模式。 状态在 {@code
 * LlmGatewayImpl#chat} 一次调用内定型（追加型流水，无中间态、不翻转）：
 *
 * <ul>
 *   <li>{@link #SUCCESS}：命中本地缓存直返（cache_hit=1，0 token 0 成本）或任一 provider 外呼成功。
 *   <li>{@link #FAILED}：fallback 链全部失败（超时/限频/5xx/解析异常）或未配置 default provider。
 *   <li>{@link #REJECTED}：单用户日 token 预算耗尽，外呼前即被 {@code LlmCostGuard} 拦截（30030，429 语义）。
 * </ul>
 */
public enum LlmCallStatus {
    /** 调用成功（含本地缓存命中，cache_hit 列区分）。 */
    SUCCESS,
    /** 调用失败（fallback 链耗尽 / 无可调 provider）。 */
    FAILED,
    /** 预算拦截（成本上限触发，未发起外呼）。 */
    REJECTED;

    /** 持久化用枚举名（与 DDL status TEXT 一致，跨语言读取不歧义）。 */
    public String persistentName() {
        return name();
    }

    /** 从持久化文本反查枚举；未知值兜底抛非法参数（防御脏数据）。 */
    public static LlmCallStatus fromName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("llmCallStatus 为空");
        }
        for (LlmCallStatus s : values()) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知 llmCallStatus: " + name);
    }
}
