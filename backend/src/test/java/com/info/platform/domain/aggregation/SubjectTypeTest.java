package com.info.platform.domain.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** SubjectType 单测（T31 扩展位）：编码/标签/预留标记、activeTypes 默认支持范围、fromCode 对预留值开放。 */
class SubjectTypeTest {

    @Test
    void codesAndLabels_matchDesign() {
        // Assert: 编码对齐 V1 DDL 注释（1股票 2指数 3板块 4基金预留 5债券预留），标签为中文展示名
        assertThat(SubjectType.STOCK.code()).isEqualTo(1);
        assertThat(SubjectType.STOCK.label()).isEqualTo("股票");
        assertThat(SubjectType.INDEX.code()).isEqualTo(2);
        assertThat(SubjectType.INDEX.label()).isEqualTo("指数");
        assertThat(SubjectType.SECTOR.code()).isEqualTo(3);
        assertThat(SubjectType.SECTOR.label()).isEqualTo("板块");
        assertThat(SubjectType.FUND_RESERVED.code()).isEqualTo(4);
        assertThat(SubjectType.FUND_RESERVED.label()).isEqualTo("基金");
        assertThat(SubjectType.BOND_RESERVED.code()).isEqualTo(5);
        assertThat(SubjectType.BOND_RESERVED.label()).isEqualTo("债券");
    }

    @Test
    void reservedMarks_onlyFundAndBond() {
        // Assert: 已开放类型非预留；基金/债券为预留（类型体系开放、数据创建受守卫拦截）
        assertThat(SubjectType.STOCK.isReserved()).isFalse();
        assertThat(SubjectType.INDEX.isReserved()).isFalse();
        assertThat(SubjectType.SECTOR.isReserved()).isFalse();
        assertThat(SubjectType.FUND_RESERVED.isReserved()).isTrue();
        assertThat(SubjectType.BOND_RESERVED.isReserved()).isTrue();
    }

    @Test
    void activeTypes_containsOpenTypes_excludesReserved() {
        // Act
        Set<SubjectType> active = SubjectType.activeTypes();

        // Assert: 端口默认支持范围 = 股票/指数/板块，不含预留类型
        assertThat(active)
                .containsExactlyInAnyOrder(
                        SubjectType.STOCK, SubjectType.INDEX, SubjectType.SECTOR);
    }

    @Test
    void activeTypes_isUnmodifiable() {
        // Assert: 返回不可变集合，防止调用方篡改默认注册范围
        assertThatThrownBy(() -> SubjectType.activeTypes().add(SubjectType.FUND_RESERVED))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void fromCode_knownCodes_includingReserved() {
        // Assert: 类型体系对预留值开放（读路径可还原 4/5 行）
        assertThat(SubjectType.fromCode(4)).isEqualTo(SubjectType.FUND_RESERVED);
        assertThat(SubjectType.fromCode(5)).isEqualTo(SubjectType.BOND_RESERVED);
    }

    @Test
    void fromCode_unknownCode_throws() {
        // Assert: 未知编码 fail fast
        assertThatThrownBy(() -> SubjectType.fromCode(99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }
}
