package com.info.platform.application.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.retention.RetentionLogTable;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * RetentionWindows 解析单测（T71，方案 §4.2 执行侧防御；T113 扩 newsItemDays）：全字段采信 / 键缺失全默认 / 逐字段独立回退（缺失、非整型、低于下限
 * 各自回退该表 defaultDays，好字段不受牵连）/ 恰等于下限采信 / of(table) 映射。纯函数单测（无 Spring 上下文）。
 */
class RetentionWindowsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode doc(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void resolve_fullDocument_adoptsAllFourWindows() {
        // Arrange + Act：四字段均合法且各不相同（30/14 两窗口交叉）
        RetentionWindows windows =
                RetentionWindows.resolve(
                        doc(
                                "{\"jobExecutionLogDays\":10,\"dataSourceEventDays\":5,"
                                        + "\"llmCallLogDays\":40,\"readingEventDays\":50,\"newsItemDays\":200}"));

        // Assert
        assertThat(windows.jobExecutionLogDays()).isEqualTo(10);
        assertThat(windows.dataSourceEventDays()).isEqualTo(5);
        assertThat(windows.llmCallLogDays()).isEqualTo(40);
        assertThat(windows.readingEventDays()).isEqualTo(50);
        assertThat(windows.newsItemDays()).isEqualTo(200);
    }

    @Test
    void resolve_nullDocument_allDefaults() {
        // Arrange + Act：键缺失（种子前/被删）→ 全默认，绝不按 0 全删（执行侧防御）
        RetentionWindows windows = RetentionWindows.resolve(null);

        // Assert：默认窗口 30/14/90/90/180（枚举单一事实源，T113 扩 newsItem）
        assertThat(windows).isEqualTo(new RetentionWindows(30, 14, 90, 90, 180));
    }

    @Test
    void resolve_missingField_fallsBackPerField_goodFieldsUnaffected() {
        // Arrange：仅两字段存在（其余缺失）
        RetentionWindows windows =
                RetentionWindows.resolve(doc("{\"jobExecutionLogDays\":10,\"llmCallLogDays\":40}"));

        // Assert：存在字段采信、缺失字段各自回退默认（字段级回退，不整文档废弃）
        assertThat(windows.jobExecutionLogDays()).isEqualTo(10);
        assertThat(windows.dataSourceEventDays()).isEqualTo(14);
        assertThat(windows.llmCallLogDays()).isEqualTo(40);
        assertThat(windows.readingEventDays()).isEqualTo(90);
        // newsItemDays 缺失 → 字段级回退 180（存量四字段键兼容）
        assertThat(windows.newsItemDays()).isEqualTo(180);
    }

    /** 非法值矩阵：0 / 负数 / 非整数（文本、小数、布尔、null）——绕过校验器直写库的执行侧兜底。 */
    static Stream<String> illegalValues() {
        return Stream.of("0", "-5", "\"30\"", "30.5", "true", "null");
    }

    @ParameterizedTest
    @MethodSource("illegalValues")
    void resolve_illegalValue_fallsBackToDefault_notZero(String raw) {
        // Arrange：jobExecutionLogDays 被写坏，其余字段合法
        RetentionWindows windows =
                RetentionWindows.resolve(
                        doc("{\"jobExecutionLogDays\":" + raw + ",\"dataSourceEventDays\":5}"));

        // Assert：坏字段回退 30（不是 0——「设 0 清空」不可达），好字段照常采信
        assertThat(windows.jobExecutionLogDays()).isEqualTo(30);
        assertThat(windows.dataSourceEventDays()).isEqualTo(5);
    }

    @Test
    void resolve_valueAtMin_adopted() {
        // Arrange：四字段恰取各自下限 7/2/35/35（合法边界）
        RetentionWindows windows =
                RetentionWindows.resolve(
                        doc(
                                "{\"jobExecutionLogDays\":7,\"dataSourceEventDays\":2,"
                                        + "\"llmCallLogDays\":35,\"readingEventDays\":35,\"newsItemDays\":30}"));

        // Assert
        assertThat(windows).isEqualTo(new RetentionWindows(7, 2, 35, 35, 30));
    }

    @Test
    void of_mapsEachTableToItsWindow() {
        RetentionWindows windows = new RetentionWindows(10, 5, 40, 50, 200);

        assertThat(windows.of(RetentionLogTable.JOB_EXECUTION_LOG)).isEqualTo(10);
        assertThat(windows.of(RetentionLogTable.DATA_SOURCE_EVENT)).isEqualTo(5);
        assertThat(windows.of(RetentionLogTable.LLM_CALL_LOG)).isEqualTo(40);
        assertThat(windows.of(RetentionLogTable.READING_EVENT)).isEqualTo(50);
        assertThat(windows.of(RetentionLogTable.NEWS_ITEM)).isEqualTo(200);
    }

    @Test
    void defaults_returnsEnumSourcedDefaults() {
        // 枚举默认值与 resolve(null) 等价（校验器/种子/解析三方共用枚举常量）
        assertThat(RetentionWindows.defaults()).isEqualTo(RetentionWindows.resolve(null));
        assertThat(RetentionWindows.defaults().of(RetentionLogTable.JOB_EXECUTION_LOG))
                .isEqualTo(RetentionLogTable.JOB_EXECUTION_LOG.defaultDays());
    }
}
