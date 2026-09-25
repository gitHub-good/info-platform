package com.info.platform.application.retention;

/**
 * 留痕清理执行参数（T71，ADR-0036 §4）：代码常量，不入配置键——执行细节非运维参数，避免配置面蔓延。
 *
 * <ul>
 *   <li>{@link #BATCH_SIZE}：每批删除行数。500 行/批毫秒级提交（SQLite WAL 单写者，锁窗口短）；首轮 15,987 行 ≈32 批， 稳态日增 ≈8
 *       批/日。批间独立事务。
 * </ul>
 */
public final class RetentionPolicy {

    /** 每批删除行数（批间独立事务，循环删至返回值 &lt; BATCH_SIZE）。 */
    public static final int BATCH_SIZE = 500;

    private RetentionPolicy() {}
}
