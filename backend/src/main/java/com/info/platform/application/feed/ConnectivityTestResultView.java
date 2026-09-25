package com.info.platform.application.feed;

import java.util.List;

/**
 * 连通性测试诊断体（M13 T105，方案 §4.5 POST /{id}/connectivity-test）：干跑一轮不落库， 200 恒返回（失败原因在 body——测试已执行即 200）。
 *
 * @param reachable 端点可达且解析成功
 * @param robotsAllowed robots 判读结论（保存红线复判）
 * @param latencyMillis 取数耗时毫秒（robots 判读不计入）
 * @param parsedCount 解析条数（引擎首页原始条数，未过过滤）
 * @param error 失败摘要（reachable=true 为 null）
 * @param sampleItems 解析样本（≤3 条，title/url/publishedAt）
 */
public record ConnectivityTestResultView(
        boolean reachable,
        boolean robotsAllowed,
        Long latencyMillis,
        Integer parsedCount,
        String error,
        List<SampleItem> sampleItems) {

    /** 样本条目。 */
    public record SampleItem(String title, String url, String publishedAt) {}
}
