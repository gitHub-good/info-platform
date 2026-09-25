package com.info.platform.application.feed;

import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.SourcePollState;
import java.time.Instant;

/**
 * 源运行态五值（M14 T114 大盘源维度表状态徽章，口径 = 源管理页五态徽章矩阵的服务端镜像——两页同数据同语义， 对账验收前提）。
 *
 * <p>判定序（与前端 {@code InfoSources.statusBadgeOf} 一致）：停用 → 暂未抓取 → 退避中（连续失败 ≥2 且 backoff 未到期）→
 * 失败（最近尝试晚于最近成功且带 lastError）→ 正常。
 */
public enum SourceRunState {

    /** 正常（最近一轮成功）。 */
    OK("ok"),

    /** 失败（最近一轮失败、未进退避）。 */
    FAIL("fail"),

    /** 退避中（连续失败 ≥2 且 backoff_until 晚于当前时刻）。 */
    BACKOFF("backoff"),

    /** 停用（enabled=0，调度已摘除）。 */
    DISABLED("disabled"),

    /** 暂未抓取（无运行态行或从未尝试）。 */
    PENDING("pending");

    private final String wireCode;

    SourceRunState(String wireCode) {
        this.wireCode = wireCode;
    }

    /** 线格式（大盘 API runState 字段）。 */
    public String wireCode() {
        return wireCode;
    }

    /** 异常态（失败/退避中）——大盘源维度表置顶排序依据。 */
    public boolean abnormal() {
        return this == FAIL || this == BACKOFF;
    }

    /** 据源配置 + 运行态判定五值（纯函数可单测）。 */
    public static SourceRunState of(InfoSource source, SourcePollState state, Instant now) {
        if (!source.isEnabled()) {
            return DISABLED;
        }
        if (state == null || state.lastAttemptAt() == null) {
            return PENDING;
        }
        if (state.consecutiveFailures() >= 2
                && state.backoffUntil() != null
                && state.backoffUntil().isAfter(now)) {
            return BACKOFF;
        }
        Instant lastSuccess = state.lastSuccessAt();
        if (state.lastError() != null
                && (lastSuccess == null || state.lastAttemptAt().isAfter(lastSuccess))) {
            return FAIL;
        }
        return OK;
    }
}
