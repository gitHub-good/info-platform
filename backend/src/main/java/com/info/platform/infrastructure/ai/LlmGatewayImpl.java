package com.info.platform.infrastructure.ai;

import com.info.platform.domain.ai.LlmCallLog;
import com.info.platform.domain.ai.LlmException;
import com.info.platform.domain.ai.LlmGateway;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.domain.ai.LlmUsage;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.UserContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * LLM 网关实现（{@link LlmGateway} 端口，ADR-0004 + ADR-0008 + 技术方案 §4.4 + ADR-0010）。
 *
 * <p>编排顺序：缓存命中直返（0 成本）→ 成本上限校验 → fallback 链调用（每调用裹 Future 超时）→ 用量入账 + 写缓存。 T30
 * 起每次 chat 落一行调用留痕（{@link LlmCallLog}，经 {@link LlmCallLogger}——缓存命中/成功/失败/预算拒绝四态， 供成本报表
 * {@code GET /api/v1/llm-cost-report} 聚合；留痕失败不阻断调用）。
 *
 * <p><b>Fallback 链</b>（对齐 §4.4 + ADR-0008）：从 {@code default: true} provider 起，按 {@code fallback}
 * 字段串联（带环检测）；逐个调用 enabled 且已装配的 adapter，任一成功即返。 失败类型（超时 / 限频 429 {@code
 * RestClientResponseException} / 5xx / 连接错误 / 解析异常）均视为本 provider 失败，切下一个； 全部失败抛 {@link
 * LlmException}（T21 据此置 {@code ai_brief.status=2} + 告警）。
 *
 * <p><b>超时</b>（"自带"，对齐 {@code llm.timeout-seconds} + ADR-0010 思路）： 虚拟线程执行器承载阻塞式 {@code
 * adapter.chat}，{@code Future.get(timeout)} 兜底，超时 {@code cancel(true)} 中断工作线程后切 fallback。 不在
 * adapter 层设 HTTP {@code requestFactory} 超时——避免与 {@code MockRestServiceServer} 的 mock
 * requestFactory 冲突（测试无 key 不依赖真实 API）。
 *
 * <p>成本与缓存是基础设施层职责，不出现在领域端口（端口只定义 {@code chat}）。 userId 取自 {@link UserContext}（M2
 * 同步调用，请求入口已写入）；{@code <=0} 时成本守卫跳过（系统调用场景）。
 */
@Component
public class LlmGatewayImpl implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayImpl.class);

    private final List<LlmProviderAdapter> adapters;
    private final LlmConfig config;
    private final LlmCostGuard costGuard;
    private final LlmCache cache;
    private final LlmCallLogger callLog;
    private final ExecutorService executor;
    private final Duration timeout;

    public LlmGatewayImpl(
            List<LlmProviderAdapter> adapters,
            LlmConfig config,
            LlmCostGuard costGuard,
            LlmCache cache,
            LlmCallLogger callLog,
            @Qualifier("llmExecutor") ExecutorService executor) {
        this.adapters = adapters == null ? List.of() : adapters;
        this.config = config;
        this.costGuard = costGuard;
        this.cache = cache;
        this.callLog = callLog;
        this.executor = executor;
        this.timeout = config.timeout();
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        long startNanos = System.nanoTime();
        LlmResponse cached = cache.getIfPresent(request);
        if (cached != null) {
            log.debug("LLM 缓存命中 briefType={}，0 调用", request.briefTypeKey());
            LlmCallLog hit = LlmCallLog.begin(currentUserId(), request.briefTypeKey());
            hit.markCacheHit(
                    cached.provider() == null ? null : cached.provider().configName(),
                    cached.model(),
                    elapsedMillis(startNanos));
            callLog.record(hit);
            return cached;
        }

        long userId = currentUserId();
        try {
            costGuard.checkBudget(userId);
        } catch (BusinessException e) {
            LlmCallLog rejected = LlmCallLog.begin(userId, request.briefTypeKey());
            rejected.markRejected(String.valueOf(e.getMessage()));
            callLog.record(rejected);
            throw e;
        }

        List<String> chain = config.fallbackChain();
        if (chain.isEmpty()) {
            LlmCallLog noProvider = LlmCallLog.begin(userId, request.briefTypeKey());
            noProvider.markFailed("未配置 default LLM provider", elapsedMillis(startNanos));
            callLog.record(noProvider);
            throw new LlmException("未配置 default LLM provider", List.of(), null);
        }
        Map<String, LlmProviderAdapter> byName = new HashMap<>();
        for (LlmProviderAdapter a : adapters) {
            byName.put(a.name(), a);
        }

        List<String> attempted = new ArrayList<>();
        Throwable lastError = null;
        for (String name : chain) {
            LlmProviderAdapter adapter = byName.get(name);
            LlmConfig.Provider providerCfg = config.providerByName(name);
            if (adapter == null) {
                log.warn("LLM provider '{}' 无 adapter（未实现？），跳过", name);
                attempted.add(name + "(no-adapter)");
                continue;
            }
            if (providerCfg != null && !providerCfg.isEnabled()) {
                log.info("LLM provider '{}' 已禁用，跳过", name);
                attempted.add(name + "(disabled)");
                continue;
            }
            try {
                LlmResponse resp = callWithTimeout(adapter, request);
                costGuard.recordUsage(userId, resp.usage());
                cache.put(request, resp);
                LlmCallLog ok = LlmCallLog.begin(userId, request.briefTypeKey());
                ok.markSuccess(
                        name,
                        resp.model(),
                        resp.usage(),
                        costMicros(name, resp.usage()),
                        elapsedMillis(startNanos));
                callLog.record(ok);
                return resp;
            } catch (TimeoutException te) {
                log.warn("LLM provider '{}' 超时（{}），切 fallback", name, timeout);
                attempted.add(name + "(timeout)");
                lastError = te;
            } catch (Exception e) {
                log.warn(
                        "LLM provider '{}' 调用失败，切 fallback：{}",
                        name,
                        String.valueOf(e.getMessage()));
                attempted.add(name);
                lastError = e;
            }
        }
        LlmCallLog failed = LlmCallLog.begin(userId, request.briefTypeKey());
        failed.markFailed("所有 LLM provider 均失败：" + attempted, elapsedMillis(startNanos));
        callLog.record(failed);
        throw new LlmException("所有 LLM provider 均失败：" + attempted, attempted, lastError);
    }

    /** 按 provider 配置单价估算成本（微元）；未配置 provider 或单价默认 0（不估算）。 */
    private long costMicros(String providerName, LlmUsage usage) {
        LlmConfig.Provider providerCfg = config.providerByName(providerName);
        if (providerCfg == null || usage == null) {
            return 0L;
        }
        return LlmCallLog.estimateCostMicros(
                usage.promptTokens(),
                usage.completionTokens(),
                providerCfg.getInputPricePerMillion(),
                providerCfg.getOutputPricePerMillion());
    }

    private static long elapsedMillis(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private LlmResponse callWithTimeout(LlmProviderAdapter adapter, LlmRequest request)
            throws Exception {
        Future<LlmResponse> future = executor.submit(() -> adapter.chat(request));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw te;
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof Exception e) {
                throw e;
            }
            throw new RuntimeException(cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("LLM 调用被中断", ie);
        }
    }

    private static long currentUserId() {
        UserContext.Principal p = UserContext.get();
        return p == null ? 0L : p.userId();
    }
}
