package com.info.platform.domain.common;

/**
 * 统一错误码（领域层 shared kernel，纯枚举、不依赖框架）。
 *
 * <p>分段：0 成功 / 1xxx 认证 / 2xxx 参数 / 3xxx 业务 / 5xxx 服务端。 httpStatus 为对应 HTTP
 * 状态码语义（供接口层全局异常处理器映射，不在领域层使用 Spring 类型）。 错误码清单对齐技术方案 §4.1。
 */
public enum ErrorCode {
    SUCCESS(0, "成功", 200),

    /** 登录凭证错误：用户名不存在或密码不匹配（401） */
    BAD_CREDENTIALS(1001, "凭证错误", 401),
    /** 登录限流：撞库防护触发，锁定窗口内拒绝（429） */
    LOGIN_RATE_LIMITED(1002, "登录尝试过于频繁，请稍后再试", 429),
    /** 令牌无效或已过期：缺失/格式错误/签名不符/过期（401） */
    TOKEN_INVALID(1003, "令牌无效或已过期", 401),

    PARAM_INVALID(2001, "参数校验失败", 400),

    /** HTTP 方法不支持（如 GET 打 PATCH-only 路径，405；ISSUE-C） */
    METHOD_NOT_SUPPORTED(2002, "HTTP 方法不支持", 405),

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
    /** 提示词模板未配置启用版本（配置缺失，500 语义） */
    PROMPT_TEMPLATE_NOT_FOUND(30031, "提示词模板未配置", 500),
    /** AI 简报任务不存在（404 语义，GET /ai-briefs/{taskId}） */
    AI_BRIEF_NOT_FOUND(30032, "AI 简报任务不存在", 404),

    /** 政策条目不存在 */
    POLICY_NOT_FOUND(30040, "政策条目不存在", 404),

    /** 订阅不存在（404 语义，DELETE /api/v1/subscriptions/{id}） */
    SUBSCRIPTION_NOT_FOUND(30050, "订阅不存在", 404),
    /** 无权操作该订阅（403，行级越权） */
    SUBSCRIPTION_FORBIDDEN(30051, "无权操作该订阅", 403),

    /** LLM provider 不存在（M4 配置可视化，方案 §4.4） */
    LLM_PROVIDER_NOT_FOUND(30060, "LLM provider 不存在", 404),
    /** 数据源配置不存在 */
    DATASOURCE_CONFIG_NOT_FOUND(30061, "数据源配置不存在", 404),
    /** 任务不存在 */
    JOB_NOT_FOUND(30062, "任务不存在", 404),
    /** 任务正在运行，拒绝重复触发（防重入，409） */
    JOB_ALREADY_RUNNING(30063, "任务正在运行，请等待本轮执行完成", 409),
    /** API key 写入未启用（CONFIG_SECRET 未配置，ADR-0018 降级态，503） */
    API_KEY_WRITE_DISABLED(30064, "API key 写入未启用（未配置 CONFIG_SECRET，key 暂走环境变量）", 503),
    /** 配置已被并发修改（expectedUpdatedAt 与当前 updated_at 不符，409） */
    CONFIG_CONFLICT(30065, "配置已被并发修改，请刷新后重试", 409),

    /** 模板版本不存在（M5 管理面 404；区别于 30031 生成时无激活模板的 500 语义） */
    PROMPT_TEMPLATE_VERSION_NOT_FOUND(30066, "模板版本不存在", 404),
    /** 模板校验失败：分段标记/顺序、system 段 json 字样、长度上限（msg 逐条，400） */
    PROMPT_TEMPLATE_INVALID(30067, "模板校验失败", 400),
    /** 存在待确认的占位符移除（data 返回 removed/unknown 清单，409） */
    PROMPT_PLACEHOLDER_REMOVAL_UNCONFIRMED(30068, "存在待确认的占位符移除，请逐项确认后重试", 409),
    /** 激活版本不可删除（仅置废版本可物理删除，409） */
    PROMPT_TEMPLATE_ACTIVE_DELETE_FORBIDDEN(30069, "激活版本不可删除，请先切换激活到其他版本", 409),
    /** 版本号冲突（UNIQUE(brief_type, version) 兜底，msg 注明冲突版本，409） */
    PROMPT_TEMPLATE_VERSION_CONFLICT(30070, "版本号冲突，请刷新列表后重试", 409),

    /** 资讯源不存在（404，M13 源管理，方案 §4.5） */
    INFO_SOURCE_NOT_FOUND(30071, "资讯源不存在", 404),
    /** 资讯源配置校验失败（类型/URL/频控 1~60/映射/headers/游标声明，msg 字段级，400） */
    INFO_SOURCE_CONFIG_INVALID(30072, "资讯源配置校验失败", 400),
    /** 预置源禁止删除（平台资产，仅启停与参数编辑；前端亦无删除入口，403） */
    INFO_SOURCE_PRESET_DELETE_FORBIDDEN(30073, "预置源不支持删除，仅可停用", 403),
    /** 该源抓取正在进行中（单源 CAS 守卫，手动触发 409） */
    INFO_SOURCE_POLL_IN_FLIGHT(30074, "该源抓取正在进行中，请稍后重试", 409),
    /** robots.txt 禁止抓取该端点（合规红线硬拦截，400） */
    INFO_SOURCE_ROBOTS_FORBIDDEN(30075, "robots.txt 禁止抓取该端点", 400),

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
