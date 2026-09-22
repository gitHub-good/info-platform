package com.info.platform.application.subscription;

/**
 * 个人信息流响应条目（应用层视图，对齐技术方案 §4.1.6 {@code GET /api/v1/feed/personal} FeedItem）。
 *
 * <p>{@code id} 为合成游标序号（按 publishedAt 倒序排序后 1 起递增），游标分页 {@code id > cursor LIMIT 20} 用； M2 首期为内存合成
 * id（每次请求重算），跨请求稳定性待 M3 预计算落库后由持久 id 承接。 {@code type} 覆盖 announce/news/policy（订阅命中）+
 * recommendation（每日推荐，{@code matchReason="每日推荐"}）。 {@code matchReason} 为订阅命中原因（主题/标的/事件类型/政策主题 +
 * key），推荐项填「每日推荐」。
 *
 * @param id 合成游标序号（1 起递增）
 * @param type 条目类型（{@link FeedItemType}）
 * @param title 标题（推荐项为标的名称）
 * @param summary 摘要（推荐项为推荐理由）
 * @param publishedAt 发布时间 ISO 串（推荐项取信息流装配时刻）
 * @param source 来源标签
 * @param url 详情链接
 * @param subjectCode 标的代码（公告/新闻/推荐）
 * @param subjectName 标的名称（公告/新闻/推荐）
 * @param matchReason 命中原因（订阅类型+key）；推荐项为「每日推荐」
 */
public record FeedItem(
        Long id,
        FeedItemType type,
        String title,
        String summary,
        String publishedAt,
        String source,
        String url,
        String subjectCode,
        String subjectName,
        String matchReason) {

    /** 复制本条目并替换 id（游标序号在排序后回填）。 */
    public FeedItem withId(Long newId) {
        return new FeedItem(
                newId,
                type,
                title,
                summary,
                publishedAt,
                source,
                url,
                subjectCode,
                subjectName,
                matchReason);
    }
}
