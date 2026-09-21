package com.info.platform.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.ai.PromptTemplateRepository;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PromptTemplateService 单测（T20）：加载启用模板 + 渲染占位符，AAA 结构。
 *
 * <p>mock {@link PromptTemplateRepository}；覆盖 {@code loadActiveTemplate}（命中/未命中/null 入参）与 {@code
 * render}（替换已知键/保留未知键/保留单花括号 JSON 格式/null context/分段标记缺失）。 占位符约定：双花括号 {@code {{key}}} 替换，user 段单花括号
 * JSON 形状 {@code {summary,...}} 不受影响。
 */
class PromptTemplateServiceTest {

    private PromptTemplateRepository repository;
    private PromptTemplateService service;

    /** 测试用模板：含分段标记 + 双花括号占位符 + 单花括号 JSON 输出格式约束。 */
    private static final String RAW_TEMPLATE =
            "---SYSTEM---\n你是分析师。生成结构化 json 简报。\n---USER---\n"
                    + "标的：{{subjectName}}({{subjectCode}})\n当前价：{{price}}\n"
                    + "请输出 json：{summary, keyEvents[], bias}";

    @BeforeEach
    void setUp() {
        repository = mock(PromptTemplateRepository.class);
        service = new PromptTemplateService(repository);
    }

    @Test
    void loadActiveTemplate_found_returnsTemplate() {
        // Arrange
        PromptTemplate template = templateOf(BriefType.STOCK, "v1.0");
        when(repository.findActiveByBriefType(BriefType.STOCK)).thenReturn(Optional.of(template));

        // Act
        PromptTemplate loaded = service.loadActiveTemplate(BriefType.STOCK);

        // Assert
        assertThat(loaded).isSameAs(template);
        verify(repository).findActiveByBriefType(BriefType.STOCK);
    }

    @Test
    void loadActiveTemplate_notFound_throwsBusinessException() {
        // Arrange
        when(repository.findActiveByBriefType(BriefType.POLICY)).thenReturn(Optional.empty());

        // Act + Assert：未配置启用模板 → PROMPT_TEMPLATE_NOT_FOUND（配置缺失，500 语义）
        assertThatThrownBy(() -> service.loadActiveTemplate(BriefType.POLICY))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            ErrorCode code = ((BusinessException) ex).getErrorCode();
                            assertThat(code).isEqualTo(ErrorCode.PROMPT_TEMPLATE_NOT_FOUND);
                            assertThat(code.getHttpStatus()).isEqualTo(500);
                        });
    }

    @Test
    void loadActiveTemplate_nullBriefType_throwsNpe() {
        // Act + Assert：null 入参防御（Objects.requireNonNull）
        assertThatThrownBy(() -> service.loadActiveTemplate(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void render_replacesKnownKeysAndPreservesUnknownAndSingleBraceJson() {
        // Arrange：context 缺 price → 保留 {{price}}；单花括号 JSON 形状不替换
        PromptTemplate template = templateOf(BriefType.STOCK, "v1.0");
        Map<String, String> context = Map.of("subjectName", "贵州茅台", "subjectCode", "SH600519");

        // Act
        List<ChatMessage> messages = service.render(template, context);

        // Assert：两条消息，system 在前含 "json"，user 替换已知键、保留未知占位符与单花括号 JSON
        assertThat(messages).hasSize(2);
        ChatMessage system = messages.get(0);
        ChatMessage user = messages.get(1);
        assertThat(system.role()).isEqualTo("system");
        assertThat(system.content()).contains("json");
        assertThat(user.role()).isEqualTo("user");
        assertThat(user.content()).contains("标的：贵州茅台(SH600519)");
        assertThat(user.content()).contains("当前价：{{price}}");
        assertThat(user.content()).contains("{summary, keyEvents[], bias}");
    }

    @Test
    void render_allKeysProvided_noLeftoverPlaceholders() {
        // Arrange：全部键提供 → 无残留 {{...}}
        PromptTemplate template = templateOf(BriefType.STOCK, "v1.0");
        Map<String, String> context =
                Map.of(
                        "subjectName", "贵州茅台",
                        "subjectCode", "SH600519",
                        "price", "1680.50");

        // Act
        List<ChatMessage> messages = service.render(template, context);

        // Assert
        String user = messages.get(1).content();
        assertThat(user).contains("当前价：1680.50");
        assertThat(user).doesNotContain("{{");
    }

    @Test
    void render_nullContext_keepsAllPlaceholders() {
        // Arrange：null context → 全部占位符保留（缺失可见而非静默置空）
        PromptTemplate template = templateOf(BriefType.STOCK, "v1.0");

        // Act
        List<ChatMessage> messages = service.render(template, null);

        // Assert
        assertThat(messages.get(1).content()).contains("{{subjectName}}").contains("{{price}}");
    }

    @Test
    void render_nullTemplate_throwsNpe() {
        // Act + Assert
        assertThatThrownBy(() -> service.render(null, Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void render_malformedTemplateMissingMarkers_throwsIllegalState() {
        // Arrange：缺分段标记（数据损坏）
        PromptTemplate broken =
                PromptTemplate.reconstruct(
                        1L, BriefType.STOCK, "v1.0", "没有标记的模板 {{subjectName}}", 1);

        // Act + Assert
        assertThatThrownBy(() -> service.render(broken, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("---SYSTEM---");
    }

    @Test
    void render_markerOrderReversed_throwsIllegalState() {
        // Arrange：USER 在 SYSTEM 之前（顺序错）
        PromptTemplate reversed =
                PromptTemplate.reconstruct(
                        1L, BriefType.STOCK, "v1.0", "---USER---\nu\n---SYSTEM---\ns", 1);

        // Act + Assert
        assertThatThrownBy(() -> service.render(reversed, Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static PromptTemplate templateOf(BriefType briefType, String version) {
        return PromptTemplate.reconstruct(1L, briefType, version, RAW_TEMPLATE, 1);
    }
}
