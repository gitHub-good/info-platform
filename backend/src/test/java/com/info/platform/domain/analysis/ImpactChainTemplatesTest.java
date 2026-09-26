package com.info.platform.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 事件→行业影响链模板矩阵单测（M17 T144，REQ-20260926-14 拍板五 / 故事 3 场景 3/4）：
 *
 * <ul>
 *   <li>首批 ≥10 类模板：8 具名事件枚举全覆盖 + 政策发布宏观三分（货币/财政/产业）
 *   <li>模板行业集 ⊆ 申万 31 白名单（零新增事实红线——容器不进链）
 *   <li>渲染输出：事件提取行业必含（方向=事件方向）、模板传导行业并入、NEUTRAL 全中性
 *   <li>宏观细分关键词判读（降准→货币 / 专项债→财政 / 其余→产业）
 * </ul>
 */
class ImpactChainTemplatesTest {

    private static final Instant EVENT_TIME = Instant.parse("2026-09-22T02:00:00Z");

    // ---- 模板覆盖面（场景 3：≥10 类全覆盖） ----

    @Test
    @DisplayName("模板键 ≥10 类：8 具名枚举 + 宏观三分")
    void templateKeys_atLeastTen_coverNamedEnumsAndMacro() {
        Set<String> keys = Set.copyOf(ImpactChainTemplates.templateKeys());

        assertThat(keys).hasSizeGreaterThanOrEqualTo(10);
        // 8 具名事件枚举（OTHER 兜底链第 12 类）
        for (EventType type : EventType.values()) {
            assertThat(keys).as("事件类型 %s 应有影响链模板", type).contains(type.name());
        }
        // 政策发布宏观三分（拍板五-4：货币/财政/产业）
        assertThat(keys)
                .contains(
                        ImpactChainTemplates.KEY_POLICY_MONETARY,
                        ImpactChainTemplates.KEY_POLICY_FISCAL,
                        ImpactChainTemplates.KEY_POLICY_INDUSTRIAL);
    }

    @Test
    @DisplayName("全部模板行业 ⊆ 申万 31 白名单")
    void allTemplateIndustries_withinSwWhitelist() {
        for (String key : ImpactChainTemplates.templateKeys()) {
            for (String industry : ImpactChainTemplates.templateIndustries(key)) {
                assertThat(IndustryCategory.isSwIndustry(industry))
                        .as("模板 %s 行业 %s 须为申白名单枚举", key, industry)
                        .isTrue();
            }
        }
    }

    // ---- 宏观细分判读 ----

    @Test
    @DisplayName("政策发布：降准→货币 / 专项债→财政 / 产业规划→产业")
    void macroKind_keywordJudgement() {
        assertThat(ImpactChainTemplates.macroKindOf("央行宣布降准0.5个百分点", null))
                .isEqualTo(ImpactChainTemplates.MacroKind.MONETARY);
        assertThat(ImpactChainTemplates.macroKindOf("发行专项债支持基建", null))
                .isEqualTo(ImpactChainTemplates.MacroKind.FISCAL);
        assertThat(ImpactChainTemplates.macroKindOf("发布新能源产业规划", null))
                .isEqualTo(ImpactChainTemplates.MacroKind.INDUSTRIAL);
        assertThat(ImpactChainTemplates.macroKindOf("无关文本", "LPR 下行"))
                .isEqualTo(ImpactChainTemplates.MacroKind.MONETARY);
    }

    // ---- 渲染主路径 ----

    @Test
    @DisplayName("降准事件（BULLISH）→ 银行/非银金融/房地产 利好传导链，模板键 POLICY_MONETARY")
    void render_policyReleaseMonetary_mapsBankAndRealEstate() {
        EventItem event =
                eventOf(
                        EventType.POLICY_RELEASE,
                        Direction.BULLISH,
                        Importance.HIGH,
                        "央行宣布降准0.5个百分点",
                        List.of());

        List<ImpactChainTemplates.ChainOutput> chains = ImpactChainTemplates.render(event);

        assertThat(chains).isNotEmpty();
        assertThat(chains.get(0).templateKey()).isEqualTo(ImpactChainTemplates.KEY_POLICY_MONETARY);
        assertThat(chains.stream().map(ImpactChainTemplates.ChainOutput::industry))
                .contains("银行", "非银金融", "房地产");
        for (ImpactChainTemplates.ChainOutput chain : chains) {
            assertThat(chain.direction())
                    .as("宽松政策传导行业方向 = 事件方向 BULLISH")
                    .isEqualTo(Direction.BULLISH);
            assertThat(chain.logicChain()).as("逻辑链非空").isNotBlank();
        }
        assertThat(
                        chains.stream()
                                .filter(chain -> chain.industry().equals("银行"))
                                .findFirst()
                                .orElseThrow()
                                .logicChain())
                .contains("流动");
    }

    @Test
    @DisplayName("财政政策（BEARISH 收紧）→ 基建链行业随事件方向利空")
    void render_policyReleaseFiscal_followsEventDirection() {
        EventItem event =
                eventOf(
                        EventType.POLICY_RELEASE,
                        Direction.BEARISH,
                        Importance.HIGH,
                        "财政政策收紧，专项债额度压降",
                        List.of());

        List<ImpactChainTemplates.ChainOutput> chains = ImpactChainTemplates.render(event);

        assertThat(chains.get(0).templateKey()).isEqualTo(ImpactChainTemplates.KEY_POLICY_FISCAL);
        assertThat(chains.stream().map(ImpactChainTemplates.ChainOutput::industry))
                .contains("建筑装饰", "建筑材料");
        assertThat(chains)
                .allSatisfy(chain -> assertThat(chain.direction()).isEqualTo(Direction.BEARISH));
    }

    @Test
    @DisplayName("产业政策：事件提取行业为主传导集（无关键词命中落产业模板）")
    void render_policyReleaseIndustrial_usesEventIndustries() {
        EventItem event =
                eventOf(
                        EventType.POLICY_RELEASE,
                        Direction.BULLISH,
                        Importance.MEDIUM,
                        "发布算力基础设施扶持政策",
                        List.of("计算机", "通信"));

        List<ImpactChainTemplates.ChainOutput> chains = ImpactChainTemplates.render(event);

        assertThat(chains.get(0).templateKey())
                .isEqualTo(ImpactChainTemplates.KEY_POLICY_INDUSTRIAL);
        assertThat(chains.stream().map(ImpactChainTemplates.ChainOutput::industry))
                .contains("计算机", "通信");
    }

    @Test
    @DisplayName("公司类事件：事件提取行业必含且方向=事件方向（零新增事实：行业集来自 event_item）")
    void render_companyEvent_includesAffectedIndustriesWithEventDirection() {
        EventItem event =
                eventOf(
                        EventType.EARNINGS_FORECAST,
                        Direction.BEARISH,
                        Importance.MEDIUM,
                        "某公司下修业绩预告",
                        List.of("食品饮料"));

        List<ImpactChainTemplates.ChainOutput> chains = ImpactChainTemplates.render(event);

        assertThat(chains).isNotEmpty();
        assertThat(chains.get(0).templateKey()).isEqualTo(EventType.EARNINGS_FORECAST.name());
        assertThat(chains.stream().map(ImpactChainTemplates.ChainOutput::industry))
                .contains("食品饮料");
        assertThat(
                        chains.stream()
                                .filter(chain -> chain.industry().equals("食品饮料"))
                                .findFirst()
                                .orElseThrow()
                                .direction())
                .isEqualTo(Direction.BEARISH);
    }

    @Test
    @DisplayName("affected 空时回落 subjects 行业，仍空回落类型默认行业集（每类至少一条产出）")
    void render_affectedEmpty_fallsBackToSubjectsThenDefaults() {
        EventItem withSubject =
                EventItem.create(
                        101L,
                        EventType.BUYBACK_CHANGE,
                        "公司公告回购",
                        List.of(),
                        Direction.BULLISH,
                        Importance.MEDIUM,
                        List.of(),
                        List.of(new EventItem.SubjectRef("600001", "示例公司", "医药生物")),
                        null,
                        EVENT_TIME,
                        "2026-09-22",
                        "v1.0");
        assertThat(
                        ImpactChainTemplates.render(withSubject).stream()
                                .map(ImpactChainTemplates.ChainOutput::industry))
                .contains("医药生物");

        EventItem noIndustry =
                eventOf(
                        EventType.MAJOR_CONTRACT,
                        Direction.BULLISH,
                        Importance.HIGH,
                        "公司中标重大项目",
                        List.of());
        List<ImpactChainTemplates.ChainOutput> chains = ImpactChainTemplates.render(noIndustry);

        assertThat(chains).as("行业缺位时模板默认行业集兜底，链不为空").isNotEmpty();
        assertThat(chains.get(0).templateKey()).isEqualTo(EventType.MAJOR_CONTRACT.name());
    }

    @Test
    @DisplayName("NEUTRAL 事件全链中性；行业去重不重复出链")
    void render_neutralEvent_allChainsNeutralAndDeduplicated() {
        EventItem event =
                eventOf(
                        EventType.EXEC_CHANGE,
                        Direction.NEUTRAL,
                        Importance.MEDIUM,
                        "公司聘任新高管",
                        List.of("银行", "银行"));

        List<ImpactChainTemplates.ChainOutput> chains = ImpactChainTemplates.render(event);

        assertThat(chains)
                .allSatisfy(chain -> assertThat(chain.direction()).isEqualTo(Direction.NEUTRAL));
        assertThat(chains.stream().map(ImpactChainTemplates.ChainOutput::industry).distinct())
                .as("同行业去重")
                .hasSize(chains.size());
    }

    @Test
    @DisplayName("OTHER 兜底类亦有模板，渲染不抛异常")
    void render_otherType_fallsBackToOtherTemplate() {
        EventItem event =
                eventOf(
                        EventType.OTHER,
                        Direction.BULLISH,
                        Importance.MEDIUM,
                        "其他类型事件",
                        List.of("综合"));

        List<ImpactChainTemplates.ChainOutput> chains = ImpactChainTemplates.render(event);

        assertThat(chains).isNotEmpty();
        assertThat(chains.get(0).templateKey()).isEqualTo(EventType.OTHER.name());
    }

    // ---- 工具 ----

    private static EventItem eventOf(
            EventType type,
            Direction direction,
            Importance importance,
            String summary,
            List<String> industries) {
        return EventItem.create(
                100L,
                type,
                summary,
                industries,
                direction,
                importance,
                List.of(),
                List.of(),
                null,
                EVENT_TIME,
                "2026-09-22",
                "v1.0");
    }
}
