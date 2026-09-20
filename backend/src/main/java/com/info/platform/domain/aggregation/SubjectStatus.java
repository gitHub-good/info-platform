package com.info.platform.domain.aggregation;

/**
 * 标的启用状态：1 启用 / 0 停用。
 */
public enum SubjectStatus {

    DISABLED(0),
    ENABLED(1);

    private final int code;

    SubjectStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static SubjectStatus fromCode(int code) {
        for (SubjectStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知 subjectStatus: " + code);
    }
}
