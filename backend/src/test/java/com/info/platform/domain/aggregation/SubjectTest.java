package com.info.platform.domain.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Subject 实体单测（T31 扩展位守卫）：预留类型（基金/债券）不允许经 Builder 创建标的数据； 已开放类型构建不受影响；reconstruct 读路径不受守卫限制。 */
class SubjectTest {

    private static Subject.Builder openTypeSubject(SubjectType type) {
        return Subject.builder()
                .subjectCode(SubjectCode.of("SH600519"))
                .market(Market.A_SHARE)
                .subjectType(type)
                .name("贵州茅台")
                .externalCodes(Map.of("eastmoney", "1.600519"));
    }

    @Test
    void build_openType_succeeds() {
        // Act: 已开放类型（股票）正常构建
        Subject subject = openTypeSubject(SubjectType.STOCK).build();

        // Assert
        assertThat(subject.getSubjectType()).isEqualTo(SubjectType.STOCK);
        assertThat(subject.getName()).isEqualTo("贵州茅台");
    }

    @Test
    void build_reservedType_rejectedWithClearMessage() {
        // Assert: 预留类型创建被拒（基金），错误信息含类型标签与编码，可定位
        assertThatThrownBy(() -> openTypeSubject(SubjectType.FUND_RESERVED).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("预留")
                .hasMessageContaining("基金")
                .hasMessageContaining("4");
        // Assert: 债券同理
        assertThatThrownBy(() -> openTypeSubject(SubjectType.BOND_RESERVED).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("债券")
                .hasMessageContaining("5");
    }

    @Test
    void build_missingRequiredField_failsFast() {
        // Assert: 既有必填校验不回归（缺 name 直接拒绝）
        assertThatThrownBy(
                        () ->
                                Subject.builder()
                                        .subjectCode(SubjectCode.of("SH600519"))
                                        .market(Market.A_SHARE)
                                        .subjectType(SubjectType.STOCK)
                                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
    }

    @Test
    void reconstruct_reservedType_allowedOnReadPath() {
        // Act: 读路径（回读历史/预留行）不走 Builder 守卫
        Subject subject =
                Subject.reconstruct(
                        9L,
                        SubjectCode.of("FUND0001"),
                        Market.A_SHARE,
                        SubjectType.FUND_RESERVED,
                        "示例基金",
                        Map.of(),
                        null,
                        SubjectStatus.ENABLED,
                        1L,
                        null,
                        null);

        // Assert: 预留类型实体可还原（类型体系开放，仅创建被守卫拦截）
        assertThat(subject.getSubjectType()).isEqualTo(SubjectType.FUND_RESERVED);
        assertThat(subject.getId()).isEqualTo(9L);
    }
}
