package com.info.platform.domain.aggregation;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 标的类型：1 股票 / 2 指数 / 3 板块 / 4 基金(预留) / 5 债券(预留)。
 *
 * <p>T31 扩展位：{@code reserved=true} 的类型为 PRD「预留不实现」的扩展类型——类型体系（枚举值/编码/标签）与数据源映射注册位（{@link
 * SourceAdapter#supportedSubjectTypes()}）对它们开放，但标的数据不允许创建（{@code Subject.Builder#build()} 校验拒绝）；
 * 待对应类型数据接入需求落地时将枚举改为非预留并放开守卫即可，核心聚合/推送/订阅代码零改动。
 */
public enum SubjectType {
    STOCK(1, "股票", false),
    INDEX(2, "指数", false),
    SECTOR(3, "板块", false),
    FUND_RESERVED(4, "基金", true),
    BOND_RESERVED(5, "债券", true);

    private final int code;
    private final String label;
    private final boolean reserved;

    SubjectType(int code, String label, boolean reserved) {
        this.code = code;
        this.label = label;
        this.reserved = reserved;
    }

    public int code() {
        return code;
    }

    /** 中文标签（展示用）。 */
    public String label() {
        return label;
    }

    /** 是否为预留类型（PRD 首期不实现，仅占扩展位）。 */
    public boolean isReserved() {
        return reserved;
    }

    /** 全部已开放（非预留）类型；{@link SourceAdapter} 支持范围的默认值。 */
    public static Set<SubjectType> activeTypes() {
        EnumSet<SubjectType> active = EnumSet.noneOf(SubjectType.class);
        for (SubjectType type : values()) {
            if (!type.reserved) {
                active.add(type);
            }
        }
        return Collections.unmodifiableSet(active);
    }

    public static SubjectType fromCode(int code) {
        for (SubjectType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知 subjectType: " + code);
    }
}
