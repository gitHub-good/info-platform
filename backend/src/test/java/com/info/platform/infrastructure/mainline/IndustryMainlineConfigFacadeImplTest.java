package com.info.platform.infrastructure.mainline;

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
import com.info.platform.application.mainline.IndustryMainlineConfigFacade.ConfigUpdate;
import com.info.platform.application.mainline.IndustryMainlineConfigFacade.ConfigView;
import com.info.platform.application.mainline.IndustryMainlineSettings;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * IndustryMainlineConfigFacadeImpl 单测（M27 T243 配置面写路径，MarketTopConfigFacadeImplTest 同款）：读视图（键缺失 =
 * 缺省 + null updatedAt）/ 写拼全量两键文档委托 RuntimeConfigService（校验 30096 在写路径，expectedUpdatedAt 透传）/ 非法时刻
 * 2001。
 */
class IndustryMainlineConfigFacadeImplTest {

    private static final String MAINLINE_KEY = "industry.mainline";

    private static final String LEADER_KEY = "industry.leader";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RuntimeConfigService configService;

    private IndustryMainlineConfigFacadeImpl facade;

    @BeforeEach
    void setUp() {
        configService = mock(RuntimeConfigService.class);
        facade =
                new IndustryMainlineConfigFacadeImpl(
                        configService, new IndustryMainlineSettings(configService), objectMapper);
    }

    private void stubEntry(String key, String json) throws Exception {
        when(configService.read(key))
                .thenReturn(
                        Optional.of(
                                new RuntimeConfigEntry(
                                        key,
                                        json,
                                        objectMapper.readTree(json),
                                        null,
                                        Instant.parse("2026-09-28T01:00:00Z"))));
    }

    @Test
    void view_keyMissing_defaultsAndNullUpdatedAt() {
        when(configService.read(anyString())).thenReturn(Optional.empty());

        ConfigView view = facade.view();

        assertThat(view.mainline().wp()).isEqualTo(0.40);
        assertThat(view.mainline().topN()).isEqualTo(5);
        assertThat(view.mainline().updatedAt()).isNull();
        assertThat(view.leader().wa()).isEqualTo(0.50);
        assertThat(view.leader().topN()).isEqualTo(3);
        assertThat(view.leader().updatedAt()).isNull();
    }

    @Test
    void view_storedDocument_reflected() throws Exception {
        stubEntry(
                MAINLINE_KEY,
                "{\"wp\":0.5,\"wh\":0.3,\"we\":0.2,\"priceWinDay\":0.6,\"priceWinD5\":0.4,"
                        + "\"heatH24\":0.4,\"heatD7\":0.4,\"heatDelta\":0.2,\"topN\":3,"
                        + "\"persistMinDays\":3,\"persistWindowDays\":5,\"topThirdRank\":10,"
                        + "\"divergenceHeatRank\":13}");
        stubEntry(
                LEADER_KEY,
                "{\"wa\":0.6,\"wv\":0.3,\"wq\":0.1,\"mentionDays\":14,\"topN\":5,"
                        + "\"qDay\":0.7,\"qD5\":0.3}");

        ConfigView view = facade.view();

        assertThat(view.mainline().wp()).isEqualTo(0.5);
        assertThat(view.mainline().topN()).isEqualTo(3);
        assertThat(view.mainline().persistMinDays()).isEqualTo(3);
        assertThat(view.mainline().updatedAt()).isEqualTo("2026-09-28T01:00:00Z");
        assertThat(view.leader().mentionDays()).isEqualTo(14);
        assertThat(view.leader().topN()).isEqualTo(5);
    }

    @Test
    void update_buildsFullDocuments_delegatesTwoWrites() throws Exception {
        stubEntry(MAINLINE_KEY, "{}");
        stubEntry(LEADER_KEY, "{}");
        when(configService.write(anyString(), anyString(), any()))
                .thenAnswer(
                        inv ->
                                new RuntimeConfigEntry(
                                        inv.getArgument(0),
                                        inv.getArgument(1),
                                        objectMapper.readTree((String) inv.getArgument(1)),
                                        null,
                                        Instant.parse("2026-09-28T02:00:00Z")));

        ConfigView view =
                facade.update(
                        new ConfigUpdate(
                                objectMapper.readTree("0.45"), // wp
                                objectMapper.readTree("0.35"),
                                objectMapper.readTree("0.20"),
                                objectMapper.readTree("0.5"),
                                objectMapper.readTree("0.5"),
                                objectMapper.readTree("0.5"),
                                objectMapper.readTree("0.3"),
                                objectMapper.readTree("0.2"),
                                objectMapper.readTree("5"),
                                objectMapper.readTree("2"),
                                objectMapper.readTree("5"),
                                objectMapper.readTree("10"),
                                objectMapper.readTree("13"),
                                objectMapper.readTree("0.55"),
                                objectMapper.readTree("0.30"),
                                objectMapper.readTree("0.15"),
                                objectMapper.readTree("10"),
                                objectMapper.readTree("3"),
                                objectMapper.readTree("0.5"),
                                objectMapper.readTree("0.5"),
                                "2026-09-28T01:00:00Z"));

        ArgumentCaptor<String> mainlineDoc = ArgumentCaptor.forClass(String.class);
        verify(configService)
                .write(
                        eq(MAINLINE_KEY),
                        mainlineDoc.capture(),
                        eq(Instant.parse("2026-09-28T01:00:00Z")));
        assertThat(mainlineDoc.getValue()).contains("\"wp\":0.45").contains("\"topN\":5");
        ArgumentCaptor<String> leaderDoc = ArgumentCaptor.forClass(String.class);
        verify(configService).write(eq(LEADER_KEY), leaderDoc.capture(), any());
        assertThat(leaderDoc.getValue()).contains("\"wa\":0.55").contains("\"mentionDays\":10");
        assertThat(view.mainline().updatedAt()).isNotNull();
    }

    @Test
    void update_invalidExpectedUpdatedAt_rejectedAs2001() {
        assertThatThrownBy(
                        () ->
                                facade.update(
                                        new ConfigUpdate(
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                "not-a-time")))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.PARAM_INVALID))
                .hasMessageContaining("expectedUpdatedAt");
    }
}
