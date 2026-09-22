package com.info.platform.interfaces.ai;

import com.info.platform.application.ai.LlmConfigFacade;
import com.info.platform.application.ai.LlmConfigFacade.LlmApiKeyWrite;
import com.info.platform.application.ai.LlmConfigFacade.LlmConnectivityResult;
import com.info.platform.application.ai.LlmConfigFacade.LlmGlobalUpdate;
import com.info.platform.application.ai.LlmConfigFacade.LlmProviderUpdate;
import com.info.platform.application.ai.LlmConfigView;
import com.info.platform.interfaces.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LLM 模型配置接口（T35，方案 §4.4.1 llm-config 组，REQ-20260922-02 故事 2）。
 *
 * <p>受 JWT 保护（{@code /api/v1/llm-config*} 不在 {@code JwtAuthFilter} 白名单）。 安全红线（ADR-0018）：GET 只回 key
 * 脱敏态（status/source/last4），任何响应不含明文；key 录入只写不读。 写接口幂等（PUT/PATCH 同 body 重复提交结果一致），body 可带 {@code
 * expectedUpdatedAt} 防并发误覆盖（不符 30065/409）。
 *
 * <h2>端点</h2>
 *
 * <ul>
 *   <li>{@code GET /api/v1/llm-config} —— 全量视图（全局 + provider 列表 + 逐字段 effectiveMode）
 *   <li>{@code PATCH /api/v1/llm-config/global} —— 全局参数部分字段（LIVE 保存即生效）
 *   <li>{@code PUT /api/v1/llm-config/providers/{name}} —— 单 provider 编辑（baseUrl 重启后生效）
 *   <li>{@code PUT /api/v1/llm-config/providers/{name}/api-key} —— API key 写入（加密落库，30064 降级 503）
 *   <li>{@code POST /api/v1/llm-config/providers/{name}/connectivity-test} —— 连通性测试（测试已执行即 200）
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/llm-config")
public class LlmConfigController {

    private final LlmConfigFacade facade;

    public LlmConfigController(LlmConfigFacade facade) {
        this.facade = facade;
    }

    /** 全量视图（key 脱敏；{@code apiKeyWriteEnabled=false} 表示 CONFIG_SECRET 未配置，页面降级只读）。 */
    @GetMapping
    public Result<LlmConfigView> view() {
        return Result.ok(facade.view());
    }

    /** 更新全局参数（部分字段合并；返回更新后的全局视图，含新 updatedAt 供下次防呆比对）。 */
    @PatchMapping("/global")
    public Result<LlmConfigView.GlobalConfigView> updateGlobal(
            @RequestBody LlmGlobalUpdate update) {
        return Result.ok(facade.updateGlobal(update));
    }

    /** 更新单 provider（{@code isDefault=true} 服务端互斥置反保证全局恰一）。 */
    @PutMapping("/providers/{name}")
    public Result<LlmConfigView.ProviderConfigView> updateProvider(
            @PathVariable String name, @RequestBody LlmProviderUpdate update) {
        return Result.ok(facade.updateProvider(name, update));
    }

    /** 写入 API key（AES-256-GCM 加密落库；成功只回脱敏态，永不回明文）。 */
    @PutMapping("/providers/{name}/api-key")
    public Result<LlmConfigView.ProviderConfigView> writeApiKey(
            @PathVariable String name, @RequestBody LlmApiKeyWrite update) {
        return Result.ok(facade.writeApiKey(name, update));
    }

    /** 连通性测试：返回 {@code {ok, latencyMillis, model, error}}；ok=false 亦 200（测试已执行）。 */
    @PostMapping("/providers/{name}/connectivity-test")
    public Result<LlmConnectivityResult> connectivityTest(@PathVariable String name) {
        return Result.ok(facade.connectivityTest(name));
    }
}
