package com.info.platform.application.ai;

import com.info.platform.domain.common.BusinessException;
import java.util.Map;

/**
 * LLM 配置管理端口（T35，方案 §4.4.1 llm-config 组）。
 *
 * <p>端口在应用层、实现在基础设施层（{@code infrastructure.ai.LlmConfigFacadeImpl}）：实现需要 ConfigCenter 快照 / 密文工具 /
 * provider adapter / 虚拟线程执行器等基础设施件， 而应用层直接依赖基础设施会与既有 infrastructure→application
 * 依赖成环（LayeredArchitectureTest 层切片无环约束），故走端口倒置（同 {@code LlmCostBudget} 端口先例）。
 *
 * <p>语义：读返回脱敏全量视图；写为「当前文档合并部分字段 → 校验 → 整体落库」（expectedUpdatedAt 并发防呆 30065）； {@code isDefault=true}
 * 由实现保证全局恰一（互斥置反）；API key 只写不读（加密落库 30064 降级，ADR-0018）。
 */
public interface LlmConfigFacade {

    /** 全量视图（全局 + 4 provider，key 脱敏，逐字段 effectiveMode）。 */
    LlmConfigView view();

    /**
     * 更新全局参数（PATCH 语义：仅非 null 字段合并进当前文档，LIVE 级保存即生效）。
     *
     * @throws BusinessException 2001 校验失败；30065 并发冲突
     */
    LlmConfigView.GlobalConfigView updateGlobal(LlmGlobalUpdate update);

    /**
     * 更新单 provider（PUT 语义：仅非 null 字段合并；{@code isDefault=true} 时互斥置反其他 provider）。
     *
     * @throws BusinessException 30060 provider 不存在；2001 校验失败；30065 并发冲突
     */
    LlmConfigView.ProviderConfigView updateProvider(String name, LlmProviderUpdate update);

    /**
     * 写入 provider API key（AES-256-GCM 加密落库，只写不读，成功返回脱敏态）。
     *
     * @throws BusinessException 30060 provider 不存在；30064 CONFIG_SECRET 未配置（503）；30065 并发冲突
     */
    LlmConfigView.ProviderConfigView writeApiKey(String name, LlmApiKeyWrite update);

    /**
     * 连通性测试：用当前运行时配置发一次最小 chat（绕缓存、不占用户预算，scene_key=test 落留痕供报表可见）。
     *
     * @return 测试已执行即 200（ok=false 时 error 带原因摘要）
     * @throws BusinessException 30060 provider 不存在
     */
    LlmConnectivityResult connectivityTest(String name);

    /** 全局参数部分更新（null 字段 = 不修改）。expectedUpdatedAt 为 ISO-8601 文本（UTC）。 */
    record LlmGlobalUpdate(
            Long timeoutSeconds,
            Integer retry,
            Long dailyTokenBudgetPerUser,
            Double budgetWarnRatio,
            Long cacheDefaultTtlSeconds,
            Map<String, Long> cacheTtlSeconds,
            String expectedUpdatedAt) {}

    /** provider 部分更新（null 字段 = 不修改）。expectedUpdatedAt 为 ISO-8601 文本（UTC）。 */
    record LlmProviderUpdate(
            String model,
            Boolean enabled,
            Boolean isDefault,
            String fallback,
            Double inputPricePerMillion,
            Double outputPricePerMillion,
            String baseUrl,
            String expectedUpdatedAt) {}

    /** API key 写入请求（明文仅存在于请求与内存瞬时生命周期，不落日志）。 */
    record LlmApiKeyWrite(String apiKey, String expectedUpdatedAt) {}

    /** 连通性测试结果（ok=false 时 latencyMillis/model 为 null）。 */
    record LlmConnectivityResult(boolean ok, Long latencyMillis, String model, String error) {}
}
