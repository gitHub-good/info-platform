package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.ai.ReadingEvent;
import com.info.platform.domain.ai.ReadingEventRepository;
import com.info.platform.domain.ai.ReadingEventType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * ReadingEventRepositoryImpl 集成测试（T29）：SQLite 共享内存库 + Flyway（V14 建 reading_event 表）后， 测 save
 * 往返、existsSince 窗口去重与 findByUserSince 过滤/排序/limit。对齐 LlmCallLogRepositoryImplTest 模式。
 */
@SpringBootTest
@ActiveProfiles("test")
class ReadingEventRepositoryImplTest {

    @Autowired private ReadingEventRepository repository;

    @Test
    void save_roundTripAndBackfillsId() {
        // Arrange
        ReadingEvent event =
                ReadingEvent.record(1001L, 100L, ReadingEventType.SUBJECT_DETAIL, "SH600519");

        // Act
        ReadingEvent saved = repository.save(event);

        // Assert：回填 id/createdAt，各字段往返无损
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUserId()).isEqualTo(1001L);
        assertThat(saved.getSubjectId()).isEqualTo(100L);
        assertThat(saved.getContentType()).isEqualTo(ReadingEventType.SUBJECT_DETAIL);
        assertThat(saved.getContentRef()).isEqualTo("SH600519");
    }

    @Test
    void save_policyReadNullSubject_roundTripKeepsNull() {
        // Arrange / Act：政策阅读无标的关联
        ReadingEvent saved =
                repository.save(ReadingEvent.record(1001L, null, ReadingEventType.POLICY, "42"));

        // Assert：subjectId 往返仍为 null
        assertThat(saved.getSubjectId()).isNull();
        assertThat(saved.getContentType()).isEqualTo(ReadingEventType.POLICY);
    }

    @Test
    void existsSince_matchesOnlySameUserTypeRefInWindow() {
        // Arrange：用户 A 读 SH600519 + 用户 B 读同内容 + 用户 A 读政策
        repository.save(
                ReadingEvent.record(2001L, null, ReadingEventType.SUBJECT_DETAIL, "SH600519"));
        repository.save(
                ReadingEvent.record(2002L, null, ReadingEventType.SUBJECT_DETAIL, "SH600519"));
        repository.save(ReadingEvent.record(2001L, null, ReadingEventType.POLICY, "42"));

        // Act：过去 1h 窗口（save 落库时间为 now，必命中）
        boolean hitSame =
                repository.existsSince(
                        2001L,
                        ReadingEventType.SUBJECT_DETAIL,
                        "SH600519",
                        Instant.now().minus(1, ChronoUnit.HOURS));
        boolean missOtherUser =
                repository.existsSince(
                        2003L,
                        ReadingEventType.SUBJECT_DETAIL,
                        "SH600519",
                        Instant.now().minus(1, ChronoUnit.HOURS));
        boolean missOtherRef =
                repository.existsSince(
                        2001L,
                        ReadingEventType.SUBJECT_DETAIL,
                        "SH000001",
                        Instant.now().minus(1, ChronoUnit.HOURS));
        boolean missFutureWindow =
                repository.existsSince(
                        2001L,
                        ReadingEventType.SUBJECT_DETAIL,
                        "SH600519",
                        Instant.now().plusSeconds(60));

        // Assert：同 user+type+ref+窗口命中；任一维度不同不命中
        assertThat(hitSame).isTrue();
        assertThat(missOtherUser).isFalse();
        assertThat(missOtherRef).isFalse();
        assertThat(missFutureWindow).isFalse();
    }

    @Test
    void findByUserSince_filtersByUserAndWindow_ordersNewestFirst() {
        // Arrange：同用户连续两条 + 他用户一条
        Long olderId =
                repository
                        .save(
                                ReadingEvent.record(
                                        3001L, 100L, ReadingEventType.SUBJECT_DETAIL, "SH600519"))
                        .getId();
        Long newerId =
                repository
                        .save(ReadingEvent.record(3001L, 200L, ReadingEventType.AI_BRIEF, "7"))
                        .getId();
        repository.save(
                ReadingEvent.record(3002L, 100L, ReadingEventType.SUBJECT_DETAIL, "SH600519"));

        // Act：过去 1h 窗口取该用户记录
        List<ReadingEvent> found =
                repository.findByUserSince(3001L, Instant.now().minus(1, ChronoUnit.HOURS), 10);
        List<ReadingEvent> future =
                repository.findByUserSince(3001L, Instant.now().plusSeconds(60), 10);

        // Assert：只含该用户、newest-first（id DESC）；未来窗口空
        assertThat(found).extracting(ReadingEvent::getId).containsSubsequence(newerId, olderId);
        assertThat(found).allSatisfy(e -> assertThat(e.getUserId()).isEqualTo(3001L));
        assertThat(future).isEmpty();
    }

    @Test
    void findByUserSince_limitGuardsResultSize() {
        // Arrange：写入 3 条
        for (int i = 0; i < 3; i++) {
            repository.save(ReadingEvent.record(4001L, null, ReadingEventType.POLICY, "p" + i));
        }

        // Act：limit=2
        List<ReadingEvent> found =
                repository.findByUserSince(4001L, Instant.now().minus(1, ChronoUnit.HOURS), 2);

        // Assert：上限护栏生效
        assertThat(found).hasSize(2);
    }
}
