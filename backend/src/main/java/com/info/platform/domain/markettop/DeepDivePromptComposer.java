package com.info.platform.domain.markettop;

import com.info.platform.domain.markettop.DeepDiveInput.EventFact;
import com.info.platform.domain.markettop.DeepDiveInput.FactorDim;
import com.info.platform.domain.markettop.DeepDiveInput.NewsFact;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 深析输入投影组装（M21 T182，方案 §4.4.2）：{@link DeepDiveInput} → 9 占位符键值（模板 USER 段注入源）。领域纯函数——不依赖
 * Jackson，手写紧凑 JSON 序列化（值经 {@link #escape} 转义），缺项如实空集/空串。
 *
 * <p>占位符与 {@code DeepDiveService}（PlaceholderProvider）注册清单同源同序维护（ADR-0022 惯例）。
 */
public final class DeepDivePromptComposer {

    private DeepDivePromptComposer() {}

    /** 9 占位符组装（模板渲染后不残留 {{key}}——种子模板与描述符清单同批维护）。 */
    public static Map<String, String> placeholders(DeepDiveInput input) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("subject", subject(input.subject()));
        placeholders.put("factors", factors(input.factors()));
        placeholders.put("totalScore", number(input.totalScore()));
        placeholders.put("percentile", String.valueOf(input.percentile()));
        placeholders.put("breakthrough", String.valueOf(input.breakthrough()));
        placeholders.put("topEvents", topEvents(input.topEvents()));
        placeholders.put("relatedNews", news(input.relatedNews()));
        placeholders.put("industryNews", news(input.industryNews()));
        placeholders.put("marketSnapshot", marketSnapshot(input.marketSnapshot()));
        return placeholders;
    }

    private static String subject(DeepDiveInput.SubjectRef subject) {
        StringBuilder json = new StringBuilder("{\"code\":");
        string(json, subject.code());
        json.append(",\"name\":");
        string(json, subject.name());
        json.append(",\"industry\":");
        string(json, subject.industry());
        return json.append('}').toString();
    }

    private static String factors(List<FactorDim> factors) {
        StringBuilder json = new StringBuilder("[");
        for (FactorDim factor : factors) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append("{\"key\":");
            string(json, factor.key());
            json.append(",\"name\":");
            string(json, factor.name());
            json.append(",\"score\":").append(number(factor.score()));
            json.append(",\"weight\":").append(number(factor.weight())).append('}');
        }
        return json.append(']').toString();
    }

    private static String topEvents(List<EventFact> events) {
        StringBuilder json = new StringBuilder("[");
        for (EventFact event : events) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append("{\"eventId\":").append(event.eventId());
            json.append(",\"summary\":");
            string(json, event.summary());
            json.append(",\"direction\":");
            string(json, event.direction());
            json.append(",\"importance\":");
            string(json, event.importance());
            json.append(",\"eventDate\":");
            string(json, event.eventDate());
            json.append(",\"coef\":").append(number(event.coef())).append('}');
        }
        return json.append(']').toString();
    }

    private static String news(List<NewsFact> newsList) {
        StringBuilder json = new StringBuilder("[");
        for (NewsFact news : newsList) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append("{\"newsId\":").append(news.newsId());
            json.append(",\"title\":");
            string(json, news.title());
            json.append(",\"publishedAt\":");
            string(json, news.publishedAt());
            json.append(",\"sourceName\":");
            string(json, news.sourceName());
            json.append('}');
        }
        return json.append(']').toString();
    }

    /** 行情快照（缺数键省略——§4.4.2 约定；全缺 → {}）。 */
    private static String marketSnapshot(Map<String, Double> snapshot) {
        StringBuilder json = new StringBuilder("{");
        for (Map.Entry<String, Double> field : snapshot.entrySet()) {
            if (field.getValue() == null) {
                continue;
            }
            if (json.length() > 1) {
                json.append(',');
            }
            string(json, field.getKey());
            json.append(':').append(number(field.getValue()));
        }
        return json.append('}').toString();
    }

    private static void string(StringBuilder json, String value) {
        json.append('"').append(escape(value)).append('"');
    }

    /** 数字文本化（去尾零：58.4 → "58.4"、30.0 → "30"——提示词内数字即输入事实，对账面以结构化字段为准）。 */
    private static String number(double value) {
        String text = String.format(java.util.Locale.ROOT, "%.6f", value).replaceAll("0+$", "");
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    /** JSON 字符串转义（引号/反斜杠/控制字符）。 */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        escaped.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
