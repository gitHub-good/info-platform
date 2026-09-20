package com.info.platform.domain.aggregation;

/** 标的类型：1 股票 / 2 指数 / 3 板块 / 4 基金(预留) / 5 债券(预留)。 */
public enum SubjectType {
    STOCK(1),
    INDEX(2),
    SECTOR(3),
    FUND_RESERVED(4),
    BOND_RESERVED(5);

    private final int code;

    SubjectType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
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
