package com.info.platform.infrastructure.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.retention.RetentionConfigFacade;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * RetentionConfigFacadeImpl 单测（T72，方案 §4.3）：GET 视图组装（窗口/limits/updatedAt；键缺失 → 全默认 + null）/ PATCH
 * 全量文档拼装并委托 RuntimeConfigService.write（expectedUpdatedAt 透传、写后回读刷新）/ 缺字段不拼入（由校验器 2001 拦截）。AAA
 * 结构（mock RuntimeConfigService——校验/落库/换快照由其承担）。
 */
class RetentionConfigFacadeImplTest {

    private static final String KEY = "retention.global";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeConfigService configService = mock(RuntimeConfigService.class);
    private RetentionConfigFacade facade;

    @BeforeEach
    void setUp() {
        facade = new RetentionConfigFacadeImpl(configService, objectMapper);
    }

    private void stubEntry(String json, String updatedAt) {
        try {
            when(configService.read(KEY))
                    .thenReturn(
                            Optional.of(
                                    new RuntimeConfigEntry(
                                            KEY,
                                            json,
                                            objectMapper.readTree(json),
                                            null,
                                            Instant.parse(updatedAt))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void view_assemblesWindowsLimitsAndUpdatedAt() {
        // Arrange：自定义窗口（模拟用户已改过）
        stubEntry(
                "{\"jobExecutionLogDays\":10,\"dataSourceEventDays\":5,"
                        + "\"llmCallLogDays\":40,\"readingEventDays\":50,\"newsItemDays\":200}",
                "2026-09-22T01:00:00Z");

        // Act
        RetentionConfigFacade.WindowsView view = facade.view();

        // Assert：窗口逐字段回读 + limits（各表下限/默认，Dialog 校验提示数据源）+ updatedAt（下次防呆比对）
        assertThat(view.windows().jobExecutionLogDays()).isEqualTo(10);
        assertThat(view.windows().dataSourceEventDays()).isEqualTo(5);
        assertThat(view.windows().llmCallLogDays()).isEqualTo(40);
        assertThat(view.windows().readingEventDays()).isEqualTo(50);
        assertThat(view.windows().newsItemDays()).isEqualTo(200);
        assertThat(view.limits())
                .containsEntry("jobExecutionLogDays", new RetentionConfigFacade.FieldLimits(7, 30))
                .containsEntry("dataSourceEventDays", new RetentionConfigFacade.FieldLimits(2, 14))
                .containsEntry("llmCallLogDays", new RetentionConfigFacade.FieldLimits(35, 90))
                .containsEntry("readingEventDays", new RetentionConfigFacade.FieldLimits(35, 90))
                .containsEntry("newsItemDays", new RetentionConfigFacade.FieldLimits(30, 180));
        assertThat(view.updatedAt()).isEqualTo("2026-09-22T01:00:00Z");
    }

    @Test
    void view_keyMissing_defaultsAndNullUpdatedAt() {
        // Arrange：种子前/键被删 → GET 不炸：全默认窗口（读侧防御同 RetentionWindows）+ updatedAt null
        when(configService.read(KEY)).thenReturn(Optional.empty());

        // Act
        RetentionConfigFacade.WindowsView view = facade.view();

        // Assert
        assertThat(view.windows().jobExecutionLogDays()).isEqualTo(30);
        assertThat(view.windows().dataSourceEventDays()).isEqualTo(14);
        assertThat(view.windows().llmCallLogDays()).isEqualTo(90);
        assertThat(view.windows().readingEventDays()).isEqualTo(90);
        assertThat(view.windows().newsItemDays()).isEqualTo(180);
        assertThat(view.updatedAt()).isNull();
        // limits 恒全量（页面文案与前端校验兜底数据源；T113 起五字段）
        assertThat(view.limits()).hasSize(5);
    }

    @Test
    void view_illegalStoredValue_fallsBackPerField() {
        // Arrange：绕过校验器直写的坏值（0）→ 读侧字段级回退，好字段（5）照常采信
        stubEntry(
                "{\"jobExecutionLogDays\":0,\"dataSourceEventDays\":5,"
                        + "\"llmCallLogDays\":90,\"readingEventDays\":90}",
                "2026-09-22T01:00:00Z");

        // Act
        RetentionConfigFacade.WindowsView view = facade.view();

        // Assert：GET 展示的是执行口径（回退后的有效窗口），不回显坏值
        assertThat(view.windows().jobExecutionLogDays()).isEqualTo(30);
        assertThat(view.windows().dataSourceEventDays()).isEqualTo(5);
    }

    @Test
    void update_buildsFullDocument_delegatesWrite_returnsRefreshedView() {
        // Arrange：write 落库后回读新文档（含新 updatedAt）
        stubEntry(
                "{\"jobExecutionLogDays\":30,\"dataSourceEventDays\":14,"
                        + "\"llmCallLogDays\":90,\"readingEventDays\":90,\"newsItemDays\":180}",
                "2026-09-22T01:00:00Z");
        when(configService.write(
                        eq(KEY), any(String.class), eq(Instant.parse("2026-09-22T01:00:00Z"))))
                .thenAnswer(
                        inv -> {
                            stubEntry(
                                    "{\"jobExecutionLogDays\":7,\"dataSourceEventDays\":14,"
                                            + "\"llmCallLogDays\":35,\"readingEventDays\":35,\"newsItemDays\":30}",
                                    "2026-09-22T02:00:00Z");
                            return new RuntimeConfigEntry(
                                    KEY,
                                    (String) inv.getArgument(1),
                                    objectMapper.readTree((String) inv.getArgument(1)),
                                    null,
                                    Instant.parse("2026-09-22T02:00:00Z"));
                        });

        // Act：PATCH 五字段全量 + expectedUpdatedAt 防呆（D3 后窗口字段收 JsonNode，合法整数以 IntNode 透传）
        RetentionConfigFacade.WindowsView view =
                facade.update(
                        new RetentionConfigFacade.WindowsUpdate(
                                IntNode.valueOf(7),
                                IntNode.valueOf(14),
                                IntNode.valueOf(35),
                                IntNode.valueOf(35),
                                IntNode.valueOf(30),
                                "2026-09-22T01:00:00Z"));

        // Assert：写入文档五字段齐整；返回写后视图（新值 + 新 updatedAt）
        ArgumentCaptor<String> docCaptor = ArgumentCaptor.forClass(String.class);
        verify(configService)
                .write(eq(KEY), docCaptor.capture(), eq(Instant.parse("2026-09-22T01:00:00Z")));
        assertThat(docCaptor.getValue())
                .contains("\"jobExecutionLogDays\":7")
                .contains("\"dataSourceEventDays\":14")
                .contains("\"llmCallLogDays\":35")
                .contains("\"readingEventDays\":35")
                .contains("\"newsItemDays\":30");
        assertThat(view.windows().jobExecutionLogDays()).isEqualTo(7);
        assertThat(view.updatedAt()).isEqualTo("2026-09-22T02:00:00Z");
    }

    @Test
    void update_nullField_omittedFromDocument_validatorWillReject() {
        // Arrange：缺字段的 PATCH → 文档不拼入该字段（校验器 2001 必填拦截）；expectedUpdatedAt 缺省为 null（不比对）
        when(configService.write(eq(KEY), any(String.class), any()))
                .thenThrow(
                        new BusinessException(ErrorCode.PARAM_INVALID, "jobExecutionLogDays: 必填"));

        // Act + Assert：异常透传（2001 字段级，原值保留——DB 不动）
        assertThatThrownBy(
                        () ->
                                facade.update(
                                        new RetentionConfigFacade.WindowsUpdate(
                                                null,
                                                IntNode.valueOf(14),
                                                IntNode.valueOf(35),
                                                IntNode.valueOf(35),
                                                IntNode.valueOf(30),
                                                null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必填");
        verify(configService).write(eq(KEY), any(String.class), any());
    }

    @Test
    void update_invalidExpectedUpdatedAt_rejectedAs2001() {
        // Arrange：expectedUpdatedAt 非 ISO-8601 → 2001（对齐 JobCenterFacadeImpl.parseExpected 惯例）
        // Act + Assert
        assertThatThrownBy(
                        () ->
                                facade.update(
                                        new RetentionConfigFacade.WindowsUpdate(
                                                IntNode.valueOf(7),
                                                IntNode.valueOf(14),
                                                IntNode.valueOf(35),
                                                IntNode.valueOf(35),
                                                IntNode.valueOf(30),
                                                "not-a-time")))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.PARAM_INVALID))
                .hasMessageContaining("expectedUpdatedAt");
    }

    @Test
    void update_concurrentConflict_propagates30065() {
        // Arrange：RuntimeConfigService 并发防呆（expectedUpdatedAt 不符 → 30065，原值保留）
        when(configService.write(eq(KEY), any(String.class), any()))
                .thenThrow(new BusinessException(ErrorCode.CONFIG_CONFLICT, "配置已被并发修改"));

        // Act + Assert：透传给接口层 → 409
        assertThatThrownBy(
                        () ->
                                facade.update(
                                        new RetentionConfigFacade.WindowsUpdate(
                                                IntNode.valueOf(7),
                                                IntNode.valueOf(14),
                                                IntNode.valueOf(35),
                                                IntNode.valueOf(35),
                                                IntNode.valueOf(30),
                                                "2026-09-22T00:00:00Z")))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.CONFIG_CONFLICT));
    }
}
