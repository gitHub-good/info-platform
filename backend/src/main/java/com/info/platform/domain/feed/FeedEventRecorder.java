package com.info.platform.domain.feed;

/**
 * 资讯源旁路事件记录端口（M13 T103，方案 §4.8）：失败/静默事件沿用 {@code data_source_event} 表（{@code info:{source_code}}
 * 前缀命名），实现归基础设施层（FeedDataSourceEventRecorder）；记录失败不得影响主链路（旁路契约）。
 */
public interface FeedEventRecorder {

    /**
     * 记一条失败事件（event_type=3 错误）。
     *
     * @param sourceCode 源稳定代码（落库带 info: 前缀）
     * @param consecutiveFailures 当前连续失败数（detail 留痕）
     * @param detail 失败摘要
     */
    void recordFailure(String sourceCode, int consecutiveFailures, String detail);
}
