package com.info.platform.domain.ai;

import java.time.Instant;

/**
 * LLM 调用留痕实体（llm_call_log 表，T30 成本治理）。
 *
 * <p>领域层纯净：仅依赖 JDK 类型（含同层 {@link LlmUsage}），可脱离容器单测、可移植。生命周期： {@code begin} 创建（仅 userId + 场景键）→
 * 恰好一次 {@code markCacheHit} / {@code markSuccess} / {@code markFailed} / {@code markRejected} 定型 →
 * 落库（追加型流水，只 INSERT 不 UPDATE）。id/时间戳由基础设施层 {@code LlmCallLogRepositoryImpl} 落库时回填。
 *
 * <p>写入方为基础设施层 {@code LlmGatewayImpl}（经 {@code LlmCallLogger}，留痕失败不阻断调用）； 查询方为应用层 {@code
 * LlmCostReportService}（成本报表窗口聚合）。
 */
public class LlmCallLog {

    /** 失败/拒绝原因摘要截断上限（防异常栈文本撑爆 TEXT 列与报表载荷）。 */
    static final int ERROR_MESSAGE_MAX_LENGTH = 500;

    private Long id;
    private final long userId;
    private final String sceneKey;
    private String providerKey;
    private String model;
    private LlmCallStatus status;
    private boolean cacheHit;
    private int promptTokens;
    private int completionTokens;
    private long costMicros;
    private long durationMillis;
    private String errorMessage;
    private Instant createdAt;

    private LlmCallLog(
            Long id,
            long userId,
            String sceneKey,
            String providerKey,
            String model,
            LlmCallStatus status,
            boolean cacheHit,
            int promptTokens,
            int completionTokens,
            long costMicros,
            long durationMillis,
            String errorMessage,
            Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.sceneKey = sceneKey;
        this.providerKey = providerKey;
        this.model = model;
        this.status = status;
        this.cacheHit = cacheHit;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.costMicros = costMicros;
        this.durationMillis = durationMillis;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
    }

    /**
     * 构建起始留痕（未定型）：id/时间戳留空，落库后回填。
     *
     * @param userId 发起用户（{@code 0}=系统调用，如无认证上下文的定时任务）
     * @param sceneKey 场景键（对齐 {@code LlmRequest.briefTypeKey}，如 "1" 个股简报；null 规整为空串）
     */
    public static LlmCallLog begin(long userId, String sceneKey) {
        return new LlmCallLog(
                null,
                userId,
                sceneKey == null ? "" : sceneKey,
                null,
                null,
                null,
                false,
                0,
                0,
                0L,
                0L,
                null,
                null);
    }

    /** 从持久化数据重建实体（基础设施层回读时用）。 */
    public static LlmCallLog reconstruct(
            Long id,
            long userId,
            String sceneKey,
            String providerKey,
            String model,
            LlmCallStatus status,
            boolean cacheHit,
            int promptTokens,
            int completionTokens,
            long costMicros,
            long durationMillis,
            String errorMessage,
            Instant createdAt) {
        return new LlmCallLog(
                id,
                userId,
                sceneKey,
                providerKey,
                model,
                status,
                cacheHit,
                promptTokens,
                completionTokens,
                costMicros,
                durationMillis,
                errorMessage,
                createdAt);
    }

    /**
     * 定型为缓存命中成功：本地 Caffeine 命中直返（0 外呼、0 token、0 成本），provider/model 记原响应来源供回溯。
     *
     * @param providerKey 原响应厂商配置键（如 "deepseek"）
     * @param model 原响应模型名
     * @param durationMillis 端到端耗时（命中路径≈0）
     */
    public void markCacheHit(String providerKey, String model, long durationMillis) {
        this.providerKey = providerKey;
        this.model = model;
        this.status = LlmCallStatus.SUCCESS;
        this.cacheHit = true;
        this.durationMillis = Math.max(0, durationMillis);
    }

    /**
     * 定型为外呼成功：记录实际 provider/模型、token 用量与成本估算。
     *
     * @param providerKey 实际服务的厂商配置键
     * @param model 实际模型名（含 request 覆盖）
     * @param usage token 用量（null 按 0/0 容错）
     * @param costMicros 成本估算（微元，经 {@link #estimateCostMicros} 计算）
     * @param durationMillis 端到端耗时毫秒
     */
    public void markSuccess(
            String providerKey,
            String model,
            LlmUsage usage,
            long costMicros,
            long durationMillis) {
        LlmUsage safe = usage == null ? new LlmUsage(0, 0) : usage;
        this.providerKey = providerKey;
        this.model = model;
        this.status = LlmCallStatus.SUCCESS;
        this.promptTokens = safe.promptTokens();
        this.completionTokens = safe.completionTokens();
        this.costMicros = Math.max(0, costMicros);
        this.durationMillis = Math.max(0, durationMillis);
    }

    /**
     * 定型为失败：fallback 链全部失败或无 default provider。
     *
     * @param attemptedInfo 已尝试 provider 链摘要（超长截断）
     * @param durationMillis 端到端耗时毫秒
     */
    public void markFailed(String attemptedInfo, long durationMillis) {
        this.status = LlmCallStatus.FAILED;
        this.durationMillis = Math.max(0, durationMillis);
        this.errorMessage = truncate(attemptedInfo);
    }

    /**
     * 定型为预算拦截：外呼前被单用户日 token 预算拒绝（未产生外呼成本）。
     *
     * @param reason 拦截原因（超长截断）
     */
    public void markRejected(String reason) {
        this.status = LlmCallStatus.REJECTED;
        this.errorMessage = truncate(reason);
    }

    /**
     * 成本估算：{@code token × 元/百万token = 微元}（1e6 token × 1 元 = 1e6 微元），四舍五入取整。
     *
     * <p>微元（1e-6 元）整数存储避免浮点累加漂移；单价来自 {@code llm.providers[].input/output-price-per-million}
     * 配置（Spike-2 §8.1 公开定价，未配置默认 0 即不估算）。负单价按 0 容错。
     *
     * @param promptTokens 输入 token 数
     * @param completionTokens 输出 token 数
     * @param inputPricePerMillion 输入单价（元/百万 token）
     * @param outputPricePerMillion 输出单价（元/百万 token）
     * @return 成本估算（微元）
     */
    public static long estimateCostMicros(
            int promptTokens,
            int completionTokens,
            double inputPricePerMillion,
            double outputPricePerMillion) {
        double input = Math.max(0, inputPricePerMillion);
        double output = Math.max(0, outputPricePerMillion);
        return Math.round(promptTokens * input + completionTokens * output);
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= ERROR_MESSAGE_MAX_LENGTH
                ? message
                : message.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public String getSceneKey() {
        return sceneKey;
    }

    public String getProviderKey() {
        return providerKey;
    }

    public String getModel() {
        return model;
    }

    public LlmCallStatus getStatus() {
        return status;
    }

    public boolean isCacheHit() {
        return cacheHit;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    /** token 合计（输入+输出），报表口径。 */
    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    public long getCostMicros() {
        return costMicros;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
