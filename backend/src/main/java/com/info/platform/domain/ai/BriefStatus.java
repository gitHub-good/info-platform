package com.info.platform.domain.ai;

/**
 * AI 简报状态（{@code ai_brief.status} 持久化为 {@code TINYINT}，对齐技术方案 §4.1.4 / §4.2 DDL）。
 *
 * <p>领域层纯净枚举（仅 JDK）。四态： {@link #PENDING} 处理中（任务已受理，{@code status=0}，含 CAS 领取前与生成中）； {@link #DONE}
 * 完成（{@code status=1}，content 已写，幻觉校验全通过）； {@link #FAILED} 失败（{@code status=2}，LLM 调用失败 / 解析失败 /
 * 超时兜底）； {@link #NEED_VERIFY} 待核实（{@code status=3}，幻觉校验有数值不符或源暂不可用，降级展示不作已确认结论）。
 *
 * <p>{@link #isTerminal()} 区分终态（DONE/FAILED/NEED_VERIFY，不再变更）与进行中（PENDING）； {@link #isCompleted}
 * 区分「已有可展示 content」（DONE/NEED_VERIFY，前端可渲染简报）与「无可展示内容」（PENDING/FAILED）。
 */
public enum BriefStatus {
    /** 处理中（status=0）：任务已受理，CAS 领取前 / 生成中 / 卡死兜底前。 */
    PENDING(0),
    /** 完成（status=1）：content 已写，幻觉校验全通过。 */
    DONE(1),
    /** 失败（status=2）：LLM 调用失败 / 解析失败 / 超时 30min 自动置失败。 */
    FAILED(2),
    /** 待核实（status=3）：幻觉校验有数值不符或源暂不可用，降级展示不作已确认结论。 */
    NEED_VERIFY(3);

    private final int code;

    BriefStatus(int code) {
        this.code = code;
    }

    /** 持久化码（对齐 DDL {@code status TINYINT}）。 */
    public int code() {
        return code;
    }

    /** 是否终态（不再变更）：DONE/FAILED/NEED_VERIFY。PENDING 为进行中。 */
    public boolean isTerminal() {
        return this != PENDING;
    }

    /** 是否已有可展示 content（前端可渲染简报）：DONE/NEED_VERIFY。PENDING/FAILED 无可展示内容。 */
    public boolean isCompleted() {
        return this == DONE || this == NEED_VERIFY;
    }

    /** 从持久化码反查枚举（未知码抛 {@code IllegalArgumentException}）。 */
    public static BriefStatus fromCode(int code) {
        for (BriefStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知 ai_brief.status: " + code);
    }
}
