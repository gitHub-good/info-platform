package com.info.platform.domain.analysis;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 行业日报仓储端口（{@code industry_daily_report}，M15 T124，方案 §4.5）。 领域层纯净接口：写路径 UPSERT（UNIQUE(report_date)
 * ——FAILED 重生成与降级覆盖收敛为一行）；读路径供日报列表（游标分页）/详情（30078 语义由应用层把守）与统计注入取数（跨 {@code
 * news_analysis/news_item/event_item} join，日窗行数量级毫秒级——ADR-0046 裁决 1 论证）。
 */
public interface DailyReportRepository {

    /** 按日取日报（详情/retry 校验/幂等判断）。 */
    Optional<IndustryDailyReport> findByReportDate(String reportDate);

    /**
     * 日报列表页：report_date DESC 等价 id DESC 游标分页（每日一行单调，方案 §4.8 beforeId + limit）。
     *
     * @param beforeId id 游标（id &lt; beforeId；null = 首页）
     * @param limit 页大小（应用层校验 1~50）
     */
    List<IndustryDailyReport> findPage(Long beforeId, int limit);

    /**
     * UPSERT 日报（by report_date 整行替换：FAILED 重生成 / 重试覆盖）。
     *
     * @return 落库后的实体（回填 id/时间戳）
     */
    IndustryDailyReport upsert(IndustryDailyReport report);

    /**
     * 日窗各行业资讯计数（L1 聚合统计注入数据面，方案 §4.5 步骤 1）：PASS + DONE 条目按 {@code main_category} 分组（含容器 4
     * 枚举——「含容器计数」语义），仅返回计数 &gt;0 的行业。
     *
     * @param publishedFromIso news_item.published_at 下界（含，前一日上海零点）
     * @param publishedToIso news_item.published_at 上界（不含，当日上海零点）
     */
    List<IndustryCount> countNewsByIndustry(String publishedFromIso, String publishedToIso);

    /**
     * 日窗全部结构化事件（事件精选与各行业事件计数数据面）：按重要度降序（HIGH &gt; MEDIUM &gt; LOW）、id 升序稳定序。
     *
     * @param eventDate Asia/Shanghai yyyy-MM-dd（event_item.event_date 口径）
     * @param cap 取数上限（防御性；正常水位 ~130/日）
     */
    List<ReportEvent> findEventsByDate(String eventDate, int cap);

    /** 行业资讯计数行（category = 35 枚举之一，含容器）。 */
    record IndustryCount(String category, long newsCount) {}

    /**
     * 事件精选投影（{@code event_item} 行；figures 线 JSON 由消费方解析，affected 已解析为枚举数组）。
     * T163 trace-v1 溯源增量：sourceName/newsUrl 由查询 join news_item/info_source 补出（新报告
     * content 升 A 级；历史行 null 由前端判空降级）。
     *
     * @param figuresJson key_figures 列原文（JSON 数组文本，可空）
     * @param sourceName 资讯源名（join info_source.name，可空）
     * @param newsUrl 原文外链（join news_item.url，可空）
     */
    record ReportEvent(
            long eventId,
            long newsId,
            EventType eventType,
            String summary,
            List<String> industries,
            Direction direction,
            Importance importance,
            String quote,
            String figuresJson,
            Instant eventTime,
            String sourceName,
            String newsUrl) {}
}
