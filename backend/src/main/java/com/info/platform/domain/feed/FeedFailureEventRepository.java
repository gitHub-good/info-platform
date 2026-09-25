package com.info.platform.domain.feed;

import java.util.List;

/**
 * 资讯源失败事件读端口（M14 T114）：大盘「近期失败列表」从 {@code data_source_event} 旁路事件（event_type=3 错误， {@code
 * info:{sourceCode}} 前缀键空间，ADR-0038 边界）读取最近失败记录。与写侧 {@link FeedEventRecorder} 同表不同向。
 */
public interface FeedFailureEventRepository {

    /**
     * 最近 N 条失败事件（时间倒序，同刻按 id 倒序）。
     *
     * @param limit 条数上限（接口层常量，大盘 v1 = 20）
     */
    List<FeedFailureEvent> findRecent(int limit);
}
