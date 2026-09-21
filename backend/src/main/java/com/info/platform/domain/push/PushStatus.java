package com.info.platform.domain.push;

/**
 * 推送状态（push_record.status 持久化为 TINYINT）。
 *
 * <p>领域层纯净枚举（仅 JDK）。对齐技术方案 §4.2 push_record DDL：0 待推 / 1 已推 / 2 失败。 生命周期：
 *
 * <ul>
 *   <li>新建 push_record（{@link PushRecord#create}）→ {@link #PENDING}（0，待推）
 *   <li>SSE 推送在线用户成功 → {@link #SUCCESS}（1，已推，回填 pushed_at）
 *   <li>推送失败重试 1 次仍失败 → {@link #FAILED}（2，记 ERROR 告警）
 *   <li>离线用户 push_record 留 {@link #PENDING}，重连 SSE 时补拉待推记录后翻转为 {@link #SUCCESS}
 * </ul>
 */
public enum PushStatus {
    /** 待推：新建默认；离线用户留存此态待重连补拉。 */
    PENDING(0),
    /** 已推：SSE 推送在线用户成功。 */
    SUCCESS(1),
    /** 失败：重试 1 次仍失败，记 ERROR 告警。 */
    FAILED(2);

    private final int code;

    PushStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static PushStatus fromCode(int code) {
        for (PushStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知 pushStatus: " + code);
    }
}
