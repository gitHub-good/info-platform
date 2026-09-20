package com.info.platform.infrastructure.aggregation;

/** 字段映射加载或转换失败（运行时异常，带上下文，不吞）。 */
public class FieldMappingException extends RuntimeException {

    public FieldMappingException(String message) {
        super(message);
    }

    public FieldMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
