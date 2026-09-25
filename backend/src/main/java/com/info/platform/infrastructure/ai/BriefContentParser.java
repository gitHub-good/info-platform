package com.info.platform.infrastructure.ai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.info.platform.domain.ai.BriefContent;
import com.info.platform.domain.ai.BriefContentCodec;
import com.info.platform.domain.ai.BriefFact;
import com.info.platform.domain.ai.BriefKeyEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AI 简报内容解析器（基础设施层，T21）：LLM 原始 content（JSON 字符串） ↔ {@link BriefContent} 领域值对象。
 *
 * <p>对齐 Spike-2 §5.3 解析兜底链：
 *
 * <ol>
 *   <li>空 content（JSON mode 有概率返回空，§5.2）→ 返回 {@link Optional#empty()}，应用层置 status=2。
 *   <li>去 {@code ```json} 代码块标记（模型可能违反"不输出 markdown"约束）。
 *   <li>取首个 {@code {} 到末个 {@code }} 之间的 JSON（剥离前后解释文字）。
 *   <li>Jackson 反序列化为 {@link BriefContent}，{@code FAIL_ON_UNKNOWN_PROPERTIES=false} 容忍模型多输出字段（如
 *       {@code topRecommend}）；缺失的 {@code keyEvents}/{@code facts} 数组由 {@link BriefContent} 紧凑构造器归空。
 *   <li>任一步失败 → 记 WARN（含 content 片段，不泄露全文）+ 返回 empty。
 *   <li>展示字段兜底（2026-09-22 id=13 实测）：{@code event}/{@code claim} 为 null/空时由 {@link
 *       #fillDisplayFallbacks} 后处理填充（reason 首句截断 / 「{metric} 数值」）——生成与读取双路径生效。
 * </ol>
 *
 * <p>反向序列化： {@link #writeJson} 产出规范 BriefContent JSON（落 {@code ai_brief.content}，去 fence/解释文字后的干净
 * JSON）； {@link #writeSourceLinks} 采集 {@code facts}/{@code keyEvents} 的 {@code sourceUrl} 去重后写 JSON
 * 数组（落 {@code ai_brief.source_links}，事实回链率 100% 验收）。
 *
 * <p>纯 JDK + Jackson（领域层 {@link BriefContent} 无注解，序列化靠字段名匹配，守护领域层不引框架）。 用 {@code
 * objectMapper.copy()} 单独配置解析器，不污染 Spring 共享 ObjectMapper。
 */
@Component
public class BriefContentParser implements BriefContentCodec {

    private static final Logger log = LoggerFactory.getLogger(BriefContentParser.class);

    /** 事件标题兜底截断上限（与模板 v1.1 的 event 10~20 字契约一致）。 */
    private static final int MAX_FALLBACK_TITLE_LENGTH = 20;

    /** 事件标题无可合成素材（event 与 reason 均空）时的泛化标题，保证展示不空。 */
    private static final String GENERIC_EVENT_TITLE = "关键事件";

    /** claim 兜底后缀（与 metric 拼为「{metric} 数值」）。 */
    private static final String CLAIM_FALLBACK_SUFFIX = " 数值";

    /** 句界标点（中英文句号/叹号/问号/分号与换行）——取首句作事件标题兜底。 */
    private static final String SENTENCE_DELIMITERS = "。！？!?；;\n";

    private final ObjectMapper parseMapper;
    private final ObjectMapper writer;

    public BriefContentParser(ObjectMapper objectMapper) {
        // copy 不影响 Spring 共享 ObjectMapper（如日期格式等全局配置）
        this.parseMapper =
                objectMapper
                        .copy()
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        // LLM 输出的数值容错（2026-09-25 真实事故）：模型可能给 "68.78%" / "1,234.56" /
                        // "0.53" 等字符串形式的数值，Jackson 默认对 Double 字段只收 JSON number 与纯数字串，
                        // 带单位/千分位直接整份解析失败 → 简报 FAILED。宽松反序列化容忍现实世界格式。
                        .registerModule(lenientDoubleModule());
        this.writer = objectMapper;
    }

    /** {@code Double} 字段宽松反序列化：JSON number 原样；字符串剥离 %、千分位逗号、空白后解析；无法解析返回 null（字段本就可空）。 */
    private static SimpleModule lenientDoubleModule() {
        SimpleModule module = new SimpleModule("lenient-double");
        module.addDeserializer(
                Double.class,
                new JsonDeserializer<Double>() {
                    @Override
                    public Double deserialize(JsonParser p, DeserializationContext ctxt)
                            throws IOException {
                        String raw = p.getValueAsString();
                        if (raw == null) {
                            return p.getNumberValue() == null
                                    ? null
                                    : p.getNumberValue().doubleValue();
                        }
                        String cleaned =
                                raw.replace("%", "").replace(",", "").replace("，", "").trim();
                        if (cleaned.isEmpty()
                                || "null".equalsIgnoreCase(cleaned)
                                || "-".equals(cleaned)) {
                            return null;
                        }
                        try {
                            return Double.parseDouble(cleaned);
                        } catch (NumberFormatException e) {
                            // 非数值字符串（如定性描述）——字段可空语义，置 null 不阻断整份简报
                            return null;
                        }
                    }
                });
        return module;
    }

    /**
     * 解析 LLM 原始 content 为 {@link BriefContent}。
     *
     * @param rawContent LLM 返回的原文（可能空/含 fence/含解释文字），可空
     * @return 解析成功→BriefContent；空/非法→{@link Optional#empty()}（已记 WARN）
     */
    public Optional<BriefContent> parse(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            log.warn("BriefContent 解析：content 为空（JSON mode 可能空 content，Spike-2 §5.2）");
            return Optional.empty();
        }
        String cleaned = stripFenceAndExtract(rawContent);
        if (cleaned == null) {
            return Optional.empty();
        }
        try {
            BriefContent content = parseMapper.readValue(cleaned, BriefContent.class);
            return Optional.of(fillDisplayFallbacks(content));
        } catch (Exception e) {
            log.warn("BriefContent 解析失败：{}（content 片段: {}）", e.getMessage(), snippet(cleaned));
            return Optional.empty();
        }
    }

    /**
     * 展示字段兜底后处理（2026-09-22 ai_brief id=13 实测）：v1.0 模板产物 {@code keyEvents[].event} 与 {@code
     * facts[].claim} 可能为 null/空（模型把内容写进 reason）→ 前端关键事件区无标题、事实表无陈述。
     *
     * <p>写在解析器而非领域结构：生成路径（parse→writeJson 归一化落库）与读取路径（getBrief 重解析存量
     * content）双路径生效——存量简报与新简报展示均不空；领域 record 不动。
     *
     * @param content 反序列化产物
     * @return event/claim 兜底填充后的副本（其余字段原样）
     */
    private static BriefContent fillDisplayFallbacks(BriefContent content) {
        if (content.keyEvents().isEmpty() && content.facts().isEmpty()) {
            return content;
        }
        List<BriefKeyEvent> events =
                content.keyEvents().stream().map(BriefContentParser::fallbackEvent).toList();
        List<BriefFact> facts =
                content.facts().stream().map(BriefContentParser::fallbackFact).toList();
        return new BriefContent(
                content.summary(),
                events,
                content.bias(),
                content.biasReason(),
                content.watchSuggestion(),
                facts,
                content.disclaimer(),
                content.topRecommend());
    }

    /** event 空 → reason 首句截断 ≤20 字；reason 亦空 → 泛化标题。 */
    private static BriefKeyEvent fallbackEvent(BriefKeyEvent event) {
        if (event.event() != null && !event.event().isBlank()) {
            return event;
        }
        return new BriefKeyEvent(
                fallbackTitle(event.reason()), event.impact(), event.reason(), event.sourceUrl());
    }

    /** 标题兜底：reason 首句（句界标点前）截断；无素材用泛化标题。 */
    private static String fallbackTitle(String reason) {
        String firstSentence = firstSentence(reason);
        if (firstSentence == null) {
            return GENERIC_EVENT_TITLE;
        }
        return firstSentence.length() <= MAX_FALLBACK_TITLE_LENGTH
                ? firstSentence
                : firstSentence.substring(0, MAX_FALLBACK_TITLE_LENGTH);
    }

    /** 取首个非空句（句界标点不保留）；全空返回 null。 */
    private static String firstSentence(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (String part : text.split("[" + SENTENCE_DELIMITERS + "]")) {
            String sentence = part.strip();
            if (!sentence.isEmpty()) {
                return sentence;
            }
        }
        return null;
    }

    /** claim 空 → 「{metric} 数值」；metric 亦空则保持 null（前端显示 —，无可合成素材）。 */
    private static BriefFact fallbackFact(BriefFact fact) {
        if (fact.claim() != null && !fact.claim().isBlank()) {
            return fact;
        }
        if (fact.metric() == null || fact.metric().isBlank()) {
            return fact;
        }
        return new BriefFact(
                fact.metric() + CLAIM_FALLBACK_SUFFIX,
                fact.metric(),
                fact.value(),
                fact.source(),
                fact.sourceUrl());
    }

    /** 序列化 BriefContent 为规范 JSON（落 {@code ai_brief.content}）。 */
    public String writeJson(BriefContent content) {
        try {
            return writer.writeValueAsString(content);
        } catch (Exception e) {
            log.warn("BriefContent 序列化失败：{}", e.getMessage());
            return "{}";
        }
    }

    /**
     * 采集 facts/keyEvents 的 sourceUrl 去重后写 JSON 数组（落 {@code ai_brief.source_links}）。
     *
     * @param content 已解析简报
     * @return JSON 数组字符串（如 {@code ["url1","url2"]}），无链接时为 {@code []}
     */
    public String writeSourceLinks(BriefContent content) {
        Set<String> urls = new LinkedHashSet<>();
        for (BriefFact f : content.facts()) {
            if (f.sourceUrl() != null && !f.sourceUrl().isBlank()) {
                urls.add(f.sourceUrl());
            }
        }
        for (BriefKeyEvent e : content.keyEvents()) {
            if (e.sourceUrl() != null && !e.sourceUrl().isBlank()) {
                urls.add(e.sourceUrl());
            }
        }
        try {
            return writer.writeValueAsString(new ArrayList<>(urls));
        } catch (Exception ex) {
            log.warn("sourceLinks 序列化失败：{}", ex.getMessage());
            return "[]";
        }
    }

    /** 解析存储的 sourceLinks JSON 数组为 List（GET 查询时还原）。 */
    @SuppressWarnings("unchecked")
    public List<String> readSourceLinks(String sourceLinksJson) {
        if (sourceLinksJson == null || sourceLinksJson.isBlank()) {
            return List.of();
        }
        try {
            return parseMapper.readValue(sourceLinksJson, List.class);
        } catch (Exception e) {
            log.warn("sourceLinks 反序列化失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** 去 {@code ```json} fence + 取首 {@code {} 到末 {@code }} ；无可解析 JSON 返回 null（已记 WARN）。 */
    private static String stripFenceAndExtract(String raw) {
        String s = raw.strip();
        // 去 ```json ... ``` 或 ``` ... ``` 代码块标记
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline >= 0) {
                s = s.substring(firstNewline + 1);
            }
            int fenceEnd = s.lastIndexOf("```");
            if (fenceEnd >= 0) {
                s = s.substring(0, fenceEnd);
            }
            s = s.strip();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end < start) {
            log.warn("BriefContent 解析：未找到合法 JSON 对象边界（content 片段: {}）", snippet(s));
            return null;
        }
        return s.substring(start, end + 1);
    }

    private static String snippet(String s) {
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
