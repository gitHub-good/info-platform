package com.info.platform.domain.ai;

import java.time.Instant;
import java.util.Objects;

/**
 * 提示词模板实体（{@code prompt_template} 表，T20）。
 *
 * <p>领域层纯净：仅依赖 JDK 类型，可脱离容器单测、可移植。持久化字段（id/时间戳）由基础设施层 {@code
 * PromptTemplateRepositoryImpl} 经 {@link #reconstruct} 回填。
 *
 * <p>模板生命周期：低频配置表，编辑即新版本行（{@code UNIQUE(brief_type, version)} 防重复）；同一 {@code briefType}
 * 同时仅一行 {@code status=1} 启用，其余 {@code status=0} 废弃。取"启用版本" = {@code WHERE brief_type=? AND
 * status=1 ORDER BY version DESC LIMIT 1}。
 *
 * <p>{@link #template} 为单段文本，用 {@code ---SYSTEM---} / {@code ---USER---} 标记分段（独占行），system 在前 user 在后；
 * 占位符用双花括号 {@code {{key}}}，与 user 中 JSON 输出格式约束的单花括号 {@code {summary,...}} 区分（渲染只替换
 * {@code {{key}}，见 {@code PromptTemplateService.render}）。所有 system 段均含 "json" 字样以满足 DeepSeek JSON mode 前提
 * （Spike-2 §5.2）。
 *
 * <p>对齐技术方案 §4.2 prompt_template DDL：无 version 乐观锁列（区别于 subject_master——本表为低频配置表，
 * 无并发 UPDATE 竞争，编辑即新行）。
 */
public class PromptTemplate {

    private final Long id;
    private final BriefType briefType;
    private final String version;
    private final String template;
    private final int status;
    private final Instant createdAt;
    private final Instant updatedAt;

    private PromptTemplate(
            Long id,
            BriefType briefType,
            String version,
            String template,
            int status,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.briefType = briefType;
        this.version = version;
        this.template = template;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 从持久化数据重建实体（基础设施层回读时用）。
     *
     * @param id 主键
     * @param briefType 简报类型
     * @param version 语义版本（如 {@code v1.0}）
     * @param template 模板正文（{@code ---SYSTEM---}/{@code ---USER---} 分段 + {@code {{key}}} 占位符）
     * @param status 启用状态（1 启用 / 0 废弃）
     */
    public static PromptTemplate reconstruct(
            Long id, BriefType briefType, String version, String template, int status) {
        return reconstruct(id, briefType, version, template, status, null, null);
    }

    /** 从持久化数据重建实体（含时间戳，M5 管理面列表/详情展示用；时间戳由基础设施层回填，ISO-8601 文本转 {@link Instant}）。 */
    public static PromptTemplate reconstruct(
            Long id,
            BriefType briefType,
            String version,
            String template,
            int status,
            Instant createdAt,
            Instant updatedAt) {
        Objects.requireNonNull(briefType, "briefType 必填");
        Objects.requireNonNull(version, "version 必填");
        Objects.requireNonNull(template, "template 必填");
        return new PromptTemplate(id, briefType, version, template, status, createdAt, updatedAt);
    }

    /**
     * 构建新版本行（M5 管理面「保存即激活」语义：新行直接 {@code status=1}，旧版由仓储先置废）。
     *
     * <p>id/时间戳留空，落库后由 {@code PromptTemplateRepositoryImpl.insert} 回填。
     */
    public static PromptTemplate newVersion(BriefType briefType, String version, String template) {
        Objects.requireNonNull(briefType, "briefType 必填");
        Objects.requireNonNull(version, "version 必填");
        Objects.requireNonNull(template, "template 必填");
        return new PromptTemplate(null, briefType, version, template, 1, null, null);
    }

    public Long getId() {
        return id;
    }

    public BriefType getBriefType() {
        return briefType;
    }

    public String getVersion() {
        return version;
    }

    public String getTemplate() {
        return template;
    }

    public int getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** 是否启用（{@code status==1}），T21 加载时据此过滤。 */
    public boolean isActive() {
        return status == 1;
    }
}
