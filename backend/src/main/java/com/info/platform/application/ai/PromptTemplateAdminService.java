package com.info.platform.application.ai;

import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.PlaceholderDescriptor;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.ai.PromptTemplateRepository;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提示词模板管理应用服务（应用层，T45，方案 §4.6）：M5 可视化治理的编排与事务边界（Controller 薄层）。
 *
 * <p>版本安全三通道（PRD 红线「任何操作后每场景恒有且仅有一个 {@code status=1}」，接口路径承诺）：
 *
 * <ul>
 *   <li><b>create（编辑即新版本 + 保存即激活）</b>：校验（三级，纯函数）→ 版本号受控生成 → 同事务「先置废全部启用 → 插入新行
 *       status=1」——任何时刻对外可见状态恒为恰一行激活。
 *   <li><b>activate（回滚）</b>：目标存在校验 → 同场景其余置 0 → 目标置 1；目标已是激活则幂等无变更。
 *   <li><b>delete（物理删除）</b>：仅 {@code status=0} 可删（30069 守卫，激活删除必须先切换）。
 * </ul>
 *
 * <p>并发防护：单人低频操作 + 前端二次确认，不设乐观锁（V8 头注既有裁定）；UNIQUE 索引是脏数据最后防线 （生成器取 max+1 后仍冲突 = 并发竞争，30070 +
 * 事务回滚零变更）。并发败者的另一形态——读写同事务命中 WAL 过期快照（SQLITE_BUSY 族，busy_timeout 不挽救）—— 由仓储统一翻译为 {@link
 * CannotAcquireLockException}，本服务 create/activate 据此转 30070「请刷新重试」（DEFECT-1，客户端重试即成；
 * 不做服务端写冲突重试——重试需重走读阶段换新快照，须拆事务，超出轻量修复边界）。审计：create/activate/delete 各记一条 INFO。
 */
@Service
public class PromptTemplateAdminService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateAdminService.class);

    private static final String STATUS_ACTIVE_TEXT = "ACTIVE";
    private static final String STATUS_RETIRED_TEXT = "RETIRED";

    private final PromptTemplateRepository repository;
    private final PromptPlaceholderRegistry placeholderRegistry;

    public PromptTemplateAdminService(
            PromptTemplateRepository repository, PromptPlaceholderRegistry placeholderRegistry) {
        this.repository = repository;
        this.placeholderRegistry = placeholderRegistry;
    }

    /**
     * 4 场景分组版本总览（恒 4 组，含空场景——异常空态由 activeCount 透出，前端警示）。
     *
     * <p>versions 按 (major, minor) 数值降序（ADR-0021：字典序在 v1.9/v1.10 类场景错序，排序在 Java）。
     */
    public ListView list() {
        List<GroupView> groups = new ArrayList<>(BriefType.values().length);
        for (BriefType type : BriefType.values()) {
            // 防御性拷贝再排序（仓储可能返回不可变 List）
            List<PromptTemplate> rows = new ArrayList<>(repository.findAllByBriefType(type));
            rows.sort(
                    Comparator.comparing(
                                    PromptTemplate::getVersion,
                                    PromptVersionGenerator::compareNumeric)
                            .reversed());
            List<VersionView> versions =
                    rows.stream().map(PromptTemplateAdminService::toVersionView).toList();
            long activeCount = rows.stream().filter(PromptTemplate::isActive).count();
            Long activeVersionId =
                    rows.stream()
                            .filter(PromptTemplate::isActive)
                            .findFirst()
                            .map(PromptTemplate::getId)
                            .orElse(null);
            groups.add(
                    new GroupView(
                            type.code(),
                            type.displayName(),
                            activeVersionId,
                            (int) activeCount,
                            versions));
        }
        return new ListView(groups);
    }

    /**
     * 版本详情：template 全文（编辑底稿与 diff 基准）+ sections 服务端预分 + 占位符清单（模板出现序去重）。
     *
     * @throws BusinessException 30066 版本不存在（404）
     */
    public DetailView detail(Long id) {
        PromptTemplate template = requireTemplate(id);
        String[] sections = PromptSections.split(template.getTemplate(), template.getVersion());
        return new DetailView(
                template.getId(),
                template.getBriefType().code(),
                template.getBriefType().displayName(),
                template.getVersion(),
                statusText(template),
                template.getTemplate(),
                new SectionsView(sections[0].strip(), sections[1].strip()),
                PromptSections.extractKeys(template.getTemplate()));
    }

    /**
     * 新建版本并激活（事务：先置废后插入）。
     *
     * <p>校验基准 = baseVersionId 指向的底稿版本行（与前端编辑底稿一致，UI 联判点 3）；缺省/无效回落当前激活版， 无激活版基准为空集。放行条件见 {@link
     * PromptTemplateValidator.ValidationResult#passableWith}。
     *
     * @throws BusinessException 30067 硬校验失败（msg 逐条）；30070 版本号冲突（UNIQUE 兜底 + SQLITE_BUSY 族
     *     并发写冲突，DEFECT-1；事务回滚）
     * @throws RemovalConfirmationRequiredException 30068 存在未确认的占位符移除（携带 removed/unknown 清单）
     */
    @Transactional
    public CreateResult create(CreateCommand command) {
        Objects.requireNonNull(command, "command 必填");
        BriefType briefType = command.briefType();
        Objects.requireNonNull(briefType, "briefType 必填");

        String baseText = resolveBaseText(briefType, command.baseVersionId());
        List<PlaceholderDescriptor> registered = placeholderRegistry.byBriefType(briefType);
        PromptTemplateValidator.ValidationResult validation =
                PromptTemplateValidator.validate(command.template(), baseText, registered);
        if (!validation.hardPassed()) {
            throw new BusinessException(
                    ErrorCode.PROMPT_TEMPLATE_INVALID, String.join("; ", validation.hardErrors()));
        }
        if (!validation.passableWith(command.confirmedRemovedKeys())) {
            throw new RemovalConfirmationRequiredException(validation);
        }

        String nextVersion =
                PromptVersionGenerator.nextVersion(existingVersions(briefType), command.strategy());
        String deactivatedVersion =
                repository
                        .findActiveByBriefType(briefType)
                        .map(PromptTemplate::getVersion)
                        .orElse(null);
        PromptTemplate saved;
        try {
            repository.deactivateActive(briefType);
            saved =
                    repository.insert(
                            PromptTemplate.newVersion(briefType, nextVersion, command.template()));
        } catch (DuplicateKeyException | CannotAcquireLockException e) {
            // 并发竞争败者统一 30070（DEFECT-1）：UNIQUE 兜底（生成器 max+1 仍撞既有版本）或并发写冲突
            // （读后写同事务命中 WAL 过期快照，仓储已译 SQLITE_BUSY 族）——事务回滚零变更，旧激活继续生效，请重试
            log.warn(
                    "提示词版本冲突回滚: briefType={}, version={}, cause={}",
                    briefType.code(),
                    nextVersion,
                    e.getClass().getSimpleName());
            throw new BusinessException(
                    ErrorCode.PROMPT_TEMPLATE_VERSION_CONFLICT,
                    "版本号冲突: briefType="
                            + briefType.code()
                            + " version="
                            + nextVersion
                            + "（可能被并发创建，请刷新列表后重试）");
        }
        log.info(
                "提示词模板新建版本并激活: briefType={}, version={}, deactivatedVersion={}, "
                        + "placeholderCount={}, warnings={}",
                briefType.code(),
                nextVersion,
                deactivatedVersion,
                PromptSections.countKeys(command.template()),
                validation.unknownWarnings().size());
        return new CreateResult(
                saved.getId(),
                briefType.code(),
                nextVersion,
                STATUS_ACTIVE_TEXT,
                deactivatedVersion,
                PromptSections.countKeys(command.template()),
                validation.unknownWarnings());
    }

    /**
     * 激活切换（回滚任意保留版本；目标已是激活幂等 200 无变更）。
     *
     * @throws BusinessException 30066 版本不存在（404）；30070 并发写冲突（读后写同事务与 create 同机制，DEFECT-1
     *     一并收敛——败者事务回滚不变量无中间态，请刷新重试）
     */
    @Transactional
    public ActivateResult activate(Long id) {
        PromptTemplate target = requireTemplate(id);
        if (target.isActive()) {
            log.info("提示词模板激活幂等命中: id={}, version={}", target.getId(), target.getVersion());
            return new ActivateResult(
                    target.getId(),
                    target.getBriefType().code(),
                    target.getVersion(),
                    STATUS_ACTIVE_TEXT,
                    null);
        }
        String deactivatedVersion =
                repository
                        .findActiveByBriefType(target.getBriefType())
                        .map(PromptTemplate::getVersion)
                        .orElse(null);
        try {
            repository.deactivateActive(target.getBriefType());
            repository.updateStatus(target.getId(), 1);
        } catch (CannotAcquireLockException e) {
            // 并发写冲突败者（SQLITE_BUSY 族，仓储已译）→ 30070：事务回滚，激活唯一不变量保持，请重试
            log.warn(
                    "提示词模板激活切换冲突回滚: briefType={}, targetVersion={}, cause={}",
                    target.getBriefType().code(),
                    target.getVersion(),
                    e.getClass().getSimpleName());
            throw new BusinessException(
                    ErrorCode.PROMPT_TEMPLATE_VERSION_CONFLICT,
                    "激活切换冲突: briefType=" + target.getBriefType().code() + "（可能被并发操作，请刷新列表后重试）");
        }
        log.info(
                "提示词模板激活切换: briefType={}, version={}, deactivatedVersion={}",
                target.getBriefType().code(),
                target.getVersion(),
                deactivatedVersion);
        return new ActivateResult(
                target.getId(),
                target.getBriefType().code(),
                target.getVersion(),
                STATUS_ACTIVE_TEXT,
                deactivatedVersion);
    }

    /**
     * 物理删除（单行 DELETE 无需显式事务；守卫：仅置废版本可删）。
     *
     * @throws BusinessException 30066 版本不存在（404）；30069 激活版本不可删除（409）
     */
    public DeleteResult delete(Long id) {
        PromptTemplate target = requireTemplate(id);
        if (target.isActive()) {
            throw new BusinessException(ErrorCode.PROMPT_TEMPLATE_ACTIVE_DELETE_FORBIDDEN);
        }
        repository.deleteById(target.getId());
        log.info(
                "提示词模板物理删除: id={}, briefType={}, version={}",
                target.getId(),
                target.getBriefType().code(),
                target.getVersion());
        return new DeleteResult(target.getId(), target.getVersion(), true);
    }

    /** 底稿基准解析：baseVersionId 有效（存在且同场景）优先，否则回落当前激活版，再无则 null（空基准）。 */
    private String resolveBaseText(BriefType briefType, Long baseVersionId) {
        if (baseVersionId != null) {
            Optional<PromptTemplate> base =
                    repository.findById(baseVersionId).filter(t -> t.getBriefType() == briefType);
            if (base.isPresent()) {
                return base.get().getTemplate();
            }
        }
        return repository
                .findActiveByBriefType(briefType)
                .map(PromptTemplate::getTemplate)
                .orElse(null);
    }

    private List<String> existingVersions(BriefType briefType) {
        return repository.findAllByBriefType(briefType).stream()
                .map(PromptTemplate::getVersion)
                .toList();
    }

    private PromptTemplate requireTemplate(Long id) {
        return repository
                .findById(id)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.PROMPT_TEMPLATE_VERSION_NOT_FOUND,
                                        "模板版本不存在: id=" + id));
    }

    private static VersionView toVersionView(PromptTemplate row) {
        return new VersionView(
                row.getId(),
                row.getVersion(),
                statusText(row),
                PromptSections.countKeys(row.getTemplate()),
                iso(row.getCreatedAt()),
                iso(row.getUpdatedAt()));
    }

    private static String statusText(PromptTemplate row) {
        return row.isActive() ? STATUS_ACTIVE_TEXT : STATUS_RETIRED_TEXT;
    }

    private static String iso(java.time.Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /** 待确认移除信号（30068）：控制器捕获后以 Result.fail(code, msg, data) 直返清单。 */
    public static final class RemovalConfirmationRequiredException extends RuntimeException {

        private final transient PromptTemplateValidator.ValidationResult validation;

        public RemovalConfirmationRequiredException(
                PromptTemplateValidator.ValidationResult validation) {
            super(ErrorCode.PROMPT_PLACEHOLDER_REMOVAL_UNCONFIRMED.getMsg());
            this.validation = validation;
        }

        public PromptTemplateValidator.ValidationResult validation() {
            return validation;
        }
    }

    /** 新建版本命令（briefType 必填；strategy 缺省 MINOR；confirmedRemovedKeys 确认移除键名集合）。 */
    public record CreateCommand(
            BriefType briefType,
            Long baseVersionId,
            String template,
            PromptVersionGenerator.VersionStrategy strategy,
            Set<String> confirmedRemovedKeys) {}

    /** 列表响应（方案 §4.4.1）。 */
    public record ListView(List<GroupView> groups) {}

    /** 场景分组：activeCount 异常（0 或 ≥2，手工改库迹象）由前端警示。 */
    public record GroupView(
            int briefType,
            String name,
            Long activeVersionId,
            int activeCount,
            List<VersionView> versions) {}

    /** 版本行（轻列表，不带 template 全文——详情单查）。 */
    public record VersionView(
            Long id,
            String version,
            String status,
            int placeholderCount,
            String createdAt,
            String updatedAt) {}

    /** 详情响应（方案 §4.4.2）：全文 + sections 预分 + 占位符清单。 */
    public record DetailView(
            Long id,
            int briefType,
            String name,
            String version,
            String status,
            String template,
            SectionsView sections,
            List<String> placeholders) {}

    /** system/user 两段预分（服务端共享切分逻辑，前端免实现）。 */
    public record SectionsView(String system, String user) {}

    /** 新建版本响应（方案 §4.4.3）：保存即激活 + warnings 回显 unknown 占位符。 */
    public record CreateResult(
            Long id,
            int briefType,
            String version,
            String status,
            String deactivatedVersion,
            int placeholderCount,
            List<String> warnings) {}

    /** 激活切换响应（方案 §4.4.4）：幂等命中时 deactivatedVersion=null。 */
    public record ActivateResult(
            Long id, int briefType, String version, String status, String deactivatedVersion) {}

    /** 删除响应。 */
    public record DeleteResult(Long id, String version, boolean deleted) {}
}
