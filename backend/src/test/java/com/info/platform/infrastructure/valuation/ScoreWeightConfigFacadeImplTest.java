package com.info.platform.infrastructure.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.common.RuntimeConfigValidator;
import com.info.platform.application.valuation.ScoreWeightConfigFacade.WeightsUpdate;
import com.info.platform.application.valuation.ScoreWeightConfigFacade.WeightsView;
import com.info.platform.application.valuation.ValuationConfigValidator;
import com.info.platform.application.valuation.ValuationSettings;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.RuntimeConfig;
import com.info.platform.domain.common.RuntimeConfigRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * ScoreWeightConfigFacadeImpl 单测（T172，方案 §4.7.3）：视图解析（存量键/键缺失）、PATCH 全量文档拼装与 expectedUpdatedAt
 * 透传、非法时间戳 2001；热生效链（真实 RuntimeConfigService + 内存仓储 + 校验器）：合法 PATCH → ValuationSettings 现读新参数 与新
 * basis，非法 PATCH → 30087 原值保留——「下一轮快照按新参数」的单元级证明（端到端重算挂 T176 验收）。
 */
class ScoreWeightConfigFacadeImplTest {

    private static final Instant T0 = Instant.parse("2026-09-22T01:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-22T09:30:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RuntimeConfigService configService;
    private ValuationSettings valuationSettings;
    private ScoreWeightConfigFacadeImpl facade;

    @BeforeEach
    void setUp() {
        configService = mock(RuntimeConfigService.class);
        valuationSettings = new ValuationSettings(configService, objectMapper);
        facade = new ScoreWeightConfigFacadeImpl(configService, valuationSettings, objectMapper);
    }

    private void stubEntry(String json, Instant updatedAt) {
        when(configService.read(anyString()))
                .thenReturn(
                        Optional.of(
                                new RuntimeConfigEntry(
                                        "score.weight", json, null, null, updatedAt)));
    }

    /** 存量合法覆写文档（五维 0.50|0.10|0.20|0.20|0.00 + 阈值 65|45|75）。 */
    private static String storedJson() {
        return "{\"wCatalyst\":0.5,\"wConduction\":0.1,\"wFundamental\":0.2,\"wRisk\":0.2,"
                + "\"wValuation\":0.0,\"catalystWindowDays\":15,\"assocWindowDays\":45,"
                + "\"halfLifeDays\":7.0,\"k1Saturation\":4.0,\"k3Saturation\":2.0,"
                + "\"btCatalystMin\":65,\"btConductionMin\":45,\"btRiskMin\":75}";
    }

    @Test
    void view_storedEntry_resolvesParamsAndDerivedBasis() {
        stubEntry(storedJson(), T0);

        WeightsView view = facade.view();

        assertThat(view.wCatalyst()).isEqualTo(0.5);
        assertThat(view.assocWindowDays()).isEqualTo(45);
        assertThat(view.btRiskMin()).isEqualTo(75);
        assertThat(view.basis())
                .isEqualTo(
                        "vs-v1:w=0.50|0.10|0.20|0.20|0.00;win=15|45;hl=7.0;k=4.0|2.0;bt=65|45|75");
        assertThat(view.updatedAt()).isEqualTo("2026-09-22T01:00:00Z");
    }

    @Test
    void view_keyMissing_returnsCodeDefaultsWithNullUpdatedAt() {
        when(configService.read(anyString())).thenReturn(Optional.empty());

        WeightsView view = facade.view();

        assertThat(view.wCatalyst()).isEqualTo(0.40);
        assertThat(view.wValuation()).isEqualTo(0.00);
        assertThat(view.btCatalystMin()).isEqualTo(60);
        assertThat(view.basis()).isEqualTo(ValuationSettingsDefaults.EXPECTED_BASIS);
        assertThat(view.updatedAt()).isNull();
    }

    @Test
    void update_buildsFullDocumentAndWritesWithParsedExpectedUpdatedAt() throws Exception {
        stubEntry(storedJson(), T0);
        when(configService.write(anyString(), anyString(), any(Instant.class)))
                .thenAnswer(
                        inv ->
                                new RuntimeConfigEntry(
                                        "score.weight", storedJson(), null, null, T1));

        facade.update(
                new WeightsUpdate(
                        node("0.6"),
                        node("0.1"),
                        node("0.1"),
                        node("0.1"),
                        node("0.1"),
                        node("20"),
                        node("40"),
                        node("6.0"),
                        node("3.5"),
                        node("1.5"),
                        node("70"),
                        node("50"),
                        node("85"),
                        "2026-09-22T01:00:00Z"));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(configService).write(eq("score.weight"), jsonCaptor.capture(), eq(T0));
        com.fasterxml.jackson.databind.JsonNode written =
                objectMapper.readTree(jsonCaptor.getValue());
        assertThat(written.get("wCatalyst").asDouble()).isEqualTo(0.6);
        assertThat(written.get("wValuation").asDouble()).isEqualTo(0.1);
        assertThat(written.get("catalystWindowDays").asInt()).isEqualTo(20);
        assertThat(written.get("halfLifeDays").asDouble()).isEqualTo(6.0);
        assertThat(written.get("btRiskMin").asInt()).isEqualTo(85);
        assertThat(written.has("expectedUpdatedAt")).isFalse(); // 防呆字段不入文档
        assertThat(written.has("basis")).isFalse(); // basis 代码派生不落文档
    }

    @Test
    void update_returnsRefreshedViewAfterWrite() {
        stubEntry(storedJson(), T1);
        when(configService.write(anyString(), anyString(), any())).thenReturn(null);

        WeightsView view = facade.update(fullUpdate());

        assertThat(view.wCatalyst()).isEqualTo(0.5);
        assertThat(view.updatedAt()).isEqualTo("2026-09-22T09:30:00Z");
    }

    @Test
    void update_missingField_notPutIntoDocument_validatorRequiredReports() {
        // wRisk 缺失 → 文档不拼入该键（校验器「必填」拦截在 write 内发生，此处验透传语义）
        facade.update(
                new WeightsUpdate(
                        node("0.5"),
                        node("0.2"),
                        node("0.1"),
                        null,
                        node("0.2"),
                        node("10"),
                        node("30"),
                        node("5.0"),
                        node("3.0"),
                        node("1.5"),
                        node("60"),
                        node("50"),
                        node("80"),
                        null));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(configService).write(anyString(), jsonCaptor.capture(), any());
        assertThat(jsonCaptor.getValue()).doesNotContain("\"wRisk\"");
    }

    @Test
    void update_invalidExpectedUpdatedAtFormat_2001() {
        WeightsUpdate badExpected =
                new WeightsUpdate(
                        node("0.5"),
                        node("0.2"),
                        node("0.1"),
                        node("0.1"),
                        node("0.1"),
                        node("10"),
                        node("30"),
                        node("5.0"),
                        node("3.0"),
                        node("1.5"),
                        node("60"),
                        node("50"),
                        node("80"),
                        "2026-09-22 01:00:00");
        assertThatThrownBy(() -> facade.update(badExpected))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.PARAM_INVALID))
                .hasMessageContaining("expectedUpdatedAt");
    }

    @Test
    void update_withBlankExpected_writesNullInstant() {
        stubEntry(storedJson(), T1);
        when(configService.write(anyString(), anyString(), any())).thenReturn(null);
        WeightsUpdate update =
                new WeightsUpdate(
                        node("0.5"),
                        node("0.2"),
                        node("0.1"),
                        node("0.1"),
                        node("0.1"),
                        node("10"),
                        node("30"),
                        node("5.0"),
                        node("3.0"),
                        node("1.5"),
                        node("60"),
                        node("50"),
                        node("80"),
                        " ");
        facade.update(update);
        verify(configService).write(anyString(), anyString(), eq(null));
    }

    // ---- 热生效链（真实 RuntimeConfigService + 内存仓储 + 校验器） ----

    @Test
    void hotEffect_validPatch_settingsSeeNewParamsAndBasisNextRound() {
        RuntimeConfigService realService = realService();
        ValuationSettings settings = new ValuationSettings(realService, objectMapper);
        ScoreWeightConfigFacadeImpl realFacade =
                new ScoreWeightConfigFacadeImpl(realService, settings, objectMapper);
        String before = settings.params().basis();

        WeightsView view = realFacade.update(fullUpdate());

        assertThat(view.wCatalyst()).isEqualTo(0.5);
        assertThat(settings.params().wCatalyst()).isEqualTo(0.5); // 现读即新参数（快照热替换）
        assertThat(settings.params().basis())
                .isEqualTo(
                        "vs-v1:w=0.50|0.20|0.10|0.10|0.10;win=10|30;hl=5.0;k=3.0|1.5;bt=60|50|80")
                .isNotEqualTo(before);
        // 固定时钟 T0 首写：updatedAt = 写入时刻（内存仓储真实走 RuntimeConfigService 换快照路径）
        assertThat(view.updatedAt()).isEqualTo("2026-09-22T01:00:00Z");
    }

    @Test
    void hotEffect_illegalPatch_rejected30087AndOldValueKept() {
        RuntimeConfigService realService = realService();
        ValuationSettings settings = new ValuationSettings(realService, objectMapper);
        ScoreWeightConfigFacadeImpl realFacade =
                new ScoreWeightConfigFacadeImpl(realService, settings, objectMapper);
        String before = settings.params().basis();

        // wValuation=1.5 越界 + btRiskMin=120 越界 → 30087 字段级，原值保留
        WeightsUpdate illegal =
                new WeightsUpdate(
                        node("0.4"),
                        node("0.2"),
                        node("0.2"),
                        node("0.2"),
                        node("1.5"),
                        node("10"),
                        node("30"),
                        node("5.0"),
                        node("3.0"),
                        node("1.5"),
                        node("60"),
                        node("50"),
                        node("120"),
                        null);
        assertThatThrownBy(() -> realFacade.update(illegal))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.VALUATION_CONFIG_INVALID))
                .hasMessageContaining("wValuation")
                .hasMessageContaining("btRiskMin");
        assertThat(settings.params().basis()).isEqualTo(before); // 旧值继续生效
    }

    // ---- 夹具 ----

    private com.fasterxml.jackson.databind.JsonNode node(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("测试节点解析失败: " + raw, e);
        }
    }

    /** 合法全量 PATCH（五维 0.50|0.20|0.10|0.10|0.10）。 */
    private WeightsUpdate fullUpdate() {
        return new WeightsUpdate(
                node("0.5"),
                node("0.2"),
                node("0.1"),
                node("0.1"),
                node("0.1"),
                node("10"),
                node("30"),
                node("5.0"),
                node("3.0"),
                node("1.5"),
                node("60"),
                node("50"),
                node("80"),
                null);
    }

    /** 真实配置中心服务（内存仓储 + 本批校验器 + 固定时钟）。 */
    private RuntimeConfigService realService() {
        RuntimeConfigRepository repository =
                new RuntimeConfigRepository() {
                    private RuntimeConfig row;

                    @Override
                    public Optional<RuntimeConfig> findByKey(String configKey) {
                        return Optional.ofNullable(row);
                    }

                    @Override
                    public List<RuntimeConfig> findAll() {
                        return row == null ? List.of() : List.of(row);
                    }

                    @Override
                    public RuntimeConfig save(RuntimeConfig config) {
                        row =
                                row == null
                                        ? config
                                        : RuntimeConfig.reconstruct(
                                                config.getConfigKey(),
                                                config.getConfigValue(),
                                                config.getDescription(),
                                                T0,
                                                T1);
                        return row;
                    }
                };
        RuntimeConfigValidator validator = new ValuationConfigValidator();
        ApplicationEventPublisher publisher = event -> {};
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        return new RuntimeConfigService(
                repository, List.of(validator), publisher, clock, objectMapper);
    }

    /** 缺省 basis 期望串（与 ValuationParams.defaults().basis() 同源冻结）。 */
    private static final class ValuationSettingsDefaults {
        static final String EXPECTED_BASIS =
                "vs-v1:w=0.40|0.20|0.20|0.20|0.00;win=10|30;hl=5.0;k=3.0|1.5;bt=60|50|80";
    }
}
