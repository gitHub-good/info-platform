package com.info.platform.application.subscription;

import java.util.List;

/**
 * 个人信息流响应条目（应用层视图，对齐技术方案 §4.1.6 {@code GET /api/v1/feed/personal} FeedItem；T43 增补 {@code
 * keywords}，M11/REQ-20260925-08 增补稳定内容标识 {@code contentId}）。
 *
 * <p>{@code id} 为合成游标序号（按 publishedAt 倒序排序后 1 起递增），游标分页 {@code id > cursor LIMIT 20} 用； M2 首期为内存合成
 * id（每次请求重算），跨请求稳定性待 M3 预计算落库后由持久 id 承接。 {@code type} 覆盖 announce/news/policy（订阅命中）+
 * recommendation（每日推荐，{@code matchReason="每日推荐"}）。 {@code matchReason} 为订阅命中原因（主题/标的/事件类型/政策主题 +
 * key），推荐项填「每日推荐」。
 *
 * <p>{@code keywords} 为该条目命中的订阅关键词（与 {@code matchReason} 同源，首次命中订阅）：主题/政策主题为订阅词（文本命中）、
 * 标的为标的名、事件类型与每日推荐为空数组。前端据此做命中词高亮（UI 方案 §3.5 交互 2 / §6.2 联判点 5）—— 只加字段，既有字段语义不变。
 *
 * <p>{@code contentId} 为跨请求稳定的内容标识（M11 / REQ-20260925-08 增量字段，解除 ADR-0019 契约缺口）：形态 {@code
 * {type}:{源稳定 id}}——公告/新闻 {@code announce:/news: + externalId}、政策 {@code policy: + policyId}、推荐
 * {@code recommendation: + subjectCode}。满足四不变量：跨请求稳定（源 id 稳定，与游标无关）、长度 ≤200、类型前缀可辨（防跨类型条目撞
 * readingEvent 去重键）、禁用合成游标 id。用作 FEED 阅读埋点的 contentRef；源缺稳定 id 时为 null（该条不埋点，不用游标 id 兜底）。
 *
 * @param id 合成游标序号（1 起递增）
 * @param contentId 稳定内容标识（FEED 埋点 contentRef 用，见上；源缺稳定 id 为 null）
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
        String contentId,
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

    /** 复制本条目并替换 id（游标序号在排序后回填；contentId 等其余字段不变）。 */
    public FeedItem withId(Long newId) {
        return new FeedItem(
                newId,
                contentId,
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
