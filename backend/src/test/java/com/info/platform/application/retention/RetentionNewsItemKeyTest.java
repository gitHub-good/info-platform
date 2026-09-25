package com.info.platform.application.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.retention.ExpiredLogDeleter;
import com.info.platform.domain.retention.RetentionLogTable;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * T113（REQ-20260925-11 条目 7 / M13 方案 §8 挂账）回归：news_item 纳入 M10 清理体系—— 枚举白名单扩键（newsItemDays 默认 180
 * 下限 30）+ 清理轮覆盖（V22 表进遍历序与明细段）+ 执行侧回退 + 保存侧必填校验。
 *
 * <p>修前红锚点：本文件先于实现提交，断言「枚举含 NEWS_ITEM / 明细含 news_item 段 / 缺键校验拒绝」在旧代码上红灯。
 */
class RetentionNewsItemKeyTest {

    private static final String KEY = "retention.global";

    private static final Instant NOW = Instant.parse("2026-09-22T03:30:00.123Z");

    /** 四字段旧文档（T113 前形态）：newsItemDays 缺失，用于回退与校验拒绝断言。 */
    private static final String LEGACY_FOUR_FIELD_DOC =
            "{\"jobExecutionLogDays\":30,\"dataSourceEventDays\":14,"
                    + "\"llmCallLogDays\":90,\"readingEventDays\":90}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeConfigService configService = mock(RuntimeConfigService.class);
    private final ExpiredLogDeleter deleter = mock(ExpiredLogDeleter.class);
    private final RetentionConfigValidator validator = new RetentionConfigValidator();
    private RetentionCleanupService service;

    @BeforeEach
    void setUp() {
        service =
                new RetentionCleanupService(
                        configService, deleter, Clock.fixed(NOW, Clock.systemUTC().getZone()));
    }

    private void stubConfig(String json) {
        try {
            when(configService.read(KEY))
                    .thenReturn(
                            Optional.of(
                                    new RuntimeConfigEntry(
                                            KEY,
                                            json,
                                            objectMapper.readTree(json),
                                            null,
                                            Instant.parse("2026-09-22T01:00:00Z"))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void enum_whiteListContainsNewsItem_defaultsAndLimits() {
        RetentionLogTable news = RetentionLogTable.valueOf("NEWS_ITEM");

        assertThat(news.physicalName()).isEqualTo("news_item");
        assertThat(news.jsonField()).isEqualTo("newsItemDays");
        assertThat(news.defaultDays()).isEqualTo(180);
        assertThat(news.minDays()).isEqualTo(30);
    }

    @Test
    void resolve_newsItemDaysMissing_fallsBackToDefault180() {
        // 存量库四字段旧文档（键扩容上线即遇）：newsItemDays 缺失 → 字段级回退 180，不牵连其余字段
        RetentionWindows windows = RetentionWindows.resolve(jsonOf(LEGACY_FOUR_FIELD_DOC));

        assertThat(windows.of(RetentionLogTable.valueOf("NEWS_ITEM"))).isEqualTo(180);
    }

    @Test
    void runOnce_cleanupRoundIncludesNewsItemWithOwnCutoffAndSegment() {
        // 五字段文档：newsItemDays=30 采信 → cutoff = now − 30d；明细段含 news_item（V22 表纳入清理轮）
        stubConfig(
                "{\"jobExecutionLogDays\":30,\"dataSourceEventDays\":14,"
                        + "\"llmCallLogDays\":90,\"readingEventDays\":90,\"newsItemDays\":30}");
        when(deleter.deleteExpiredBefore(
                        any(RetentionLogTable.class), any(Instant.class), anyInt()))
                .thenReturn(0L);

        RetentionCleanupService.CleanupResult result = service.runOnce();

        assertThat(result.detail()).contains("news_item=0");
        verify(deleter)
                .deleteExpiredBefore(
                        RetentionLogTable.valueOf("NEWS_ITEM"),
                        Instant.parse("2026-08-23T03:30:00Z"),
                        500);
    }

    @Test
    void runOnce_newsItemRoundIsIdempotent_repeatedRoundsDeleteSameWindow() {
        // 幂等：两轮同窗重复执行，第二轮仍按同 cutoff 调用（删除按「严格早于边界」判定，结果收敛）
        stubConfig(
                "{\"jobExecutionLogDays\":30,\"dataSourceEventDays\":14,"
                        + "\"llmCallLogDays\":90,\"readingEventDays\":90,\"newsItemDays\":180}");
        when(deleter.deleteExpiredBefore(
                        any(RetentionLogTable.class), any(Instant.class), anyInt()))
                .thenReturn(0L);

        service.runOnce();
        service.runOnce();

        verify(deleter, org.mockito.Mockito.times(2))
                .deleteExpiredBefore(
                        org.mockito.ArgumentMatchers.eq(RetentionLogTable.valueOf("NEWS_ITEM")),
                        org.mockito.ArgumentMatchers.eq(Instant.parse("2026-03-26T03:30:00Z")),
                        org.mockito.ArgumentMatchers.eq(500));
    }

    @Test
    void validator_missingNewsItemDays_rejectedAsRequired() {
        // 保存侧：五字段全量替换语义——旧四字段文档缺 newsItemDays → 2001 必填（扩键即扩必填面）
        assertThatThrownBy(
                        () -> validator.validate(KEY, objectMapper.readTree(LEGACY_FOUR_FIELD_DOC)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("newsItemDays")
                .hasMessageContaining("必填");
    }

    @Test
    void validator_newsItemDaysBelowMin_rejected() {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        KEY,
                                        objectMapper.readTree(
                                                "{\"jobExecutionLogDays\":30,\"dataSourceEventDays\":14,"
                                                        + "\"llmCallLogDays\":90,\"readingEventDays\":90,"
                                                        + "\"newsItemDays\":29}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("newsItemDays")
                .hasMessageContaining("30");
    }

    private com.fasterxml.jackson.databind.JsonNode jsonOf(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
