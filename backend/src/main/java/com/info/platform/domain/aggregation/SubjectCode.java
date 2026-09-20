package com.info.platform.domain.aggregation;

import java.util.Objects;

/**
 * 标的内部统一代码值对象（如 {@code SH600519}）。
 *
 * <p>不可变；构造时校验非空。领域层纯净，不引入任何框架类型。
 */
public final class SubjectCode {

    private final String value;

    private SubjectCode(String value) {
        this.value = value;
    }

    public static SubjectCode of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("subjectCode 不能为空");
        }
        return new SubjectCode(value.trim());
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SubjectCode that)) {
            return false;
        }
        return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return "SubjectCode{" + value + "}";
    }
}
