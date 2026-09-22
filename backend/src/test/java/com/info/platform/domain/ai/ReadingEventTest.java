package com.info.platform.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * ReadingEvent / ReadingEventType 单测（T29）：工厂校验、重建往返、类型解析。AAA 结构。
 *
 * <p>纯领域对象，无外部依赖。
 */
class ReadingEventTest {

    private static final long USER_ID = 1L;
    private static final long SUBJECT_ID = 100L;

    @Test
    void record_normal_buildsEventWithTrimmedRef() {
        // Arrange / Act
        ReadingEvent event =
                ReadingEvent.record(
                        USER_ID, SUBJECT_ID, ReadingEventType.SUBJECT_DETAIL, " SH600519 ");

        // Assert：引用 trim、id/createdAt 留空待回填
        assertThat(event.getUserId()).isEqualTo(USER_ID);
        assertThat(event.getSubjectId()).isEqualTo(SUBJECT_ID);
        assertThat(event.getContentType()).isEqualTo(ReadingEventType.SUBJECT_DETAIL);
        assertThat(event.getContentRef()).isEqualTo("SH600519");
        assertThat(event.getId()).isNull();
        assertThat(event.getCreatedAt()).isNull();
    }

    @Test
    void record_policyRead_nullSubjectAllowed() {
        // Arrange / Act：政策阅读无标的关联（subjectId=null 合法）
        ReadingEvent event = ReadingEvent.record(USER_ID, null, ReadingEventType.POLICY, "42");

        // Assert
        assertThat(event.getSubjectId()).isNull();
        assertThat(event.getContentType()).isEqualTo(ReadingEventType.POLICY);
    }

    @Test
    void record_blankRef_rejected() {
        // Arrange / Act / Assert：空引用拒绝（埋点侧转 400）
        assertThatThrownBy(
                        () ->
                                ReadingEvent.record(
                                        USER_ID, SUBJECT_ID, ReadingEventType.POLICY, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contentRef");
    }

    @Test
    void record_overlongRef_rejected() {
        // Arrange：超长引用（>200）
        String overlong = "x".repeat(ReadingEvent.CONTENT_REF_MAX_LENGTH + 1);

        // Act / Assert
        assertThatThrownBy(
                        () ->
                                ReadingEvent.record(
                                        USER_ID, SUBJECT_ID, ReadingEventType.POLICY, overlong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超长");
    }

    @Test
    void reconstruct_roundTrip_keepsAllFields() {
        // Arrange
        Instant createdAt = Instant.parse("2026-09-22T01:02:03Z");

        // Act
        ReadingEvent event =
                ReadingEvent.reconstruct(
                        9L, USER_ID, SUBJECT_ID, ReadingEventType.AI_BRIEF, "7", createdAt);

        // Assert
        assertThat(event.getId()).isEqualTo(9L);
        assertThat(event.getUserId()).isEqualTo(USER_ID);
        assertThat(event.getSubjectId()).isEqualTo(SUBJECT_ID);
        assertThat(event.getContentType()).isEqualTo(ReadingEventType.AI_BRIEF);
        assertThat(event.getContentRef()).isEqualTo("7");
        assertThat(event.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    void fromName_knownAndUnknownValues() {
        // Arrange / Act / Assert：已知值解析（含大小写与空白容错）
        assertThat(ReadingEventType.fromName("SUBJECT_DETAIL"))
                .isEqualTo(ReadingEventType.SUBJECT_DETAIL);
        assertThat(ReadingEventType.fromName(" policy ")).isEqualTo(ReadingEventType.POLICY);
        assertThat(ReadingEventType.fromName("AI_BRIEF").persistentName()).isEqualTo("AI_BRIEF");
        // 未知/空值拒绝
        assertThatThrownBy(() -> ReadingEventType.fromName("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReadingEventType.fromName(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
