package com.info.platform.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.application.ai.PromptTemplateAdminService.ActivateResult;
import com.info.platform.application.ai.PromptTemplateAdminService.CreateCommand;
import com.info.platform.application.ai.PromptTemplateAdminService.CreateResult;
import com.info.platform.application.ai.PromptTemplateAdminService.ListView;
import com.info.platform.application.ai.PromptTemplateValidator.PlaceholderItem;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.PlaceholderDescriptor;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.ai.PromptTemplateRepository;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * PromptTemplateAdminService 单测（T45，方案 §4.6）：列表分组/数值排序、详情、create 编排（校验→生成→先置废后插入 +
 * 30067/30068/30070 三类失败）、activate 幂等与切换、delete 守卫。mock 仓储与注册表（纯函数组件走真实实现）。
 */
class PromptTemplateAdminServiceTest {

    private static final String TEMPLATE_FULL =
            "---SYSTEM---\n你是分析师。生成结构化 json 简报。\n---USER---\n"
                    + "标的：{{subjectName}}({{subjectCode}}) {{price}}";

    private static final String TEMPLATE_DROP_SUBJECT_CODE =
            "---SYSTEM---\n你是分析师。生成结构化 json 简报。\n---USER---\n" + "标的：{{subjectName}} {{price}}";

    private PromptTemplateRepository repository;
    private PromptTemplateAdminService service;

    @BeforeEach
    void setUp() {
        repository = mock(PromptTemplateRepository.class);
        PromptPlaceholderRegistry registry = mock(PromptPlaceholderRegistry.class);
        when(registry.byBriefType(BriefType.STOCK))
                .thenReturn(
                        List.of(
                                new PlaceholderDescriptor("subjectName", "标的名称"),
                                new PlaceholderDescriptor("subjectCode", "标的代码"),
                                new PlaceholderDescriptor("price", "当前价")));
        service = new PromptTemplateAdminService(repository, registry);
    }

    @Test
    void list_groupsAllFourScenarios_sortsVersionsNumericallyDesc() {
        // Arrange：STOCK 多版本（含 v1.10 > v1.9 数值序场景）；EVENT 空场景；其余单版本
        when(repository.findAllByBriefType(BriefType.STOCK))
                .thenReturn(
                        List.of(
                                row(3L, BriefType.STOCK, "v1.9", 0),
                                row(1L, BriefType.STOCK, "v1.10", 1),
                                row(2L, BriefType.STOCK, "v1.2", 0),
                                row(4L, BriefType.STOCK, "v1.0", 0)));
        when(repository.findAllByBriefType(BriefType.EVENT_ATTRIBUTION)).thenReturn(List.of());
        when(repository.findAllByBriefType(BriefType.POLICY))
                .thenReturn(List.of(row(9L, BriefType.POLICY, "v1.0", 1)));
        when(repository.findAllByBriefType(BriefType.DAILY_RECOMMEND))
                .thenReturn(List.of(row(11L, BriefType.DAILY_RECOMMEND, "v1.1", 1)));

        // Act
        ListView view = service.list();

        // Assert：恒 4 组按 briefType 序；数值降序（v1.10 在 v1.9 前，字典序会错）；空场景组保留
        assertThat(view.groups()).hasSize(4);
        var stock = view.groups().get(0);
        assertThat(stock.name()).isEqualTo("个股简报");
        assertThat(stock.versions())
                .extracting(v -> v.version())
                .containsExactly("v1.10", "v1.9", "v1.2", "v1.0");
        assertThat(stock.activeVersionId()).isEqualTo(1L);
        assertThat(stock.activeCount()).isEqualTo(1);
        assertThat(stock.versions().get(0).status()).isEqualTo("ACTIVE");
        assertThat(stock.versions().get(1).status()).isEqualTo("RETIRED");
        assertThat(stock.versions().get(0).placeholderCount()).isEqualTo(3);
        var event = view.groups().get(1);
        assertThat(event.versions()).isEmpty();
        assertThat(event.activeCount()).isZero();
        assertThat(event.activeVersionId()).isNull();
    }

    @Test
    void detail_returnsTemplateWithSectionsAndPlaceholders() {
        // Arrange
        when(repository.findById(5L)).thenReturn(Optional.of(row(5L, BriefType.STOCK, "v1.2", 1)));

        // Act
        var detail = service.detail(5L);

        // Assert：全文 + sections 服务端预分（strip 后不含标记行）+ 占位符出现序去重
        assertThat(detail.id()).isEqualTo(5L);
        assertThat(detail.briefType()).isEqualTo(1);
        assertThat(detail.name()).isEqualTo("个股简报");
        assertThat(detail.status()).isEqualTo("ACTIVE");
        assertThat(detail.template()).isEqualTo(TEMPLATE_FULL);
        assertThat(detail.sections().system()).isEqualTo("你是分析师。生成结构化 json 简报。");
        assertThat(detail.sections().user())
                .isEqualTo("标的：{{subjectName}}({{subjectCode}}) {{price}}");
        assertThat(detail.placeholders()).containsExactly("subjectName", "subjectCode", "price");
    }

    @Test
    void detail_notFound_throws30066() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(404L))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.PROMPT_TEMPLATE_VERSION_NOT_FOUND));
    }

    @Test
    void create_happyPath_deactivatesOldThenInsertsNewAsActive() {
        // Arrange：既有 v1.0（激活）；底稿缺省回落激活版（键集一致 → 无移除）
        PromptTemplate active = row(1L, BriefType.STOCK, "v1.0", 1);
        when(repository.findActiveByBriefType(BriefType.STOCK)).thenReturn(Optional.of(active));
        when(repository.findAllByBriefType(BriefType.STOCK)).thenReturn(List.of(active));
        when(repository.deactivateActive(BriefType.STOCK)).thenReturn(1);
        when(repository.insert(any(PromptTemplate.class)))
                .thenAnswer(
                        inv -> {
                            PromptTemplate arg = inv.getArgument(0);
                            return PromptTemplate.reconstruct(
                                    10L,
                                    arg.getBriefType(),
                                    arg.getVersion(),
                                    arg.getTemplate(),
                                    1);
                        });

        // Act
        CreateResult result =
                service.create(
                        new CreateCommand(BriefType.STOCK, null, TEMPLATE_FULL, null, Set.of()));

        // Assert：v1.0 → v1.1；先置废后插入（事务序）；新行 status=1（保存即激活）；无警告
        assertThat(result.version()).isEqualTo("v1.1");
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.deactivatedVersion()).isEqualTo("v1.0");
        assertThat(result.placeholderCount()).isEqualTo(3);
        assertThat(result.warnings()).isEmpty();
        InOrder order = inOrder(repository);
        order.verify(repository).deactivateActive(BriefType.STOCK);
        ArgumentCaptor<PromptTemplate> inserted = ArgumentCaptor.forClass(PromptTemplate.class);
        order.verify(repository).insert(inserted.capture());
        assertThat(inserted.getValue().isActive()).isTrue();
        assertThat(inserted.getValue().getVersion()).isEqualTo("v1.1");
        assertThat(inserted.getValue().getTemplate()).isEqualTo(TEMPLATE_FULL);
    }

    @Test
    void create_majorStrategy_bumpsMajor() {
        // Arrange
        PromptTemplate active = row(1L, BriefType.STOCK, "v1.9", 1);
        when(repository.findActiveByBriefType(BriefType.STOCK)).thenReturn(Optional.of(active));
        when(repository.findAllByBriefType(BriefType.STOCK)).thenReturn(List.of(active));
        when(repository.insert(any(PromptTemplate.class)))
                .thenAnswer(
                        inv -> {
                            PromptTemplate arg = inv.getArgument(0);
                            return PromptTemplate.reconstruct(
                                    11L,
                                    arg.getBriefType(),
                                    arg.getVersion(),
                                    arg.getTemplate(),
                                    1);
                        });

        // Act + Assert：v1.9 → v2.0
        CreateResult result =
                service.create(
                        new CreateCommand(
                                BriefType.STOCK,
                                null,
                                TEMPLATE_FULL,
                                PromptVersionGenerator.VersionStrategy.MAJOR,
                                Set.of()));
        assertThat(result.version()).isEqualTo("v2.0");
    }

    @Test
    void create_hardInvalid_throws30067WithJoinedMessages_noWrite() {
        // Arrange：标记齐全但 system 无 json 且超长度上限（两条硬错误联播）
        String broken =
                "---SYSTEM---\n你是分析师。\n---USER---\nu "
                        + "a".repeat(PromptTemplateValidator.MAX_TEMPLATE_LENGTH);
        when(repository.findActiveByBriefType(BriefType.STOCK))
                .thenReturn(Optional.of(row(1L, BriefType.STOCK, "v1.0", 1)));

        // Act + Assert：msg 逐条联播；未触任何写
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new CreateCommand(
                                                BriefType.STOCK, null, broken, null, Set.of())))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            assertThat(((BusinessException) ex).getErrorCode())
                                    .isEqualTo(ErrorCode.PROMPT_TEMPLATE_INVALID);
                            assertThat(ex.getMessage())
                                    .contains("模板长度超过上限")
                                    .contains("system 段须含 json 字样");
                        });
        verify(repository, never()).insert(any(PromptTemplate.class));
        verify(repository, never()).deactivateActive(any(BriefType.class));
    }

    @Test
    void create_unconfirmedRemoval_throwsWithRemovedAndUnknownLists() {
        // Arrange：新模板删 subjectCode + 引入无来源键 foo
        String next = "---SYSTEM---\njson\n---USER---\n{{subjectName}} {{price}} {{foo}}";
        when(repository.findActiveByBriefType(BriefType.STOCK))
                .thenReturn(Optional.of(row(1L, BriefType.STOCK, "v1.0", 1)));

        // Act + Assert：30068 信号携带 removed（带注册表说明）+ unknown（description null）
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new CreateCommand(
                                                BriefType.STOCK, null, next, null, Set.of())))
                .isInstanceOf(PromptTemplateAdminService.RemovalConfirmationRequiredException.class)
                .satisfies(
                        ex -> {
                            var validation =
                                    ((PromptTemplateAdminService
                                                            .RemovalConfirmationRequiredException)
                                                    ex)
                                            .validation();
                            assertThat(validation.removed())
                                    .containsExactly(new PlaceholderItem("subjectCode", "标的代码"));
                            assertThat(validation.unknown())
                                    .containsExactly(new PlaceholderItem("foo", null));
                        });
        verify(repository, never()).insert(any(PromptTemplate.class));
    }

    @Test
    void create_removalFullyConfirmed_proceeds() {
        // Arrange：新模板删 subjectCode，请求带全量确认
        PromptTemplate active = row(1L, BriefType.STOCK, "v1.0", 1);
        when(repository.findActiveByBriefType(BriefType.STOCK)).thenReturn(Optional.of(active));
        when(repository.findAllByBriefType(BriefType.STOCK)).thenReturn(List.of(active));
        when(repository.insert(any(PromptTemplate.class)))
                .thenAnswer(
                        inv -> {
                            PromptTemplate arg = inv.getArgument(0);
                            return PromptTemplate.reconstruct(
                                    12L,
                                    arg.getBriefType(),
                                    arg.getVersion(),
                                    arg.getTemplate(),
                                    1);
                        });

        // Act + Assert：全确认放行；unknown 不参与放行
        CreateResult result =
                service.create(
                        new CreateCommand(
                                BriefType.STOCK,
                                null,
                                TEMPLATE_DROP_SUBJECT_CODE,
                                null,
                                Set.of("subjectCode")));
        assertThat(result.version()).isEqualTo("v1.1");
        assertThat(result.placeholderCount()).isEqualTo(2);
    }

    @Test
    void create_baseVersionIdValid_diffsAgainstThatDraftRow() {
        // Arrange：baseVersionId 指向置废旧版（含 subjectCode），当前激活版已删该键
        PromptTemplate retiredDraft = row(2L, BriefType.STOCK, "v1.1", 0);
        PromptTemplate active = row(1L, BriefType.STOCK, "v1.2", 1);
        when(repository.findById(2L)).thenReturn(Optional.of(retiredDraft));
        when(repository.findActiveByBriefType(BriefType.STOCK)).thenReturn(Optional.of(active));
        when(repository.findAllByBriefType(BriefType.STOCK))
                .thenReturn(List.of(active, retiredDraft));
        when(repository.insert(any(PromptTemplate.class)))
                .thenAnswer(
                        inv -> {
                            PromptTemplate arg = inv.getArgument(0);
                            return PromptTemplate.reconstruct(
                                    13L,
                                    arg.getBriefType(),
                                    arg.getVersion(),
                                    arg.getTemplate(),
                                    1);
                        });

        // Act：不确认移除 → 抛 30068 信号（证明 diff 基准 = 底稿行而非激活版）
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new CreateCommand(
                                                BriefType.STOCK,
                                                2L,
                                                TEMPLATE_DROP_SUBJECT_CODE,
                                                null,
                                                Set.of())))
                .isInstanceOf(PromptTemplateAdminService.RemovalConfirmationRequiredException.class)
                .satisfies(
                        ex ->
                                assertThat(
                                                ((PromptTemplateAdminService
                                                                        .RemovalConfirmationRequiredException)
                                                                ex)
                                                        .validation()
                                                        .removed())
                                        .extracting(PlaceholderItem::key)
                                        .containsExactly("subjectCode"));
    }

    @Test
    void create_baseVersionIdInvalidOrCrossType_fallsBackToActiveOrEmpty() {
        // Arrange：baseVersionId 不存在 / 属于其他场景 → 回落激活版
        PromptTemplate active = row(1L, BriefType.STOCK, "v1.0", 1);
        when(repository.findById(999L)).thenReturn(Optional.empty());
        when(repository.findById(7L)).thenReturn(Optional.of(row(7L, BriefType.POLICY, "v1.0", 1)));
        when(repository.findActiveByBriefType(BriefType.STOCK)).thenReturn(Optional.of(active));
        when(repository.findAllByBriefType(BriefType.STOCK)).thenReturn(List.of(active));
        when(repository.insert(any(PromptTemplate.class)))
                .thenAnswer(
                        inv -> {
                            PromptTemplate arg = inv.getArgument(0);
                            return PromptTemplate.reconstruct(
                                    14L,
                                    arg.getBriefType(),
                                    arg.getVersion(),
                                    arg.getTemplate(),
                                    1);
                        });

        // Act + Assert：两个无效 baseVersionId 均以激活版为基准（删 subjectCode 未确认 → 30068）
        for (Long invalidBase : new Long[] {999L, 7L}) {
            assertThatThrownBy(
                            () ->
                                    service.create(
                                            new CreateCommand(
                                                    BriefType.STOCK,
                                                    invalidBase,
                                                    TEMPLATE_DROP_SUBJECT_CODE,
                                                    null,
                                                    Set.of())))
                    .isInstanceOf(
                            PromptTemplateAdminService.RemovalConfirmationRequiredException.class);
        }
    }

    @Test
    void create_duplicateKeyOnInsert_throws30070() {
        // Arrange：UNIQUE 兜底（并发竞争模拟）——insert 抛 DuplicateKeyException
        PromptTemplate active = row(1L, BriefType.STOCK, "v1.0", 1);
        when(repository.findActiveByBriefType(BriefType.STOCK)).thenReturn(Optional.of(active));
        when(repository.findAllByBriefType(BriefType.STOCK)).thenReturn(List.of(active));
        when(repository.insert(any(PromptTemplate.class)))
                .thenThrow(
                        new org.springframework.dao.DuplicateKeyException(
                                "UNIQUE(brief_type, version) 冲突"));

        // Act + Assert：msg 注明冲突版本
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new CreateCommand(
                                                BriefType.STOCK,
                                                null,
                                                TEMPLATE_FULL,
                                                null,
                                                Set.of())))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            assertThat(((BusinessException) ex).getErrorCode())
                                    .isEqualTo(ErrorCode.PROMPT_TEMPLATE_VERSION_CONFLICT);
                            assertThat(ex.getMessage()).contains("v1.1");
                        });
    }

    @Test
    void activate_targetAlreadyActive_idempotentNoWrites() {
        // Arrange
        when(repository.findById(1L)).thenReturn(Optional.of(row(1L, BriefType.STOCK, "v1.1", 1)));

        // Act
        ActivateResult result = service.activate(1L);

        // Assert：幂等 200 语义（deactivatedVersion null）；不触任何写
        assertThat(result.version()).isEqualTo("v1.1");
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.deactivatedVersion()).isNull();
        verify(repository, never()).deactivateActive(any(BriefType.class));
        verify(repository, never()).updateStatus(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void activate_switch_deactivatesOthersThenActivatesTarget() {
        // Arrange：目标置废行 + 当前激活 v1.1
        when(repository.findById(2L)).thenReturn(Optional.of(row(2L, BriefType.STOCK, "v1.0", 0)));
        when(repository.findActiveByBriefType(BriefType.STOCK))
                .thenReturn(Optional.of(row(1L, BriefType.STOCK, "v1.1", 1)));

        // Act
        ActivateResult result = service.activate(2L);

        // Assert：先置废后激活（事务序）；回执带被顶下的版本
        assertThat(result.version()).isEqualTo("v1.0");
        assertThat(result.deactivatedVersion()).isEqualTo("v1.1");
        InOrder order = inOrder(repository);
        order.verify(repository).deactivateActive(BriefType.STOCK);
        order.verify(repository).updateStatus(2L, 1);
    }

    @Test
    void activate_notFound_throws30066() {
        when(repository.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.activate(404L))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.PROMPT_TEMPLATE_VERSION_NOT_FOUND));
    }

    @Test
    void delete_activeVersion_throws30069() {
        // Arrange
        when(repository.findById(1L)).thenReturn(Optional.of(row(1L, BriefType.STOCK, "v1.0", 1)));

        // Act + Assert：激活不可删（须先切换）；未触删除
        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(
                                                ErrorCode.PROMPT_TEMPLATE_ACTIVE_DELETE_FORBIDDEN));
        verify(repository, never()).deleteById(any());
    }

    @Test
    void delete_retiredVersion_deletesPhysically() {
        // Arrange
        when(repository.findById(2L)).thenReturn(Optional.of(row(2L, BriefType.STOCK, "v1.0", 0)));
        when(repository.deleteById(2L)).thenReturn(true);

        // Act + Assert
        var result = service.delete(2L);
        assertThat(result.id()).isEqualTo(2L);
        assertThat(result.version()).isEqualTo("v1.0");
        assertThat(result.deleted()).isTrue();
        verify(repository).deleteById(2L);
    }

    @Test
    void delete_notFound_throws30066() {
        when(repository.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(404L))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.PROMPT_TEMPLATE_VERSION_NOT_FOUND));
    }

    private static PromptTemplate row(Long id, BriefType briefType, String version, int status) {
        return PromptTemplate.reconstruct(id, briefType, version, TEMPLATE_FULL, status);
    }
}
