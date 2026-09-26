package com.info.platform.application.recommendation;

import com.info.platform.domain.analysis.EventItem;
import com.info.platform.domain.recommendation.RecommendationCard;
import java.time.Instant;
import java.util.List;

/**
 * 推荐中心卡片视图（M16 T133，方案 §4.8）：一用户一事件一卡的卡片流（id DESC 游标分页）。卡片字段面 = 方案冻结契约 {@code {id, eventId,
 * eventType, importance, direction, level, industries[], subjects[], logicChain, summary, figures,
 * quote, newsId, newsTitle, newsUrl, eventTime, pushedAt, createdAt, read, muted,
 * feedbackAction}}—— figures/quote/summary/eventTime/newsTitle/newsUrl 由 event_item + news_item
 * join 直出（卡片表不冗余大字段）。
 *
 * @param total 当前筛选总数（无筛选 = 该用户全量卡，§4.11 对账）
 * @param items 当前页卡片
 * @param nextBeforeId 下页游标（尾页 null）
 */
public record RecommendationCardListView(long total, List<CardView> items, Long nextBeforeId) {

    /** 卡片视图（subjects code 非空可跳标的详情 + inWatchlist 供「加自选」按钮；pushStatus 含 SILENT 语义态）。 */
    public record CardView(
            long id,
            long eventId,
            String eventType,
            String importance,
            String direction,
            String level,
            List<String> industries,
            List<SubjectView> subjects,
            String logicChain,
            String summary,
            List<FigureView> figures,
            String quote,
            long newsId,
            String newsTitle,
            String newsUrl,
            Instant eventTime,
            String pushStatus,
            Instant pushedAt,
            Instant createdAt,
            boolean read,
            boolean muted,
            String feedbackAction) {}

    /** 标的区条目（≤5；inWatchlist=true 已在自选，false 展示「加自选」）。 */
    public record SubjectView(String code, String name, String industry, boolean inWatchlist) {

        static SubjectView of(RecommendationCard.CardSubject subject) {
            return new SubjectView(
                    subject.code(), subject.name(), subject.industry(), subject.inWatchlist());
        }
    }

    /** 关键数字 chips（结构化事实直出，禁编造）。 */
    public record FigureView(String label, String value, String unit) {

        static FigureView of(EventItem.KeyFigure figure) {
            return new FigureView(figure.label(), figure.value(), figure.unit());
        }
    }
}
