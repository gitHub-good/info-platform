package com.info.platform.infrastructure.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.retention.RetentionConfigFacade;
import com.info.platform.application.retention.RetentionConfigFacade.Windows;
import com.info.platform.application.retention.RetentionConfigFacade.WindowsUpdate;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * PATCH /retention/windows 非法类型矩阵集成测试（D3 回归，PRD 故事 2 场景 3「非整数拒绝」）：五窗口字段按「JSON
 * 整数」严格校验——"14x"/true/14.5/"14" 均须 2001 PARAM_INVALID 字段级「须为整数」且原值保留；null/缺失 2001「必填」；合法整数 14 正常采信。
 *
 * <p>经 Spring 共享 ObjectMapper 做 {@code readValue} 绑定（与 HTTP 层 HttpMessageConverter 同一绑定器同一语义）+ 真实
 * Facade/校验器链（RuntimeConfigService.write 内联校验）——完整复现缺陷链路：修前 Integer 绑定层先炸（50000）或静默截断 （14.5→14
 * 采信），校验器的「须为整数」分支经此路径不可达。
 *
 * <p>数据治理：非法值在校验阶段即拒绝（不落库）；合法值用例写库后于 @AfterEach 恢复默认 30/14/90/90（共享内存库不留痕）。
 */
@SpringBootTest
@ActiveProfiles("test")
class RetentionWindowsUpdateTypeMatrixTest {

    @Autowired private RetentionConfigFacade facade;

    @Autowired private ObjectMapper objectMapper;

    @AfterEach
    void restoreDefaults() throws Exception {
        facade.update(
                bind(
                        "{\"jobExecutionLogDays\":30,\"dataSourceEventDays\":14,"
                                + "\"llmCallLogDays\":90,\"readingEventDays\":90,\"newsItemDays\":180}"));
    }

    /** 与 HTTP 层同一绑定语义：JSON 文本 → WindowsUpdate 记录。 */
    private WindowsUpdate bind(String json) throws Exception {
        return objectMapper.readValue(json, WindowsUpdate.class);
    }

    private Windows currentWindows() {
        return facade.view().windows();
    }

    private void assertRejectedAs2001(String json, String expectedMessagePart) throws Exception {
        Windows before = currentWindows();
        assertThatThrownBy(() -> facade.update(bind(json)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.PARAM_INVALID))
                .hasMessageContaining(expectedMessagePart);
        // 原值保留：失败不落库，GET 回读不变
        assertThat(currentWindows()).isEqualTo(before);
    }

    @Test
    void update_nonIntegerString_rejectedAs2001FieldLevel() throws Exception {
        // "14x"（D3 探针用例）：修前 Jackson 绑定 String→Integer 失败直落兜底 50000
        assertRejectedAs2001(
                "{\"jobExecutionLogDays\":30,\"dataSourceEventDays\":\"14x\","
                        + "\"llmCallLogDays\":90,\"readingEventDays\":90,\"newsItemDays\":180}",
                "dataSourceEventDays: 须为整数");
    }

    @Test
    void update_booleanValue_rejectedAs2001FieldLevel() throws Exception {
        // true（D3 探针用例）：修前 Jackson 绑定 Boolean→Integer 失败直落兜底 50000
        assertRejectedAs2001(
                "{\"jobExecutionLogDays\":30,\"dataSourceEventDays\":true,"
                        + "\"llmCallLogDays\":90,\"readingEventDays\":90,\"newsItemDays\":180}",
                "dataSourceEventDays: 须为整数");
    }

    @Test
    void update_fractionalNumber_rejectedAs2001FieldLevel() throws Exception {
        // 14.5（D3 探针用例）：修前静默截断为 14 采信，违背「非整数拒绝」字面契约
        assertRejectedAs2001(
                "{\"jobExecutionLogDays\":30,\"dataSourceEventDays\":14.5,"
                        + "\"llmCallLogDays\":90,\"readingEventDays\":90,\"newsItemDays\":180}",
                "dataSourceEventDays: 须为整数");
    }

    @Test
    void update_numericString_rejectedAs2001FieldLevel() throws Exception {
        // "14"（字符串数字）：JSON 字符串非 JSON 整数，同口径拒绝（修前会被 Jackson 字符串强转采信）
        assertRejectedAs2001(
                "{\"jobExecutionLogDays\":30,\"dataSourceEventDays\":\"14\","
                        + "\"llmCallLogDays\":90,\"readingEventDays\":90,\"newsItemDays\":180}",
                "dataSourceEventDays: 须为整数");
    }

    @Test
    void update_nullValue_rejectedAs2001Required() throws Exception {
        // null（矩阵完备）：字段级「必填」语义不变（修前修后一致）
        assertRejectedAs2001(
                "{\"jobExecutionLogDays\":30,\"dataSourceEventDays\":null,"
                        + "\"llmCallLogDays\":90,\"readingEventDays\":90,\"newsItemDays\":180}",
                "dataSourceEventDays: 必填");
    }

    @Test
    void update_jsonInteger_acceptedAndEffective() throws Exception {
        // 14（矩阵完备）：合法 JSON 整数正常采信（修前修后一致；写后回读新值，@AfterEach 恢复默认）
        RetentionConfigFacade.WindowsView view =
                facade.update(
                        bind(
                                "{\"jobExecutionLogDays\":30,\"dataSourceEventDays\":14,"
                                        + "\"llmCallLogDays\":90,\"readingEventDays\":90,\"newsItemDays\":180}"));

        assertThat(view.windows().dataSourceEventDays()).isEqualTo(14);
        assertThat(currentWindows().dataSourceEventDays()).isEqualTo(14);
    }
}
