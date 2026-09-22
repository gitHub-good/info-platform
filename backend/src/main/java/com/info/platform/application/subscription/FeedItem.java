package com.info.platform.application.subscription;

import java.util.List;

/**
 * 个人信息流响应条目（应用层视图，对齐技术方案 §4.1.6 {@code GET /api/v1/feed/personal} FeedItem；T43 增补 {@code
 * keywords}）。
 *
 * <p>{@code id} 为合成游标序号（按 publishedAt 倒序排序后 1 起递增），游标分页 {@code id > cursor LIMIT 20} 用； M2 首期为内存合成
 * id（每次请求重算），跨请求稳定性待 M3 预计算落库后由持久 id 承接。 {@code type} 覆盖 announce/news/policy（订阅命中）+
 * recommendation（每日推荐，{@code matchReason="每日推荐"}）。 {@code matchReason} 为订阅命中原因（主题/标的/事件类型/政策主题 +
 * key），推荐项填「每日推荐」。
 *
 * <p>{@code keywords} 为该条目命中的订阅关键词（与 {@code matchReason} 同源，首次命中订阅）：主题/政策主题为订阅词（文本命中）、 标的为标的名、事件类型与每日推荐为空数组。前端据此做命中词高亮（UI 方案 §3.5 交互 2 / §6.2 联判点 5）——
 * 只加字段，既有字段语义不变。
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
 * @param keywords 命中关键词（可高亮文本词；无文本命中词为空列表，序列化恒为 [] 不为 null）
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
        String matchReason,
        List<String> keywords) {

    /** 紧凑构造器：关键词不可 null（统一空列表，保证 JSON 序列化为 []）。 */
    public FeedItem {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

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
                matchReason,
                keywords);
    }
}
