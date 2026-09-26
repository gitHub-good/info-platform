package com.info.platform.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.recommendation.LogicChainTemplates.TemplateInput;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 模板拼接兜底单测（T132，方案 §4.5 步骤 6 / REQ 拍板二三形态原文）：P1/P2/P3 三分支文案结构（三环节完整——事件环节 summary
 * 前置 + 逻辑环节关联判定 + 用户环节「你关注的…」）、P2 空标的双形态（有/无订阅主题）、P3 主题/事件类型双来源。
 */
class LogicChainTemplatesTest {

    private static final String SUMMARY = "某公司公告回购计划";

    private static RecommendationCard.CardSubject subject(String name, String industry) {
        return new RecommendationCard.CardSubject("SH600000", name, industry, true);
    }

    @Test
    void p1_subjects_directConcern() {
        // Arrange
        TemplateInput input =
                new TemplateInput(
                        RecLevel.P1,
                        SUMMARY,
                        Direction.BULLISH,
                        List.of("食品饮料"),
                        List.of(subject("贵州茅台", "食品饮料"), subject("五粮液", "食品饮料")),
                        null,
                        "回购·增持·减持");

        // Act
        String chain = LogicChainTemplates.render(input);

        // Assert：三环节 = 事件（summary 前置）—— 逻辑（直接涉及）—— 用户（你关注的标的列举）
        assertThat(chain).startsWith(SUMMARY + "——");
        assertThat(chain).contains("该事件直接涉及你关注的标的贵州茅台、五粮液。");
        assertThat(chain).endsWith("。");
    }

    @Test
    void p2_withSubjects_industryChain() {
        // Arrange
        TemplateInput input =
                new TemplateInput(
                        RecLevel.P2,
                        SUMMARY,
                        Direction.BULLISH,
                        List.of("电子", "计算机"),
                        List.of(subject("中芯国际", "电子")),
                        null,
                        "回购·增持·减持");

        // Act
        String chain = LogicChainTemplates.render(input);

        // Assert：因该事件{方向词}{行业}行业，你关注的标的属于该行业
        assertThat(chain)
                .isEqualTo(SUMMARY + "——因该事件利好电子行业，你关注的标的属于该行业。");
    }

    @Test
    void p2_emptySubjects_withTheme_sourceNote() {
        // Arrange：通道 B 空转——标的区空 + 命中主题来源（源于你订阅的{主题}）
        TemplateInput input =
                new TemplateInput(
                        RecLevel.P2,
                        SUMMARY,
                        Direction.BEARISH,
                        List.of("电子"),
                        List.of(),
                        "半导体",
                        "回购·增持·减持");

        // Act
        String chain = LogicChainTemplates.render(input);

        // Assert
        assertThat(chain)
                .isEqualTo(
                        SUMMARY + "——你关注的电子行业受该事件利空影响（源于你订阅的半导体）。");
    }

    @Test
    void p2_emptySubjects_withoutTheme_noSourceNote() {
        // Arrange：通道 B 命中（标的行业）但无订阅主题——省略来源注
        TemplateInput input =
                new TemplateInput(
                        RecLevel.P2,
                        SUMMARY,
                        Direction.NEUTRAL,
                        List.of("电子"),
                        List.of(),
                        null,
                        "回购·增持·减持");

        // Act
        String chain = LogicChainTemplates.render(input);

        // Assert：NEUTRAL 方向词为「中性」；无主题不硬凑来源
        assertThat(chain).isEqualTo(SUMMARY + "——你关注的电子行业受该事件中性影响。");
        assertThat(chain).doesNotContain("订阅");
    }

    @Test
    void p3_themeHit() {
        // Arrange
        TemplateInput input =
                new TemplateInput(
                        RecLevel.P3,
                        SUMMARY,
                        Direction.BULLISH,
                        List.of(),
                        List.of(),
                        "回购",
                        "回购·增持·减持");

        // Act
        String chain = LogicChainTemplates.render(input);

        // Assert
        assertThat(chain).isEqualTo(SUMMARY + "——与你订阅的回购相关。");
    }

    @Test
    void p3_eventTypeSubscription() {
        // Arrange：EVENT_TYPE 订阅命中（无主题词）
        TemplateInput input =
                new TemplateInput(
                        RecLevel.P3,
                        SUMMARY,
                        Direction.NEUTRAL,
                        List.of(),
                        List.of(),
                        null,
                        "政策发布");

        // Act
        String chain = LogicChainTemplates.render(input);

        // Assert
        assertThat(chain).isEqualTo(SUMMARY + "——与你订阅的政策发布类事件相关。");
    }

    @Test
    void p2_industriesEmpty_fallsBackToRelatedWording() {
        // Arrange：affected_industries 为空（combo_key industry="-"）防御路径——不硬凑行业名
        TemplateInput input =
                new TemplateInput(
                        RecLevel.P2,
                        SUMMARY,
                        Direction.BULLISH,
                        List.of(),
                        List.of(subject("中芯国际", null)),
                        "半导体",
                        "回购·增持·减持");

        // Act
        String chain = LogicChainTemplates.render(input);

        // Assert：行业缺省用「相关」措辞，仍保三环节
        assertThat(chain).isEqualTo(SUMMARY + "——该事件影响你关注的行业（源于你订阅的半导体）。");
    }
}
