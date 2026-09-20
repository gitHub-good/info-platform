package com.info.platform.domain.common;

/**
 * 统一错误码（领域层 shared kernel，纯枚举、不依赖框架）。
 *
 * <p>分段：0 成功 / 1xxx 认证 / 2xxx 参数 / 3xxx 业务 / 5xxx 服务端。
 * httpStatus 为对应 HTTP 状态码语义（供接口层全局异常处理器映射，不在领域层使用 Spring 类型）。
 * 错误码清单对齐技术方案 §4.1。
 */
public enum ErrorCode {

    SUCCESS(0, "成功", 200),

    UNAUTHORIZED(1001, "未认证", 401),

    PARAM_INVALID(2001, "参数校验失败", 400),

    /** 标的不存在（404 语义） */
    SUBJECT_NOT_FOUND(30001, "标的不存在", 404),
    /** 详情全部分区均缺失（仍 200，前端兜底） */
    SUBJECT_DETAIL_ALL_MISSING(30002, "全部分区均缺失", 200),

    /** 自选清单不存在 */
    WATCHLIST_NOT_FOUND(30010, "自选清单不存在", 404),
    /** 标的已在清单中（409） */
    SUBJECT_ALREADY_IN_WATCHLIST(30011, "标的已在清单中", 409),
    /** 无权操作该清单（403） */
    WATCHLIST_FORBIDDEN(30012, "无权操作该清单", 403),

    /** 推送通道未配置，不重复发送（软跳过，200） */
    PUSH_CHANNEL_NOT_CONFIGURED(30020, "推送通道未配置，不重复发送", 200),

    /** AI 配额已用尽（429 语义） */
    AI_QUOTA_EXHAUSTED(30030, "AI 配额已用尽", 429),

    /** 政策条目不存在 */
    POLICY_NOT_FOUND(30040, "政策条目不存在", 404),

    /** 服务端异常（500） */
    SERVER_ERROR(50000, "服务异常", 500);

    private final int code;
    private final String msg;
    private final int httpStatus;

    ErrorCode(int code, String msg, int httpStatus) {
        this.code = code;
        this.msg = msg;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
