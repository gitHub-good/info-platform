package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.LlmException;
import com.info.platform.domain.ai.LlmProvider;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.domain.ai.LlmUsage;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.UserContext;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * LlmGatewayImpl 单测（T19）：default/fallback 编排 + 成本上限 + 缓存 + 超时（ADR-0004/0008 + §4.4）。
 *
 * <p>adapter 全 mock（Mockito，不依赖真实 API key）：覆盖 default 成功（用量入账+写缓存）/ default 失败切 fallback / 全失败抛
 * {@link LlmException} / 成本上限触发拒绝（{@link ErrorCode#AI_QUOTA_EXHAUSTED}，不调 adapter）/ 缓存命中二次不调 /
 * 调用超时切 fallback。
 */
class LlmGatewayImplTest {

    private static final long USER_ID = 1001L;

    private LlmProviderAdapter deepseek;
    private LlmProviderAdapter glm;
    private LlmCostGuard costGuard;
    private LlmCache cache;
    private ExecutorService executor;
    private LlmConfig config;

    private static final LlmResponse DS_RESP =
            new LlmResponse(
                    "ds brief", new LlmUsage(100, 50), LlmProvider.DEEPSEEK, "deepseek-flash");
    private static final LlmResponse GLM_RESP =
            new LlmResponse(
                    "glm brief", new LlmUsage(80, 40), LlmProvider.GLM, "glm-4-flash-250414");

    @BeforeEach
    void setUp() {
        deepseek = mock(LlmProviderAdapter.class);
        glm = mock(LlmProviderAdapter.class);
        when(deepseek.name()).thenReturn("deepseek");
        when(glm.name()).thenReturn("glm");
        config =
                LlmConfigTest.configWith(
                        LlmConfigTest.deepseekProvider(), LlmConfigTest.glmProvider());
        costGuard = new LlmCostGuard(config.getDailyTokenBudgetPerUser());
        cache =
                new LlmCache(
                        bt -> java.time.Duration.ofSeconds(60),
                        java.time.Duration.ofSeconds(60),
                        100);
        ThreadFactory factory = Thread.ofVirtual().factory();
        executor = Executors.newThreadPerTaskExecutor(factory);
        UserContext.set(new UserContext.Principal(USER_ID, "tester"));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        UserContext.clear();
    }

    private LlmRequest request() {
        return LlmRequest.json(
                List.of(new ChatMessage("system", "sys"), new ChatMessage("user", "ctx")), "1");
    }

    private LlmGatewayImpl gateway() {
        return new LlmGatewayImpl(List.of(deepseek, glm), config, costGuard, cache, executor);
    }

    @Test
    void chat_defaultProviderSuccess_returnsResponseAndRecordsUsage() {
        when(deepseek.chat(any())).thenReturn(DS_RESP);

        LlmResponse result = gateway().chat(request());

        assertThat(result).isEqualTo(DS_RESP);
        verify(glm, never()).chat(any());
        assertThat(costGuard.currentUsage(USER_ID)).isEqualTo(150L);
    }

    @Test
    void chat_cacheHit_secondCallDoesNotInvokeAdapter() {
        when(deepseek.chat(any())).thenReturn(DS_RESP);
        LlmGatewayImpl gw = gateway();
        LlmRequest req = request();

        LlmResponse first = gw.chat(req);
        LlmResponse second = gw.chat(req); // 缓存命中

        assertThat(first).isEqualTo(DS_RESP);
        assertThat(second).isEqualTo(DS_RESP);
        verify(deepseek, times(1)).chat(any());
    }

    @Test
    void chat_defaultFails_fallbackSucceeds() {
        when(deepseek.chat(any())).thenThrow(new RuntimeException("deepseek down"));
        when(glm.chat(any())).thenReturn(GLM_RESP);

        LlmResponse result = gateway().chat(request());

        assertThat(result).isEqualTo(GLM_RESP);
        verify(deepseek).chat(any());
        verify(glm).chat(any());
        assertThat(costGuard.currentUsage(USER_ID)).isEqualTo(120L); // glm usage 入账
    }

    @Test
    void chat_allFail_throwsLlmExceptionWithAttempted() {
        when(deepseek.chat(any())).thenThrow(new RuntimeException("ds err"));
        when(glm.chat(any())).thenThrow(new RuntimeException("glm err"));

        assertThatThrownBy(() -> gateway().chat(request()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("deepseek")
                .hasMessageContaining("glm");
        verify(deepseek).chat(any());
        verify(glm).chat(any());
    }

    @Test
    void chat_costBudgetExhausted_throwsBeforeCallingAdapter() {
        LlmCostGuard smallBudget = new LlmCostGuard(100);
        smallBudget.recordUsage(USER_ID, new LlmUsage(100, 0)); // 已达上限
        LlmGatewayImpl gw =
                new LlmGatewayImpl(List.of(deepseek, glm), config, smallBudget, cache, executor);

        assertThatThrownBy(() -> gw.chat(request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.AI_QUOTA_EXHAUSTED));
        verify(deepseek, never()).chat(any());
        verify(glm, never()).chat(any());
    }

    @Test
    void chat_providerTimeout_triggersFallback() {
        // default provider 阻塞，超时后切 fallback（"自带" Future 超时，对齐 llm.timeout-seconds）
        when(deepseek.chat(any()))
                .thenAnswer(
                        inv -> {
                            try {
                                Thread.sleep(5000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return DS_RESP;
                        });
        when(glm.chat(any())).thenReturn(GLM_RESP);
        LlmConfig tinyTimeout =
                LlmConfigTest.configWith(
                        LlmConfigTest.deepseekProvider(), LlmConfigTest.glmProvider());
        tinyTimeout.setTimeoutSeconds(1);
        LlmGatewayImpl gw =
                new LlmGatewayImpl(List.of(deepseek, glm), tinyTimeout, costGuard, cache, executor);

        LlmResponse result = gw.chat(request());

        assertThat(result).isEqualTo(GLM_RESP);
        verify(glm).chat(any());
    }
}
