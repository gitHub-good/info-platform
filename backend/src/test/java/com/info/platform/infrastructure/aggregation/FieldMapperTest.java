package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * FieldMapper 单测（T02）：映射配置加载、各 transform 规则、未匹配字段处理。
 *
 * <p>AAA 结构，纯单元（不启 Spring 上下文）。覆盖率目标 ≥80%。
 */
class FieldMapperTest {

    private final FieldMapper fieldMapper = new FieldMapper(new ObjectMapper());

    @Test
    void loadMapping_sampleResource_parsesEntriesAndTransforms() {
        // Arrange & Act
        List<FieldMapping> mapping = fieldMapper.loadMapping("field-mapping/quote-sample.json");

        // Assert
        assertThat(mapping).hasSize(6);
        assertThat(transformOf(mapping, "ts_code")).isEqualTo(Transform.NONE);
        assertThat(transformOf(mapping, "name")).isEqualTo(Transform.TO_STRING);
        assertThat(transformOf(mapping, "price")).isEqualTo(Transform.TO_DECIMAL);
        assertThat(transformOf(mapping, "vol")).isEqualTo(Transform.TO_LONG);
        assertThat(transformOf(mapping, "trade_date")).isEqualTo(Transform.TO_ISO_DATE);
        assertThat(targetOf(mapping, "trade_date")).isEqualTo("tradeDate");
    }

    @Test
    void loadMapping_missingResource_throws() {
        assertThatThrownBy(() -> fieldMapper.loadMapping("field-mapping/no-such.json"))
                .isInstanceOf(FieldMappingException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void map_dotPathSource_navigatesNestedMaps() {
        // Arrange：金十快讯实口径（ADR-0042）——title/content 嵌于 data 子对象
        List<FieldMapping> config =
                List.of(
                        new FieldMapping("data.title", "title", Transform.TO_STRING),
                        new FieldMapping("data.content", "summary", Transform.TO_STRING),
                        new FieldMapping("id", "externalId", Transform.TO_STRING));
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("title", "");
        inner.put("content", "现货白银突破65美元/盎司");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", "20260925182244445800");
        raw.put("data", inner);

        // Act
        Map<String, Object> mapped = fieldMapper.map(raw, config);

        // Assert：嵌套导航命中 + 顶层键口径不变 + 缺失路径不产出
        assertThat(mapped.get("title")).isEqualTo("");
        assertThat(mapped.get("summary")).isEqualTo("现货白银突破65美元/盎司");
        assertThat(mapped.get("externalId")).isEqualTo("20260925182244445800");
        assertThat(mapped).doesNotContainKey("absent");

        // 中间层级非 Map / 任一级缺失：目标字段不产出（与顶层缺失同语义）
        Map<String, Object> broken = Map.of("data", "not-a-map");
        assertThat(fieldMapper.map(broken, config)).doesNotContainKeys("title", "summary");
        assertThat(fieldMapper.map(Map.of(), config)).isEmpty();
    }

    @Test
    void map_appliesTransformsAndDropsUnmatchedFields() {
        // Arrange
        List<FieldMapping> config =
                List.of(
                        new FieldMapping("price", "price", Transform.TO_DECIMAL),
                        new FieldMapping("pct_chg", "changePct", Transform.TO_DECIMAL),
                        new FieldMapping("vol", "volume", Transform.TO_LONG),
                        new FieldMapping("name", "name", Transform.TO_STRING),
                        new FieldMapping("trade_date", "tradeDate", Transform.TO_ISO_DATE),
                        new FieldMapping("absent_field", "absent", Transform.NONE));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("price", "1,680.50");
        raw.put("pct_chg", 1.23);
        raw.put("vol", "1000000");
        raw.put("name", 600519);
        raw.put("trade_date", "2026/09/20 15:30:00");
        raw.put("raw_only_field", "dropped");

        // Act
        Map<String, Object> mapped = fieldMapper.map(raw, config);

        // Assert
        assertThat(mapped).containsOnlyKeys("price", "changePct", "volume", "name", "tradeDate");
        assertThat((BigDecimal) mapped.get("price"))
                .isEqualByComparingTo(new BigDecimal("1680.50"));
        assertThat((BigDecimal) mapped.get("changePct"))
                .isEqualByComparingTo(new BigDecimal("1.23"));
        assertThat(mapped.get("volume")).isEqualTo(1_000_000L);
        assertThat(mapped.get("name")).isEqualTo("600519");
        assertThat(mapped.get("tradeDate")).isEqualTo("2026-09-20T15:30:00");
    }

    @Test
    void map_nullRawValue_skipsTarget() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("price", null);
        List<FieldMapping> config =
                List.of(new FieldMapping("price", "price", Transform.TO_DECIMAL));

        Map<String, Object> mapped = fieldMapper.map(raw, config);

        assertThat(mapped).doesNotContainKey("price");
    }

    @Test
    void map_isoDate_parsesDateOnlyPatterns() {
        List<FieldMapping> config = List.of(new FieldMapping("d", "d", Transform.TO_ISO_DATE));

        assertThat(fieldMapper.map(Map.of("d", "20260920"), config).get("d"))
                .isEqualTo("2026-09-20");
        assertThat(fieldMapper.map(Map.of("d", "2026-09-20"), config).get("d"))
                .isEqualTo("2026-09-20");
        assertThat(fieldMapper.map(Map.of("d", "2026/09/20"), config).get("d"))
                .isEqualTo("2026-09-20");
    }

    @Test
    void map_isoDate_unparseable_throws() {
        List<FieldMapping> config = List.of(new FieldMapping("d", "d", Transform.TO_ISO_DATE));

        assertThatThrownBy(() -> fieldMapper.map(Map.of("d", "not-a-date"), config))
                .isInstanceOf(FieldMappingException.class)
                .hasMessageContaining("TO_ISO_DATE");
    }

    @Test
    void map_toLong_fromNumberAndString() {
        List<FieldMapping> config = List.of(new FieldMapping("n", "n", Transform.TO_LONG));

        assertThat(fieldMapper.map(Map.of("n", 42L), config).get("n")).isEqualTo(42L);
        assertThat(fieldMapper.map(Map.of("n", "42"), config).get("n")).isEqualTo(42L);
    }

    @Test
    void map_toDecimal_fromNumberKeepsPrecision() {
        List<FieldMapping> config = List.of(new FieldMapping("p", "p", Transform.TO_DECIMAL));

        assertThat((BigDecimal) fieldMapper.map(Map.of("p", 0.1), config).get("p"))
                .isEqualByComparingTo(new BigDecimal("0.1"));
    }

    @Test
    void transformFrom_isCaseInsensitiveAndDefaultsNone() {
        assertThat(Transform.from("to_decimal")).isEqualTo(Transform.TO_DECIMAL);
        assertThat(Transform.from("NONE")).isEqualTo(Transform.NONE);
        assertThat(Transform.from(null)).isEqualTo(Transform.NONE);
        assertThat(Transform.from("")).isEqualTo(Transform.NONE);
        assertThatThrownBy(() -> Transform.from("bogus"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Transform transformOf(List<FieldMapping> mapping, String source) {
        return find(mapping, source).transform();
    }

    private static String targetOf(List<FieldMapping> mapping, String source) {
        return find(mapping, source).target();
    }

    private static FieldMapping find(List<FieldMapping> mapping, String source) {
        return mapping.stream()
                .filter(m -> m.source().equals(source))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到映射: " + source));
    }
}
