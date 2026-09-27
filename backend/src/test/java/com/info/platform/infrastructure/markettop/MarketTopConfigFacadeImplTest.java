package com.info.platform.infrastructure.markettop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.markettop.MarketTopConfigFacade.ConfigUpdate;
import com.info.platform.application.markettop.MarketTopConfigFacade.ConfigView;
import com.info.platform.application.markettop.MarketTopConfigSettings;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * MarketTopConfigFacadeImpl 单测（M21 T181，ScoreWeightConfigFacadeImpl 同款）：GET 视图（键缺失 = 代码缺省 + null）/
 * PATCH 全量拼文档 委托 RuntimeConfigService（30091 + 30065 透传）/ expectedUpdatedAt 非法 2001 / 写后回读刷新视图。
 */
class MarketTopConfigFacadeImplTest {

    private static final String KEY = "market.top";

    private final RuntimeConfigService configService = mock(RuntimeConfigService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private MarketTopConfigFacadeImpl facade;

    @BeforeEach
    void setUp() {
        when(configService.read(KEY)).thenReturn(Optional.empty());
        facade =
                new MarketTopConfigFacadeImpl(
                        configService, new MarketTopConfigSettings(configService, mapper), mapper);
    }

    @Test
    void view_missingKey_codeDefaultsWithNullUpdatedAt() {
        ConfigView view = facade.view();

        assertThat(view.poolSize()).isEqualTo(300);
        assertThat(view.deepDiveLimit()).isEqualTo(40);
        assertThat(view.deepDiveCostCapRatio()).isEqualTo(0.30);
        assertThat(view.diveCostEstimateMicros()).isEqualTo(100_000L);
        assertThat(view.memberCoverageFloor()).isEqualTo(0.80);
        assertThat(view.updatedAt()).isNull();
    }

    @Test
    void view_existingKey_updatedAtCarried() {
        when(configService.read(KEY))
                .thenReturn(
                        Optional.of(
                                new RuntimeConfigEntry(
                                        KEY,
                                        "{}",
                                        mapper.createObjectNode(),
                                        null,
                                        Instant.parse("2026-09-22T01:00:00Z"))));

        assertThat(facade.view().updatedAt()).isEqualTo("2026-09-22T01:00:00Z");
    }

    @Test
    void update_composesFullDocument_delegatesWrite_refreshesView() throws Exception {
        // Act：PATCH 5 字段全量（合法数值以原节点透传）+ expectedUpdatedAt 防呆
        ConfigView updated =
                facade.update(
                        new ConfigUpdate(
                                IntNode.valueOf(500),
                                IntNode.valueOf(50),
                                DoubleNode.valueOf(0.5),
                                IntNode.valueOf(200000),
                                DoubleNode.valueOf(0.9),
                                "2026-09-22T01:00:00Z"));

        // Assert：写路径拼全量文档 + 乐观防呆透传
        ArgumentCaptor<String> docCaptor = ArgumentCaptor.forClass(String.class);
        verify(configService)
                .write(eq(KEY), docCaptor.capture(), eq(Instant.parse("2026-09-22T01:00:00Z")));
        String writtenDoc = docCaptor.getValue();
        assertThat(writtenDoc)
                .contains("\"poolSize\":500")
                .contains("\"deepDiveLimit\":50")
                .contains("\"deepDiveCostCapRatio\":0.5")
                .contains("\"diveCostEstimateMicros\":200000")
                .contains("\"memberCoverageFloor\":0.9");
        // 写后回读刷新视图（mock 的 RuntimeConfigService 快照不自动换——按写入文档回读断言新值生效）
        when(configService.read(KEY))
                .thenReturn(
                        Optional.of(
                                new RuntimeConfigEntry(
                                        KEY,
                                        writtenDoc,
                                        mapper.readTree(writtenDoc),
                                        null,
                                        Instant.parse("2026-09-22T02:00:00Z"))));
        ConfigView refreshed = facade.view();
        assertThat(refreshed.poolSize()).isEqualTo(500);
        assertThat(refreshed.deepDiveLimit()).isEqualTo(50);
        assertThat(refreshed.memberCoverageFloor()).isEqualTo(0.9);
        assertThat(updated).isNotNull();
    }

    @Test
    void update_nullField_omittedFromDocument_validatorWillReject() {
        // 缺字段的 PATCH → 文档不拼入（校验器 30091 必填拦截，不部分写）
        when(configService.write(eq(KEY), any(String.class), any()))
                .thenThrow(
                        new BusinessException(ErrorCode.MARKET_TOP_CONFIG_INVALID, "poolSize: 必填"));

        assertThatThrownBy(
                        () ->
                                facade.update(
                                        new ConfigUpdate(
                                                null,
                                                IntNode.valueOf(40),
                                                DoubleNode.valueOf(0.3),
                                                IntNode.valueOf(100000),
                                                DoubleNode.valueOf(0.8),
                                                null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必填");
        ArgumentCaptor<String> docCaptor = ArgumentCaptor.forClass(String.class);
        verify(configService).write(eq(KEY), docCaptor.capture(), any());
        assertThat(docCaptor.getValue()).doesNotContain("\"poolSize\"");
    }

    @Test
    void update_invalidExpectedUpdatedAt_rejectedAs2001() {
        assertThatThrownBy(
                        () ->
                                facade.update(
                                        new ConfigUpdate(
                                                IntNode.valueOf(300),
                                                IntNode.valueOf(40),
                                                DoubleNode.valueOf(0.3),
                                                IntNode.valueOf(100000),
                                                DoubleNode.valueOf(0.8),
                                                "not-a-time")))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.PARAM_INVALID))
                .hasMessageContaining("expectedUpdatedAt");
    }

    @Test
    void update_concurrentConflict_propagates30065() {
        when(configService.write(eq(KEY), any(String.class), any()))
                .thenThrow(new BusinessException(ErrorCode.CONFIG_CONFLICT, "配置已被并发修改"));

        assertThatThrownBy(
                        () ->
                                facade.update(
                                        new ConfigUpdate(
                                                IntNode.valueOf(300),
                                                IntNode.valueOf(40),
                                                DoubleNode.valueOf(0.3),
                                                IntNode.valueOf(100000),
                                                DoubleNode.valueOf(0.8),
                                                "2026-09-22T00:00:00Z")))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.CONFIG_CONFLICT));
    }
}
