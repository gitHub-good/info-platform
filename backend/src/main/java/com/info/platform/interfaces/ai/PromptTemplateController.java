package com.info.platform.interfaces.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.info.platform.application.ai.PromptPlaceholderRegistry;
import com.info.platform.application.ai.PromptTemplateAdminService;
import com.info.platform.application.ai.PromptTemplateAdminService.CreateCommand;
import com.info.platform.application.ai.PromptTemplateAdminService.ListView;
import com.info.platform.application.ai.PromptTemplateValidator.PlaceholderItem;
import com.info.platform.application.ai.PromptVersionGenerator.VersionStrategy;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.PlaceholderDescriptor;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.interfaces.common.Result;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提示词模板管理接口（接口层，T45/T46，方案 §4.4，REQ-20260922-03）。
 *
 * <p>受 JWT 保护（{@code /api/v1/prompt-*} 不在 {@code JwtAuthFilter} 白名单）。薄层：参数校验与视图组装， 编排/事务/不变量在
 * {@code PromptTemplateAdminService}；占位符注册表直接读 {@link PromptPlaceholderRegistry} （与保存校验 V5
 * 同一实例——对照区与校验基准同源，ADR-0022）。
 *
 * <h2>端点</h2>
 *
 * <ul>
 *   <li>{@code GET /api/v1/prompt-templates} —— 4 场景分组版本列表（含 activeCount 异常空态检测）
 *   <li>{@code GET /api/v1/prompt-templates/{id}} —— 版本详情（template 全文 + sections 预分 + placeholders
 *       清单）
 *   <li>{@code POST /api/v1/prompt-templates} —— 新建版本（保存即激活；30067 硬校验 / 30068 待确认移除 / 30070 冲突）
 *   <li>{@code POST /api/v1/prompt-templates/{id}/activate} —— 激活切换（回滚；幂等）
 *   <li>{@code DELETE /api/v1/prompt-templates/{id}} —— 物理删除（仅 RETIRED，30069 守卫）
 *   <li>{@code GET /api/v1/prompt-placeholders} —— 占位符注册表只读（编辑器对照区，可选 briefType 过滤）
 * </ul>
 */
@RestController
public class PromptTemplateController {

    /** 场景 2（事件归因）休眠披露文案（方案 §4.4.5：无生产触发入口，事件类占位符无注入来源）。 */
    static final String DORMANT_EVENT_NOTE = "该场景当前无生产触发入口；事件类占位符（eventTitle 等）暂无上下文来源，使用时将原样发给模型";

    private final PromptTemplateAdminService adminService;
    private final PromptPlaceholderRegistry placeholderRegistry;

    public PromptTemplateController(
            PromptTemplateAdminService adminService,
            PromptPlaceholderRegistry placeholderRegistry) {
        this.adminService = adminService;
        this.placeholderRegistry = placeholderRegistry;
    }

    /** 4 场景分组版本总览（故事 1）。 */
    @GetMapping("/api/v1/prompt-templates")
    public Result<ListView> list() {
        return Result.ok(adminService.list());
    }

    /** 版本详情（故事 1 场景 2：编辑底稿与 diff 基准取全文；404 → 30066）。 */
    @GetMapping("/api/v1/prompt-templates/{id}")
    public Result<PromptTemplateAdminService.DetailView> detail(@PathVariable Long id) {
        return Result.ok(adminService.detail(id));
    }

    /**
     * 新建版本（故事 2：保存即激活，201）。
     *
     * <p>30068（待确认占位符移除）在本控制器直返 {@code Result.fail(code, msg, data)}——全局异常处理器无 data 形态，方案 §4.8
     * 裁定控制器直返；data 携带 removed/unknown 清单供前端确认 Dialog 渲染勾选。成功/确认分支 data 形态不同（CreateResult /
     * RemovalConfirmationData），故响应泛型取 Object，形态按 code 区分（0 / 30068）。
     */
    @PostMapping("/api/v1/prompt-templates")
    public ResponseEntity<Result<Object>> create(@RequestBody CreateTemplateRequest request) {
        CreateCommand command = toCommand(request);
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Result.<Object>ok(adminService.create(command)));
        } catch (PromptTemplateAdminService.RemovalConfirmationRequiredException ex) {
            return ResponseEntity.status(
                            ErrorCode.PROMPT_PLACEHOLDER_REMOVAL_UNCONFIRMED.getHttpStatus())
                    .body(
                            Result.<Object>fail(
                                    ErrorCode.PROMPT_PLACEHOLDER_REMOVAL_UNCONFIRMED,
                                    null,
                                    new RemovalConfirmationData(
                                            ex.validation().removed(), ex.validation().unknown())));
        }
    }

    /** 激活切换（故事 3：回滚；目标已是激活幂等 200）。 */
    @PostMapping("/api/v1/prompt-templates/{id}/activate")
    public Result<PromptTemplateAdminService.ActivateResult> activate(@PathVariable Long id) {
        return Result.ok(adminService.activate(id));
    }

    /** 物理删除（故事 3：仅置废版本可删，激活 → 30069）。 */
    @DeleteMapping("/api/v1/prompt-templates/{id}")
    public Result<PromptTemplateAdminService.DeleteResult> delete(@PathVariable Long id) {
        return Result.ok(adminService.delete(id));
    }

    /**
     * 占位符注册表只读（故事 4：编辑器对照区 + 一键插入）。
     *
     * @param briefType 可选场景过滤（1~4）；缺省返回全部 4 场景
     */
    @GetMapping("/api/v1/prompt-placeholders")
    public Result<PlaceholderRegistryView> placeholders(
            @RequestParam(value = "briefType", required = false) Integer briefType) {
        List<BriefType> targets =
                briefType == null
                        ? Arrays.asList(BriefType.values())
                        : List.of(parseBriefType(briefType));
        List<PlaceholderScenarioView> scenarios =
                targets.stream().map(this::toScenarioView).toList();
        return Result.ok(new PlaceholderRegistryView(scenarios));
    }

    /** 请求体 → 命令：briefType/versionStrategy 非法值按参数校验口径 400/2001（不落 5xxx）。 */
    private static CreateCommand toCommand(CreateTemplateRequest request) {
        if (request == null || request.briefType() == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "briefType: 必填（1~4）");
        }
        return new CreateCommand(
                parseBriefType(request.briefType()),
                request.baseVersionId(),
                request.template(),
                parseStrategy(request.versionStrategy()),
                request.confirmedRemovedKeys() == null
                        ? Set.of()
                        : Set.copyOf(request.confirmedRemovedKeys()));
    }

    private static VersionStrategy parseStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) {
            return VersionStrategy.MINOR;
        }
        try {
            return VersionStrategy.valueOf(strategy.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID,
                    "versionStrategy: 须为 MINOR 或 MAJOR（收到 " + strategy + "）");
        }
    }

    /** 场景视图组装：dormant = 该场景无生产触发入口（当前仅事件归因），如披露 note。 */
    private PlaceholderScenarioView toScenarioView(BriefType briefType) {
        boolean dormant = briefType == BriefType.EVENT_ATTRIBUTION;
        return new PlaceholderScenarioView(
                briefType.code(),
                briefType.displayName(),
                dormant,
                dormant ? DORMANT_EVENT_NOTE : null,
                placeholderRegistry.byBriefType(briefType));
    }

    /** 持久化码 → 枚举（未知码按参数校验口径 400/2001，不落 5xxx）。 */
    private static BriefType parseBriefType(Integer code) {
        try {
            return BriefType.fromCode(code);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID, "briefType: 未知场景码 " + code + "（合法: 1~4）");
        }
    }

    /** 新建版本请求体（方案 §4.4.3；versionStrategy 字段名对齐 UI 方案 §6.2，confirmedRemovedKeys 确认后重提交）。 */
    public record CreateTemplateRequest(
            Integer briefType,
            Long baseVersionId,
            String template,
            String versionStrategy,
            List<String> confirmedRemovedKeys) {}

    /** 30068 data 形态：removed（拦截条件，须逐项确认）+ unknown（仅随单展示，不拦截）。 */
    public record RemovalConfirmationData(
            List<PlaceholderItem> removed, List<PlaceholderItem> unknown) {}

    /** 注册表响应视图（方案 §4.4.5）。 */
    public record PlaceholderRegistryView(List<PlaceholderScenarioView> scenarios) {}

    /** 单场景分组：note 仅休眠场景携带（非休眠不输出该字段）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlaceholderScenarioView(
            int briefType,
            String name,
            boolean dormant,
            String note,
            List<PlaceholderDescriptor> placeholders) {}
}
