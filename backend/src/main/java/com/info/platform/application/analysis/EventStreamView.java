package com.info.platform.application.analysis;

import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventItem;
import com.info.platform.domain.analysis.EventItemRepository;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.Importance;
import java.time.Instant;
import java.util.List;

/**
 * 事件流视图（M15 T127，方案 §4.8 {@code GET /api/v1/events}）：L2 结构化事件全字段卡片流（id DESC 游标分页）。 卡片字段面 =
 * 方案冻结契约：{@code {id, eventType, summary, industries, direction, importance, figures, subjects,
 * quote, newsId, newsTitle, newsUrl, eventTime}}——news 标题/链接 join news_item（event_item 无该两列，表结构核实）。
 *
 * @param total 当前筛选总数（无筛选 = event_item 全量，§4.10 对账）
 * @param items 当前页事件卡
 * @param nextBeforeId 下页游标（尾页 null）
 */
public record EventStreamView(long total, List<EventCardView> items, Long nextBeforeId) {

    /** 事件卡片（subjects code 非空可点跳标的详情；quote/figures 取自原文可回溯）。 */
    public record EventCardView(
            long id,
            EventType eventType,
            String summary,
            List<String> industries,
            Direction direction,
            Importance importance,
            List<FigureView> figures,
            List<SubjectView> subjects,
            String quote,
            long newsId,
            String newsTitle,
            String newsUrl,
            Instant eventTime) {

        static EventCardView of(EventItemRepository.EventStreamItem row) {
            return new EventCardView(
                    row.event().getId(),
                    row.event().getEventType(),
                    row.event().getSummary(),
                    row.event().getAffectedIndustries(),
                    row.event().getDirection(),
                    row.event().getImportance(),
                    row.event().getKeyFigures().stream().map(FigureView::of).toList(),
                    row.event().getSubjects().stream().map(SubjectView::of).toList(),
                    row.event().getQuote(),
                    row.event().getNewsId(),
                    row.newsTitle(),
                    row.newsUrl(),
                    row.event().getEventTime());
        }
    }

    /** 关键数字（原文出现，禁编造）。 */
    public record FigureView(String label, String value, String unit) {

        static FigureView of(EventItem.KeyFigure figure) {
            return new FigureView(figure.label(), figure.value(), figure.unit());
        }
    }

    /** 关联标的（code 非空 = 已回联标的池，可跳标的详情）。 */
    public record SubjectView(String code, String name, String industry) {

        static SubjectView of(EventItem.SubjectRef subject) {
            return new SubjectView(subject.code(), subject.name(), subject.industry());
        }
    }
}
