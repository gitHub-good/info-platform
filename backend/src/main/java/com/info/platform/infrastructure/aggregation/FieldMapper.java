package com.info.platform.infrastructure.aggregation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 字段映射框架：把外部源原始字段名映射为内部规范字段名，并按 {@link Transform} 转换值。
 *
 * <p>对齐技术方案 §4.4「统一数据访问层」字段映射表与 ADR-0003。映射规则：
 *
 * <ul>
 *   <li>白名单映射——未出现在配置中的原始字段被丢弃，避免源私有字段泄漏进规范模型
 *   <li>配置中源字段在原始数据缺失或值为 null 时，对应目标字段不产出
 *   <li>转换失败（如不可解析的日期）抛 {@link FieldMappingException}，不吞
 * </ul>
 *
 * <p>映射配置为 {@link FieldMapping} 列表，可由 classpath JSON 资源加载（见 {@link #loadMapping}）， 也可运行时直接构造注入。配置
 * JSON 形如：
 *
 * <pre>{@code
 * [
 *   {"source":"price","target":"price","transform":"to_decimal"},
 *   {"source":"trade_date","target":"tradeDate","transform":"to_iso_date"}
 * ]
 * }</pre>
 */
public class FieldMapper {

    private static final Logger log = LoggerFactory.getLogger(FieldMapper.class);

    private static final List<DateTimeFormatter> DATEONLY_FORMATS =
            List.of(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd"),
                    DateTimeFormatter.ofPattern("yyyy/MM/dd"),
                    DateTimeFormatter.ofPattern("yyyyMMdd"));

    private static final List<DateTimeFormatter> DATETIME_FORMATS =
            List.of(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));

    private final ObjectMapper objectMapper;

    public FieldMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 必填");
    }

    /**
     * 从 classpath 加载映射配置 JSON。
     *
     * @param classpathLocation classpath 相对路径，如 {@code field-mapping/quote-sample.json}
     */
    public List<FieldMapping> loadMapping(String classpathLocation) {
        Objects.requireNonNull(classpathLocation, "classpathLocation 必填");
        try (InputStream in =
                FieldMapper.class.getClassLoader().getResourceAsStream(classpathLocation)) {
            if (in == null) {
                throw new FieldMappingException("映射配置不存在: " + classpathLocation);
            }
            List<Map<String, String>> raw =
                    objectMapper.readValue(in, new TypeReference<List<Map<String, String>>>() {});
            List<FieldMapping> result = new ArrayList<>();
            for (Map<String, String> entry : raw) {
                String source = entry.get("source");
                String target = entry.get("target");
                if (source == null || target == null) {
                    throw new FieldMappingException(
                            "映射条目缺 source/target: " + entry + " @ " + classpathLocation);
                }
                result.add(
                        new FieldMapping(source, target, Transform.from(entry.get("transform"))));
            }
            return List.copyOf(result);
        } catch (IOException e) {
            throw new FieldMappingException("加载映射配置失败: " + classpathLocation, e);
        }
    }

    /**
     * 按 {@code config} 将 {@code raw} 原始字段映射为规范字段，并转换值。
     *
     * <p>白名单：未匹配的原始字段丢弃；源字段缺失/null 时目标字段不产出。
     *
     * @return 不可变映射；不可变视图，调用方不可修改
     */
    public Map<String, Object> map(Map<String, Object> raw, List<FieldMapping> config) {
        Objects.requireNonNull(raw, "raw 必填");
        Objects.requireNonNull(config, "config 必填");
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (FieldMapping m : config) {
            if (!raw.containsKey(m.source())) {
                continue;
            }
            Object value = raw.get(m.source());
            if (value == null) {
                continue;
            }
            mapped.put(m.target(), applyTransform(value, m.transform()));
        }
        return Map.copyOf(mapped);
    }

    private Object applyTransform(Object value, Transform transform) {
        switch (transform) {
            case NONE:
                return value;
            case TO_STRING:
                return String.valueOf(value);
            case TO_LONG:
                return toLong(value);
            case TO_DECIMAL:
                return toDecimal(value);
            case TO_ISO_DATE:
                return toIsoDate(value);
            default:
                throw new IllegalStateException("未知 transform: " + transform);
        }
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string) {
            try {
                return Long.parseLong(string.trim());
            } catch (NumberFormatException e) {
                throw new FieldMappingException("TO_LONG 转换失败: " + string, e);
            }
        }
        throw new FieldMappingException("TO_LONG 不支持的类型: " + value.getClass());
    }

    private static BigDecimal toDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            // 用 toString 构造，避免 double 二次精度损失
            return new BigDecimal(number.toString());
        }
        if (value instanceof String string) {
            String cleaned = string.trim().replace(",", "");
            try {
                return new BigDecimal(cleaned);
            } catch (NumberFormatException e) {
                throw new FieldMappingException("TO_DECIMAL 转换失败: " + string, e);
            }
        }
        throw new FieldMappingException("TO_DECIMAL 不支持的类型: " + value.getClass());
    }

    private static String toIsoDate(Object value) {
        String raw = value.toString().trim();
        for (DateTimeFormatter formatter : DATETIME_FORMATS) {
            try {
                // ISO_LOCAL_DATE_TIME 始终带秒，避免 LocalDateTime.toString 在整秒时省略 ":00"
                return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
                        LocalDateTime.parse(raw, formatter));
            } catch (DateTimeParseException ignore) {
                // try next
            }
        }
        for (DateTimeFormatter formatter : DATEONLY_FORMATS) {
            try {
                return LocalDate.parse(raw, formatter).toString();
            } catch (DateTimeParseException ignore) {
                // try next
            }
        }
        throw new FieldMappingException("TO_ISO_DATE 无法解析: " + raw);
    }
}
