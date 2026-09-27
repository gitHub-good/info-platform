package com.info.platform.application.ai;

/**
 * LLM 场景失败例留痕端口（应用层，M22 T190 随批，M21 §11-11）：调用本身成功（网关已落 SUCCESS 行）而<b>消费侧校验链失败</b>时，补一行 FAILED
 * 留痕（userId=0 系统调用、cost=0 不入成本口径）附失败摘要——解析类缺陷可离线归因，全量原文存储成本红线不破。
 *
 * <p>依赖倒置（仓储同款）：接口落应用层，实现由基础设施 {@code LlmSceneFailureRecorderImpl} 承担（封装 {@code
 * LlmCallLogger}），避免应用层直依赖基础设施形成层间环（LayeredArchitectureTest 守护）。
 */
public interface LlmSceneFailureRecorder {

    /**
     * 落一行场景失败留痕（旁路语义：实现内部容错，失败不阻断调用方主链）。
     *
     * @param sceneKey 场景键（对齐 LlmRequest.briefTypeKey，如 "10" 深析）
     * @param errorMessage 失败摘要（含阶段与响应片段 head 截断——由调用方组串）
     */
    void record(String sceneKey, String errorMessage);
}
