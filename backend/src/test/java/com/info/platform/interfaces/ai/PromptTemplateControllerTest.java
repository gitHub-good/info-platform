package com.info.platform.interfaces.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.ai.PromptPlaceholderRegistry;
import com.info.platform.application.ai.PromptTemplateAdminService;
import com.info.platform.application.ai.PromptTemplateAdminService.CreateCommand;
import com.info.platform.application.ai.PromptTemplateValidator;
import com.info.platform.application.ai.PromptVersionGenerator.VersionStrategy;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.PlaceholderDescriptor;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * PromptTemplateController 切片测试（T45/T46）：契约形状（列表/详情/新建/激活/删除/注册表）+ 错误码映射
 * （30066/30067/30068/30069/30070、参数 2001）+ 请求体 → 命令解析透传。
 *
 * <p>对齐 LlmConfigControllerTest 模式：standaloneSetup 独立 MockMvc，应用端口 Mockito mock， {@link
 * GlobalExceptionHandler} 作 ControllerAdvice（JWT 保护由生产过滤器承担，不入白名单即受保护，此处不重复测）。
 */
class PromptTemplateControllerTest {

    private MockMvc mockMvc;
    private PromptTemplateAdminService adminService;
    private PromptPlaceholderRegistry placeholderRegistry;

    @BeforeEach
    void setUp() {
        adminService = mock(PromptTemplateAdminService.class);
        placeholderRegistry = mock(PromptPlaceholderRegistry.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new PromptTemplateController(adminService, placeholderRegistry))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void list_returnsGroupedVersions() throws Exception {
        // Arrange
        when(adminService.list())
                .thenReturn(
                        new PromptTemplateAdminService.ListView(
                                List.of(
                                        new PromptTemplateAdminService.GroupView(
                                                1,
                                                "个股简报",
                                                3L,
                                                1,
                                                List.of(
                                                        new PromptTemplateAdminService.VersionView(
                                                                3L,
                                                                "v1.2",
                                                                "ACTIVE",
                                                                17,
                                                                "2026-09-22T01:00:00Z",
                                                                "2026-09-22T01:00:00Z"),
                                                        new PromptTemplateAdminService.VersionView(
                                                                1L,
                                                                "v1.0",
                                                                "RETIRED",
                                                                17,
                                                                "2026-09-21T00:00:00Z",
                                                                "2026-09-22T00:00:00Z"))))));

        // Act + Assert
        mockMvc.perform(get("/api/v1/prompt-templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.groups[0].briefType").value(1))
                .andExpect(jsonPath("$.data.groups[0].name").value("个股简报"))
                .andExpect(jsonPath("$.data.groups[0].activeVersionId").value(3))
                .andExpect(jsonPath("$.data.groups[0].activeCount").value(1))
                .andExpect(jsonPath("$.data.groups[0].versions[0].version").value("v1.2"))
                .andExpect(jsonPath("$.data.groups[0].versions[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.groups[0].versions[0].placeholderCount").value(17))
                .andExpect(jsonPath("$.data.groups[0].versions[1].status").value("RETIRED"));
    }

    @Test
    void detail_returnsFullTemplateWithSectionsAndPlaceholders() throws Exception {
        // Arrange
        when(adminService.detail(3L))
                .thenReturn(
                        new PromptTemplateAdminService.DetailView(
                                3L,
                                1,
                                "个股简报",
                                "v1.2",
                                "ACTIVE",
                                "---SYSTEM---\nsys json\n---USER---\nu {{subjectName}}",
                                new PromptTemplateAdminService.SectionsView(
                                        "sys json", "u {{subjectName}}"),
                                List.of("subjectName")));

        // Act + Assert
        mockMvc.perform(get("/api/v1/prompt-templates/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(3))
                .andExpect(jsonPath("$.data.briefType").value(1))
                .andExpect(jsonPath("$.data.name").value("个股简报"))
                .andExpect(jsonPath("$.data.version").value("v1.2"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.template").isNotEmpty())
                .andExpect(jsonPath("$.data.sections.system").value("sys json"))
                .andExpect(jsonPath("$.data.sections.user").value("u {{subjectName}}"))
                .andExpect(jsonPath("$.data.placeholders[0]").value("subjectName"));
    }

    @Test
    void detail_notFound_returns404With30066() throws Exception {
        when(adminService.detail(404L))
                .thenThrow(new BusinessException(ErrorCode.PROMPT_TEMPLATE_VERSION_NOT_FOUND));

        mockMvc.perform(get("/api/v1/prompt-templates/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30066));
    }

    @Test
    void create_happyPath_returns201WithActivatedVersionAndWarnings() throws Exception {
        // Arrange
        when(adminService.create(any(CreateCommand.class)))
                .thenReturn(
                        new PromptTemplateAdminService.CreateResult(
                                10L,
                                1,
                                "v1.3",
                                "ACTIVE",
                                "v1.2",
                                16,
                                List.of("占位符 foo 无上下文来源，渲染时将原样发给模型")));

        // Act + Assert：保存即激活 201 + warnings 回显
        mockMvc.perform(
                        post("/api/v1/prompt-templates")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"briefType": 1,
                                         "baseVersionId": 3,
                                         "template": "---SYSTEM---\\nsys json\\n---USER---\\nu {{subjectName}}",
                                         "versionStrategy": "MINOR"}
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.version").value("v1.3"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.deactivatedVersion").value("v1.2"))
                .andExpect(jsonPath("$.data.placeholderCount").value(16))
                .andExpect(jsonPath("$.data.warnings[0]").isNotEmpty());

        // Assert：命令透传（briefType/baseVersionId/template/strategy 缺省 MINOR/空确认集）
        ArgumentCaptor<CreateCommand> captor = ArgumentCaptor.forClass(CreateCommand.class);
        verify(adminService).create(captor.capture());
        assertThat(captor.getValue().briefType()).isEqualTo(BriefType.STOCK);
        assertThat(captor.getValue().baseVersionId()).isEqualTo(3L);
        assertThat(captor.getValue().strategy()).isEqualTo(VersionStrategy.MINOR);
        assertThat(captor.getValue().confirmedRemovedKeys()).isEmpty();
    }

    @Test
    void create_hardInvalid_returns400With30067AndItemizedMsg() throws Exception {
        // Arrange：硬校验失败（msg 逐条，故事 2 场景 3/4 契约）
        when(adminService.create(any(CreateCommand.class)))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.PROMPT_TEMPLATE_INVALID,
                                "缺少 ---USER--- 分段标记; system 段须含 json 字样（JSON 输出模式前提）"));

        // Act + Assert
        mockMvc.perform(
                        post("/api/v1/prompt-templates")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"briefType": 1, "template": "---SYSTEM---\\n你是分析师。"}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30067))
                .andExpect(
                        jsonPath("$.msg")
                                .value("缺少 ---USER--- 分段标记; system 段须含 json 字样（JSON 输出模式前提）"));
    }

    @Test
    void create_unconfirmedRemoval_returns409With30068AndDataLists() throws Exception {
        // Arrange：服务端重算 diff 发现未确认移除
        PromptTemplateValidator.ValidationResult validation =
                PromptTemplateValidator.validate(
                        "---SYSTEM---\njson\n---USER---\n{{subjectName}} {{price}}",
                        "---SYSTEM---\njson\n---USER---\n{{subjectName}}({{subjectCode}}) {{price}}",
                        List.of(
                                new PlaceholderDescriptor("subjectName", "标的名称"),
                                new PlaceholderDescriptor("subjectCode", "标的代码")));
        when(adminService.create(any(CreateCommand.class)))
                .thenThrow(
                        new PromptTemplateAdminService.RemovalConfirmationRequiredException(
                                validation));

        // Act + Assert：409 + data 结构（removed 拦截清单 + unknown 随单展示）
        mockMvc.perform(
                        post("/api/v1/prompt-templates")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"briefType": 1, "template": "---SYSTEM---\\njson\\n---USER---\\n{{subjectName}}"}
                                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(30068))
                .andExpect(jsonPath("$.msg").value("存在待确认的占位符移除，请逐项确认后重试"))
                .andExpect(jsonPath("$.data.removed[0].key").value("subjectCode"))
                .andExpect(jsonPath("$.data.removed[0].description").value("标的代码"))
                // unknown（price 不在注册表）仅随单展示：description 恒 null
                .andExpect(jsonPath("$.data.unknown[0].key").value("price"))
                .andExpect(jsonPath("$.data.unknown[0].description").value(nullValue()));
    }

    @Test
    void create_confirmedRemovedKeysForwarded_caseInsensitiveStrategy() throws Exception {
        // Arrange
        when(adminService.create(any(CreateCommand.class)))
                .thenReturn(
                        new PromptTemplateAdminService.CreateResult(
                                11L, 1, "v1.4", "ACTIVE", "v1.3", 2, List.of()));

        // Act：大写策略 + 确认数组透传
        mockMvc.perform(
                        post("/api/v1/prompt-templates")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"briefType": 1,
                                         "template": "---SYSTEM---\\njson\\n---USER---\\nu",
                                         "versionStrategy": "major",
                                         "confirmedRemovedKeys": ["subjectCode", "price"]}
                                        """))
                .andExpect(status().isCreated());

        verify(adminService)
                .create(
                        argThat(
                                cmd ->
                                        cmd.strategy() == VersionStrategy.MAJOR
                                                && cmd.confirmedRemovedKeys()
                                                        .equals(Set.of("subjectCode", "price"))));
    }

    @Test
    void create_invalidParams_return400() throws Exception {
        // 缺 briefType
        mockMvc.perform(
                        post("/api/v1/prompt-templates")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"template\": \"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        // 非法 briefType
        mockMvc.perform(
                        post("/api/v1/prompt-templates")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"briefType\": 9, \"template\": \"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        // 非法 versionStrategy
        mockMvc.perform(
                        post("/api/v1/prompt-templates")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"briefType\": 1, \"template\": \"x\", \"versionStrategy\": \"PATCH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void activate_returns200WithDeactivatedVersion() throws Exception {
        // Arrange
        when(adminService.activate(1L))
                .thenReturn(
                        new PromptTemplateAdminService.ActivateResult(
                                1L, 4, "v1.0", "ACTIVE", "v1.1"));

        // Act + Assert
        mockMvc.perform(post("/api/v1/prompt-templates/1/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.version").value("v1.0"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.deactivatedVersion").value("v1.1"));
    }

    @Test
    void delete_retired_returns200() throws Exception {
        when(adminService.delete(1L))
                .thenReturn(new PromptTemplateAdminService.DeleteResult(1L, "v1.0", true));

        mockMvc.perform(delete("/api/v1/prompt-templates/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.version").value("v1.0"))
                .andExpect(jsonPath("$.data.deleted").value(true));
    }

    @Test
    void delete_activeVersion_returns409With30069() throws Exception {
        when(adminService.delete(2L))
                .thenThrow(
                        new BusinessException(ErrorCode.PROMPT_TEMPLATE_ACTIVE_DELETE_FORBIDDEN));

        mockMvc.perform(delete("/api/v1/prompt-templates/2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(30069))
                .andExpect(jsonPath("$.msg").value("激活版本不可删除，请先切换激活到其他版本"));
    }

    @Test
    void create_versionConflict_returns409With30070() throws Exception {
        when(adminService.create(any(CreateCommand.class)))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.PROMPT_TEMPLATE_VERSION_CONFLICT,
                                "版本号冲突: briefType=1 version=v1.3（可能被并发创建，请刷新列表后重试）"));

        mockMvc.perform(
                        post("/api/v1/prompt-templates")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"briefType\": 1, \"template\": \"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(30070));
    }

    @Test
    void placeholders_returnsFourScenariosWithDormantFlagOnEventAttribution() throws Exception {
        // Arrange：注册表按场景返回描述符清单
        for (BriefType type : BriefType.values()) {
            when(placeholderRegistry.byBriefType(type))
                    .thenReturn(List.of(new PlaceholderDescriptor("subjectName", "标的名称")));
        }

        // Act + Assert：4 场景按 briefType 升序；场景 2 dormant=true 带 note，其余无 note 字段
        mockMvc.perform(get("/api/v1/prompt-placeholders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.scenarios.length()").value(4))
                .andExpect(jsonPath("$.data.scenarios[0].briefType").value(1))
                .andExpect(jsonPath("$.data.scenarios[0].name").value("个股简报"))
                .andExpect(jsonPath("$.data.scenarios[0].dormant").value(false))
                .andExpect(jsonPath("$.data.scenarios[0].note").doesNotExist())
                .andExpect(jsonPath("$.data.scenarios[1].briefType").value(2))
                .andExpect(jsonPath("$.data.scenarios[1].dormant").value(true))
                .andExpect(jsonPath("$.data.scenarios[1].note").isNotEmpty())
                .andExpect(jsonPath("$.data.scenarios[3].briefType").value(4))
                .andExpect(jsonPath("$.data.scenarios[0].placeholders[0].key").value("subjectName"))
                .andExpect(
                        jsonPath("$.data.scenarios[0].placeholders[0].description").value("标的名称"));
    }

    @Test
    void placeholders_withBriefTypeFilter_returnsSingleScenario() throws Exception {
        // Arrange
        when(placeholderRegistry.byBriefType(BriefType.POLICY))
                .thenReturn(List.of(new PlaceholderDescriptor("policyTitle", "政策标题")));

        // Act + Assert
        mockMvc.perform(get("/api/v1/prompt-placeholders").param("briefType", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scenarios.length()").value(1))
                .andExpect(jsonPath("$.data.scenarios[0].briefType").value(3))
                .andExpect(jsonPath("$.data.scenarios[0].dormant").value(false));
    }

    @Test
    void placeholders_invalidBriefType_returns400ParamInvalid() throws Exception {
        // Act + Assert：未知场景码按参数校验口径 400/2001
        mockMvc.perform(get("/api/v1/prompt-placeholders").param("briefType", "9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void placeholders_noteTextDisclosesDormantScenario() {
        // Assert：休眠披露文案与方案 §4.4.5 一致（无生产触发入口 + 事件占位符无来源）
        assertThat(PromptTemplateController.DORMANT_EVENT_NOTE)
                .contains("无生产触发入口")
                .contains("eventTitle");
    }
}
