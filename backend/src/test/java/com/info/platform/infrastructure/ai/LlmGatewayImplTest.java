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
import com.info.platform.domain.ai.LlmCallLog;
import com.info.platform.domain.ai.LlmCallStatus;
import com.info.platform.domain.ai.LlmException;
import com.info.platform.domain.ai.LlmProvider;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.domain.ai.LlmUsage;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.UserContext;
import com.info.platform.infrastructure.ai.ConfigCenterStubs.Runtime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * LlmGatewayImpl 单测（T19+T30+T35）：default/fallback 编排 + 成本上限 + 缓存 + 超时 + 调用留痕四态 （ADR-0004/0008 +
 * §4.4）。
 *
 * <p>adapter 全 mock（Mockito，不依赖真实 API key）；T35 起链/启停/单价/超时经 {@link ConfigCenterStubs} 运行时
 * stub（可拨动的预算/单价/启停承载），覆盖：<b>热改语义</b>——改预算当次调用即拦截、改单价新调用按新价 且历史留痕不回溯（调价红线）、运行时停用 provider
 * 即跳过、运行时改超时即对下一次调用生效。
 */
class LlmGatewayImplTest {

    private static final long USER_ID = 1001L;

    private LlmProviderAdapter deepseek;
    private LlmProviderAdapter glm;
    private LlmCostGuard costGuard;
    private LlmCache cache;
    private LlmCallLogger callLog;
    private ExecutorService executor;
    private Runtime runtime;

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
        runtime = new Runtime(LlmProviderFixtures.deepseek(), LlmProviderFixtures.glm());
        costGuard = new LlmCostGuard(runtime.global::get);
        cache =
                new LlmCache(
                        bt -> java.time.Duration.ofSeconds(60),
                        java.time.Duration.ofSeconds(60),
                        100);
        callLog = mock(LlmCallLogger.class);
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
        return new LlmGatewayImpl(
                List.of(deepseek, glm),
                ConfigCenterStubs.stub(runtime),
                costGuard,
                cache,
                callLog,
                executor);
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
    void chat_success_recordsCallLogWithCostEstimate() {
        // Arrange：deepseek 单价 1 元/M 输入、4 元/M 输出 → 100×1 + 50×4 = 300 微元
        runtime.providers.get("deepseek").setInputPricePerMillion(1.0);
        runtime.providers.get("deepseek").setOutputPricePerMillion(4.0);
        when(deepseek.chat(any())).thenReturn(DS_RESP);

        // Act
        gateway().chat(request());

        // Assert：落 SUCCESS 留痕，provider/model/token/成本齐备
        ArgumentCaptor<LlmCallLog> captor = ArgumentCaptor.forClass(LlmCallLog.class);
        verify(callLog).record(captor.capture());
        LlmCallLog entry = captor.getValue();
        assertThat(entry.getStatus()).isEqualTo(LlmCallStatus.SUCCESS);
        assertThat(entry.getUserId()).isEqualTo(USER_ID);
        assertThat(entry.getProviderKey()).isEqualTo("deepseek");
        assertThat(entry.getModel()).isEqualTo("deepseek-flash");
        assertThat(entry.getPromptTokens()).isEqualTo(100);
        assertThat(entry.getCompletionTokens()).isEqualTo(50);
        assertThat(entry.getCostMicros()).isEqualTo(300L);
        assertThat(entry.isCacheHit()).isFalse();
    }

    @Test
    void chat_priceChangedAtRuntime_newCallUsesNewPriceAndHistoryUntouched() {
        // Arrange（T35 调价红线，PRD 场景 2.3）：第一次调用按 1.0/4.0 计价
        runtime.providers.get("deepseek").setInputPricePerMillion(1.0);
        runtime.providers.get("deepseek").setOutputPricePerMillion(4.0);
        when(deepseek.chat(any())).thenReturn(DS_RESP);
        LlmGatewayImpl gw = gateway();
        gw.chat(request());

        // Act：页面调价至 2.0/8.0（保存即生效），第二次调用（不同内容，避开缓存）
        runtime.providers.get("deepseek").setInputPricePerMillion(2.0);
        runtime.providers.get("deepseek").setOutputPricePerMillion(8.0);
        gw.chat(
                LlmRequest.json(
                        List.of(new ChatMessage("system", "sys"), new ChatMessage("user", "ctx-2")),
                        "1"));

        // Assert：第一行留痕维持调价前死值 300，第二行按调用时点新价 600（历史不回溯重算）
        ArgumentCaptor<LlmCallLog> captor = ArgumentCaptor.forClass(LlmCallLog.class);
        verify(callLog, times(2)).record(captor.capture());
        assertThat(captor.getAllValues().get(0).getCostMicros()).isEqualTo(300L);
        assertThat(captor.getAllValues().get(1).getCostMicros()).isEqualTo(600L);
    }

    @Test
    void chat_cacheHit_secondCallDoesNotInvokeAdapterAndLogsCacheHit() {
        when(deepseek.chat(any())).thenReturn(DS_RESP);
        LlmGatewayImpl gw = gateway();
        LlmRequest req = request();

        LlmResponse first = gw.chat(req);
        LlmResponse second = gw.chat(req); // 缓存命中

        assertThat(first).isEqualTo(DS_RESP);
        assertThat(second).isEqualTo(DS_RESP);
        verify(deepseek, times(1)).chat(any());

        // Assert：第二行留痕为缓存命中（0 token 0 成本，provider 记原响应来源）
        ArgumentCaptor<LlmCallLog> captor = ArgumentCaptor.forClass(LlmCallLog.class);
        verify(callLog, times(2)).record(captor.capture());
        LlmCallLog hit = captor.getAllValues().get(1);
        assertThat(hit.getStatus()).isEqualTo(LlmCallStatus.SUCCESS);
        assertThat(hit.isCacheHit()).isTrue();
        assertThat(hit.getProviderKey()).isEqualTo("deepseek");
        assertThat(hit.totalTokens()).isZero();
        assertThat(hit.getCostMicros()).isZero();
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
    void chat_allFail_throwsLlmExceptionWithAttemptedAndLogsFailed() {
        when(deepseek.chat(any())).thenThrow(new RuntimeException("ds err"));
        when(glm.chat(any())).thenThrow(new RuntimeException("glm err"));

        assertThatThrownBy(() -> gateway().chat(request()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("deepseek")
                .hasMessageContaining("glm");
        verify(deepseek).chat(any());
        verify(glm).chat(any());

        // Assert：落 FAILED 留痕（attempted 链入 error_message，无 provider）
        ArgumentCaptor<LlmCallLog> captor = ArgumentCaptor.forClass(LlmCallLog.class);
        verify(callLog).record(captor.capture());
        LlmCallLog entry = captor.getValue();
        assertThat(entry.getStatus()).isEqualTo(LlmCallStatus.FAILED);
        assertThat(entry.getProviderKey()).isNull();
        assertThat(entry.getErrorMessage()).contains("deepseek").contains("glm");
    }

    @Test
    void chat_costBudgetExhausted_throwsBeforeCallingAdapterAndLogsRejected() {
        LlmCostGuard smallBudget = new LlmCostGuard(() -> ConfigCenterStubs.global(30, 100, 0.8));
        smallBudget.recordUsage(USER_ID, new LlmUsage(100, 0)); // 已达上限
        LlmGatewayImpl gw =
                new LlmGatewayImpl(
                        List.of(deepseek, glm),
                        ConfigCenterStubs.stub(runtime),
                        smallBudget,
                        cache,
                        callLog,
                        executor);

        assertThatThrownBy(() -> gw.chat(request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.AI_QUOTA_EXHAUSTED));
        verify(deepseek, never()).chat(any());
        verify(glm, never()).chat(any());

        // Assert：落 REJECTED 留痕（未外呼，原因入 error_message）
        ArgumentCaptor<LlmCallLog> captor = ArgumentCaptor.forClass(LlmCallLog.class);
        verify(callLog).record(captor.capture());
        LlmCallLog entry = captor.getValue();
        assertThat(entry.getStatus()).isEqualTo(LlmCallStatus.REJECTED);
        assertThat(entry.getErrorMessage()).contains("配额已用尽");
        assertThat(entry.getCostMicros()).isZero();
    }

    @Test
    void chat_budgetLoweredAtRuntime_nextCallRejectedImmediately() {
        // Arrange（T35 热改，方案 §6「改预算 → 下一次调用按新值拦截」）：预算 20000 下先成功一次（用 150）
        when(deepseek.chat(any())).thenReturn(DS_RESP);
        LlmGatewayImpl gw = gateway();
        gw.chat(request());

        // Act：页面把预算改小至 100（已用 150），下一次调用即被拦截
        runtime.setBudget(100);

        // Assert
        assertThatThrownBy(
                        () ->
                                gw.chat(
                                        LlmRequest.json(
                                                List.of(
                                                        new ChatMessage("system", "sys"),
                                                        new ChatMessage("user", "ctx-3")),
                                                "1")))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.AI_QUOTA_EXHAUSTED))
                .hasMessageContaining("150/100");
        verify(deepseek, times(1)).chat(any()); // 拦截后未再外呼
    }

    @Test
    void chat_noDefaultProvider_throwsAndLogsFailed() {
        // Arrange：运行时无任何 default provider → fallback 链为空
        runtime.providers.get("deepseek").setDefault(false);
        runtime.providers.get("glm").setDefault(false);
        LlmGatewayImpl gw = gateway();

        // Act + Assert：抛 LlmException 并落 FAILED 留痕
        assertThatThrownBy(() -> gw.chat(request())).isInstanceOf(LlmException.class);
        ArgumentCaptor<LlmCallLog> captor = ArgumentCaptor.forClass(LlmCallLog.class);
        verify(callLog).record(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(LlmCallStatus.FAILED);
        assertThat(captor.getValue().getErrorMessage()).contains("未配置 default");
    }

    @Test
    void chat_defaultDisabledAtRuntime_fallsToGlmImmediately() {
        // Arrange（T35 热改）：运行时停用 default provider deepseek
        runtime.providers.get("deepseek").setEnabled(false);
        when(glm.chat(any())).thenReturn(GLM_RESP);

        // Act
        LlmResponse result = gateway().chat(request());

        // Assert：下一次调用即按运行时启停跳过 deepseek，glm 兜底
        assertThat(result).isEqualTo(GLM_RESP);
        verify(deepseek, never()).chat(any());
        verify(glm).chat(any());
    }

    @Test
    void chat_providerTimeout_triggersFallback() {
        // default provider 阻塞，超时后切 fallback（Future 超时；T35 起超时值运行时读取）
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
        LlmGatewayImpl gw = gateway();

        // Act：网关构建后运行时把超时从 30s 改小为 1s（保存即对下一次调用生效）
        runtime.setTimeoutSeconds(1);

        LlmResponse result = gw.chat(request());

        assertThat(result).isEqualTo(GLM_RESP);
        verify(glm).chat(any());
    }
}
