package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * FieldMapper M13 三 transform 扩展单测（T102，方案 §4.3）：to_iso_datetime（墙钟缺秒/带秒，Asia/Shanghai→UTC）、
 * epoch_seconds_to_iso（字符串/数字）、strip_html（jsoup 抽纯文本）；既有五 transform 回归抽测。 白名单语义不变：未映射字段丢弃。
 */
class FieldMapperFeedTransformTest {

    private final FieldMapper mapper = new FieldMapper(new ObjectMapper());

    @Test
    void toIsoDatetime_minutePrecision_secondsPaddedAndZoneConverted() {
        Map<String, Object> mapped =
                mapper.map(
                        Map.of("time", "2026-09-22 09:31"),
                        List.of(
                                new FieldMapping(
                                        "time", "publishedAt", Transform.TO_ISO_DATETIME)));

        // 北京时间 09:31 → UTC 01:31，缺秒补 :00（Instant.toString 整秒定长）
        assertThat(mapped.get("publishedAt")).isEqualTo("2026-09-22T01:31:00Z");
    }

    @Test
    void toIsoDatetime_secondPrecision_kept() {
        Map<String, Object> mapped =
                mapper.map(
                        Map.of("time", "2026-09-22 09:31:05"),
                        List.of(new FieldMapping("time", "t", Transform.TO_ISO_DATETIME)));

        assertThat(mapped.get("t")).isEqualTo("2026-09-22T01:31:05Z");
    }

    @Test
    void toIsoDatetime_unparseable_throwsFieldMappingException() {
        assertThatThrownBy(
                        () ->
                                mapper.map(
                                        Map.of("time", "09/22 09:31"),
                                        List.of(
                                                new FieldMapping(
                                                        "time", "t",
                                                        Transform.TO_ISO_DATETIME))))
                .isInstanceOf(FieldMappingException.class)
                .hasMessageContaining("TO_ISO_DATETIME");
    }

    @Test
    void epochSeconds_stringAndNumber_bothConverted() {
        long epoch = 1748275048L;
        String expected = Instant.ofEpochSecond(epoch).toString();

        Map<String, Object> fromString =
                mapper.map(
                        Map.of("ctime", "1748275048"),
                        List.of(
                                new FieldMapping(
                                        "ctime", "t", Transform.EPOCH_SECONDS_TO_ISO)));
        Map<String, Object> fromNumber =
                mapper.map(
                        Map.of("ctime", 1748275048L),
                        List.of(
                                new FieldMapping(
                                        "ctime", "t", Transform.EPOCH_SECONDS_TO_ISO)));

        assertThat(fromString.get("t")).isEqualTo(expected);
        assertThat(fromNumber.get("t")).isEqualTo(expected);
    }

    @Test
    void epochSeconds_garbage_throwsFieldMappingException() {
        assertThatThrownBy(
                        () ->
                                mapper.map(
                                        Map.of("ctime", "not-a-number"),
                                        List.of(
                                                new FieldMapping(
                                                        "ctime", "t",
                                                        Transform.EPOCH_SECONDS_TO_ISO))))
                .isInstanceOf(FieldMappingException.class)
                .hasMessageContaining("EPOCH_SECONDS_TO_ISO");
    }

    @Test
    void stripHtml_extractsPlainText() {
        Map<String, Object> mapped =
                mapper.map(
                        Map.of("richtext", "<p>美联储<strong>维持</strong>利率不变</p>"),
                        List.of(new FieldMapping("richtext", "summary", Transform.STRIP_HTML)));

        assertThat(mapped.get("summary")).isEqualTo("美联储维持利率不变");
    }

    @Test
    void existingTransforms_regressionUnchanged() {
        // 既有五 transform 回归抽测（白名单语义不变，M13 零影响）
        Map<String, Object> mapped =
                mapper.map(
                        Map.of("title", 123, "price", "1,234.5", "date", "2026-09-22"),
                        List.of(
                                new FieldMapping("title", "t", Transform.TO_STRING),
                                new FieldMapping("price", "p", Transform.TO_DECIMAL),
                                new FieldMapping("date", "d", Transform.TO_ISO_DATE)));

        assertThat(mapped.get("t")).isEqualTo("123");
        assertThat(mapped.get("p")).isEqualTo(new java.math.BigDecimal("1234.5"));
        assertThat(mapped.get("d")).isEqualTo("2026-09-22");
        // 白名单：未映射字段丢弃
        assertThat(mapped).doesNotContainKey("price_raw");
    }

    @Test
    void transformFrom_wireNames_parseCaseInsensitive() {
        assertThat(Transform.from("to_iso_datetime")).isEqualTo(Transform.TO_ISO_DATETIME);
        assertThat(Transform.from("epoch_seconds_to_iso"))
                .isEqualTo(Transform.EPOCH_SECONDS_TO_ISO);
        assertThat(Transform.from("strip_html")).isEqualTo(Transform.STRIP_HTML);
        assertThat(Transform.from(" ")).isEqualTo(Transform.NONE);
    }
}
