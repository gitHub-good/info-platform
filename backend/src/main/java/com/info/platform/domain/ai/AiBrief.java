package com.info.platform.domain.ai;

import java.time.Instant;
import java.util.Objects;

/**
 * AI 简报实体（{@code ai_brief} 表，T21）。
 *
 * <p>领域层纯净：仅依赖 JDK 类型，可脱离容器单测、可移植。持久化字段（id/时间戳/version）由基础设施层 {@code AiBriefRepositoryImpl} 经
 * {@link #reconstruct} 回填。
 *
 * <p>生命周期（对齐技术方案 §4.3 流程 2）：
 *
 * <ol>
 *   <li>{@link #createNew}：POST 受理时建任务（{@code status=PENDING}，content/model/prompt_version 空串占位）。
 *   <li>CAS 领取（仓储 {@code claim}）：{@code WHERE id=? AND status=0 AND version=?} bump version，影响 0
 *       行=被并发领走。
 *   <li>{@link #complete}/{@link #markNeedVerify}/{@link #markFailed}：异步生成完成，写
 *       content/sourceLinks/costTokens/model/promptVersion 并翻终态（1 完成 / 3 待核实 / 2 失败）。终态写入走仓储 {@code
 *       save}（updateById，@Version 乐观锁）。
 * </ol>
 *
 * <p>幂等：{@code idempotency_key=subjectId+briefType+yyyyMMdd}，{@code UNIQUE} 约束为重复 POST
 * 最后防线（已完成直返上次结果）。
 *
 * @param content BriefContent JSON（PENDING 时空串，终态写回真值）
 * @param model 实际服务模型（PENDING 时空串）
 * @param promptVersion 生成所用模板版本（PENDING 时空串）
 */
public class AiBrief {

    private Long id;
    private Long subjectId;
    private BriefType briefType;
    private String promptVersion;
    private String model;
    private String content;
    private String sourceLinks;
    private Integer costTokens;
    private BriefStatus status;
    private String idempotencyKey;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    private AiBrief() {}

    /**
     * 新建受理任务（POST 触发）：{@code status=PENDING}，content/model/prompt_version 空串占位（满足 NOT NULL）。
     *
     * @param subjectId 标的内部主键
     * @param briefType 简报类型
     * @param idempotencyKey 幂等键（subjectId+briefType+yyyyMMdd）
     */
    public static AiBrief createNew(Long subjectId, BriefType briefType, String idempotencyKey) {
        Objects.requireNonNull(briefType, "briefType 必填");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey 必填");
        AiBrief b = new AiBrief();
        b.subjectId = subjectId;
        b.briefType = briefType;
        b.idempotencyKey = idempotencyKey;
        b.status = BriefStatus.PENDING;
        b.promptVersion = "";
        b.model = "";
        b.content = "";
        b.version = 0;
        return b;
    }

    /**
     * 从持久化数据重建实体（基础设施层回读时用）。
     *
     * @param id 主键（taskId）
     * @param subjectId 标的内部主键（每日推荐型可空）
     * @param briefType 简报类型
     * @param promptVersion 模板版本
     * @param model 实际服务模型
     * @param content BriefContent JSON
     * @param sourceLinks 事实回链 JSON 数组（可空）
     * @param costTokens 成本 token（可空）
     * @param status 状态
     * @param idempotencyKey 幂等键
     * @param version 乐观锁版本
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public static AiBrief reconstruct(
            Long id,
            Long subjectId,
            BriefType briefType,
            String promptVersion,
            String model,
            String content,
            String sourceLinks,
            Integer costTokens,
            BriefStatus status,
            String idempotencyKey,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        Objects.requireNonNull(briefType, "briefType 必填");
        Objects.requireNonNull(status, "status 必填");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey 必填");
        AiBrief b = new AiBrief();
        b.id = id;
        b.subjectId = subjectId;
        b.briefType = briefType;
        b.promptVersion = promptVersion == null ? "" : promptVersion;
        b.model = model == null ? "" : model;
        b.content = content == null ? "" : content;
        b.sourceLinks = sourceLinks;
        b.costTokens = costTokens;
        b.status = status;
        b.idempotencyKey = idempotencyKey;
        b.version = version;
        b.createdAt = createdAt;
        b.updatedAt = updatedAt;
        return b;
    }

    /**
     * 生成完成：写 content/sourceLinks/costTokens/model/promptVersion 并翻 {@link BriefStatus#DONE}。
     *
     * <p>仅 PENDING 态可翻完成（防终态后误改）；非 PENDING 调用静默跳过（幂等，双发安全）。
     */
    public void complete(
            String content,
            String sourceLinks,
            Integer costTokens,
            String model,
            String promptVersion) {
        if (status != BriefStatus.PENDING) {
            return;
        }
        applyResult(content, sourceLinks, costTokens, model, promptVersion, BriefStatus.DONE);
    }

    /**
     * 标待核实：幻觉校验有数值不符或源暂不可用，写 content（仍展示降级简报）并翻 {@link BriefStatus#NEED_VERIFY}。
     *
     * <p>不阻断生成——content 已生成，仅状态降级（对齐 §4.3 流程 2 / Spike-2 §6「校验失败不阻断生成，只降级展示」）。
     */
    public void markNeedVerify(
            String content,
            String sourceLinks,
            Integer costTokens,
            String model,
            String promptVersion) {
        if (status != BriefStatus.PENDING) {
            return;
        }
        applyResult(
                content, sourceLinks, costTokens, model, promptVersion, BriefStatus.NEED_VERIFY);
    }

    /**
     * 标失败：LLM 调用失败 / 解析失败 / 超时兜底，翻 {@link BriefStatus#FAILED}（content 留空串）。
     *
     * <p>仅 PENDING 态可翻失败；失败不写 content（无可展示简报）。
     */
    public void markFailed() {
        if (status != BriefStatus.PENDING) {
            return;
        }
        this.status = BriefStatus.FAILED;
    }

    /** 超时兜底强制置失败（30min 卡死，懒查触发）：无视当前态强制翻 FAILED。 */
    public void forceFailed() {
        this.status = BriefStatus.FAILED;
    }

    private void applyResult(
            String content,
            String sourceLinks,
            Integer costTokens,
            String model,
            String promptVersion,
            BriefStatus target) {
        this.content = content == null ? "" : content;
        this.sourceLinks = sourceLinks;
        this.costTokens = costTokens;
        this.model = model == null ? "" : model;
        this.promptVersion = promptVersion == null ? "" : promptVersion;
        this.status = target;
    }

    public Long getId() {
        return id;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public BriefType getBriefType() {
        return briefType;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getModel() {
        return model;
    }

    public String getContent() {
        return content;
    }

    public String getSourceLinks() {
        return sourceLinks;
    }

    public Integer getCostTokens() {
        return costTokens;
    }

    public BriefStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
