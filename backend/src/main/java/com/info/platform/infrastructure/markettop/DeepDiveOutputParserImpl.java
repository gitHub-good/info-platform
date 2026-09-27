package com.info.platform.infrastructure.markettop;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.markettop.Citation;
import com.info.platform.domain.markettop.DeepDiveOutputParser;
import com.info.platform.domain.markettop.DeepDiveOutputParser.Entry;
import com.info.platform.domain.markettop.DeepDiveOutputParser.Parsed;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 深析输出解析器（基础设施层，M21 T182）：LLM 原文 → {@link Parsed}——BriefContentParser 同款兜底链：空 content → empty；去
 * {@code ```json} fence；取首 {@code {} 到末 {@code }}；JsonNode 树读取（容忍未知字段）；citations 的 type/id 宽容读取
 * （非文本/缺字段条目跳过，解析失败 WARN 含片段返回 empty——调用方切模板兜底）。
 */
@Component
public class DeepDiveOutputParserImpl implements DeepDiveOutputParser {

    private static final Logger log = LoggerFactory.getLogger(DeepDiveOutputParserImpl.class);

    private final ObjectMapper parseMapper;

    public DeepDiveOutputParserImpl(ObjectMapper objectMapper) {
        this.parseMapper =
                objectMapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // BUG-M21-01 复测遗留：deepseek 混推理模型偶发未引号键名/单引号/尾逗号（实测 7/40 解析失败模式）
        // ——宽松读特性兜底（JSON 规范输出走默认路径零影响）
        this.parseMapper
                .configure(
                        com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES,
                        true)
                .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
                .configure(
                        com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_TRAILING_COMMA, true);
    }

    @Override
    public Optional<Parsed> parse(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return Optional.empty();
        }
        String stripped = stripFence(rawContent.trim());
        int first = stripped.indexOf('{');
        int last = stripped.lastIndexOf('}');
        if (first < 0 || last <= first) {
            log.warn("深析输出无 JSON 对象（切模板兜底）: head={}", head(stripped));
            return Optional.empty();
        }
        try {
            JsonNode root = parseMapper.readTree(stripped.substring(first, last + 1));
            return Optional.of(
                    new Parsed(
                            textOf(root.get("thesis")),
                            entriesOf(root.get("highlights")),
                            entriesOf(root.get("risks")),
                            dataNotesOf(root.get("dataNotes"))));
        } catch (Exception e) {
            log.warn("深析输出 JSON 解析失败（切模板兜底）: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 去 markdown 代码块围栏（```json ... ```）。 */
    private static String stripFence(String content) {
        if (!content.startsWith("```")) {
            return content;
        }
        int lineBreak = content.indexOf('\n');
        String body = lineBreak > 0 ? content.substring(lineBreak + 1) : content;
        int closing = body.lastIndexOf("```");
        return closing >= 0 ? body.substring(0, closing) : body;
    }

    private static List<Entry> entriesOf(JsonNode array) {
        List<Entry> entries = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return entries;
        }
        for (JsonNode node : array) {
            String text = textOf(node.get("text"));
            List<Citation> citations = new ArrayList<>();
            JsonNode citationNodes = node.get("citations");
            if (citationNodes != null && citationNodes.isArray()) {
                for (JsonNode citationNode : citationNodes) {
                    String type = textOf(citationNode.get("type"));
                    JsonNode idNode = citationNode.get("id");
                    if (type != null && idNode != null && idNode.canConvertToLong()) {
                        citations.add(new Citation(type, idNode.asLong()));
                    }
                }
            }
            entries.add(new Entry(text, citations));
        }
        return entries;
    }

    private static List<String> dataNotesOf(JsonNode array) {
        List<String> notes = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return notes;
        }
        for (JsonNode node : array) {
            String text = textOf(node);
            if (text != null) {
                notes.add(text);
            }
        }
        return notes;
    }

    private static String textOf(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static String head(String text) {
        return text.length() <= 60 ? text : text.substring(0, 60) + "…";
    }
}
