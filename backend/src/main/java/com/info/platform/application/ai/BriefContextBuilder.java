package com.info.platform.application.ai;

import com.info.platform.application.aggregation.SubjectDetail;
import com.info.platform.domain.ai.BriefType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 提示词上下文构建器（应用层，T21）：把 {@link SubjectDetail} 聚合数据压成 {@code {{key}}→value} 的上下文 Map， 供 {@link
 * com.info.platform.application.ai.PromptTemplateService#render} 替换占位符。
 *
 * <p>对齐个股简报模板（V8 briefType=1）占位符：标的/行情/财务/估值/公告/新闻。缺源数据（该分区 sourceStatus != ok）时 该键写「暂无」而非保留 {@code
 * {{key}}}（缺失可见且不污染 LLM 输入）。
 *
 * <p>事件归因（2）/政策解读（3）/每日推荐（4）的专属占位符（eventTitle/policyTitle/subjectsMetrics 等）不在本构建器范围
 * （来自事件/政策/订阅上下文，T28/T23 装配），缺失则保留 {@code {{key}}} 由上游填充——本类只负责聚合数据投影。
 */
@Component
public class BriefContextBuilder {

    private static final String NA = "暂无";

    /** 构建上下文 Map（保序，便于人看组装后的 prompt）。 */
    public Map<String, String> build(SubjectDetail detail, BriefType briefType) {
        Map<String, String> ctx = new LinkedHashMap<>();
        if (detail == null) {
            return ctx;
        }
        // 标的基本信息
        SubjectDetail.SubjectInfo s = detail.subject();
        if (s != null) {
            ctx.put("subjectName", orNa(s.name()));
            ctx.put("subjectCode", orNa(s.subjectCode()));
            ctx.put("industry", orNa(s.industry()));
        }
        // 行情
        Map<String, Object> quote = detail.quote();
        ctx.put("price", str(quote, "price"));
        ctx.put("changePct", str(quote, "changePct"));
        ctx.put("preClose", str(quote, "preClose", "close"));
        // 财务（reportDate/revenue/netProfit/grossMargin/roe；netProfitYoy 源不产 → 暂无）
        Map<String, Object> fin = detail.finance();
        ctx.put("reportDate", str(fin, "reportDate"));
        ctx.put("revenue", str(fin, "revenue"));
        ctx.put("netProfit", str(fin, "netProfit"));
        ctx.put("netProfitYoy", NA);
        ctx.put("grossMargin", str(fin, "grossProfitMargin"));
        ctx.put("roe", str(fin, "roe"));
        // 估值（peTtm/pb；ps 源不产 → 暂无）
        Map<String, Object> val = detail.valuation();
        ctx.put("peTtm", str(val, "peTtm"));
        ctx.put("pb", str(val, "pb"));
        ctx.put("ps", NA);
        // 公告 / 新闻 列表（每条: 标题 | 时间 | url）
        ctx.put("announcementsList", formatItems(detail.announcements()));
        ctx.put("newsList", formatItems(detail.news()));
        return ctx;
    }

    /** 取首键值，缺失回退备选键，均无则「暂无」。 */
    private static String str(Map<String, Object> data, String key, String... fallbacks) {
        if (data == null || data.isEmpty()) {
            return NA;
        }
        Object v = data.get(key);
        for (int i = 0; (v == null || v.toString().isBlank()) && i < fallbacks.length; i++) {
            v = data.get(fallbacks[i]);
        }
        return v == null || v.toString().isBlank() ? NA : v.toString();
    }

    /** 列表项格式化为「标题 | 时间 | url」多行。 */
    private static String formatItems(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            return NA;
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> item : items) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(str(item, "title"))
                    .append(" | ")
                    .append(str(item, "publishedAt"))
                    .append(" | ")
                    .append(str(item, "url", "detailUrl"));
        }
        return sb.toString();
    }

    private static String orNa(String v) {
        return v == null || v.isBlank() ? NA : v;
    }
}
