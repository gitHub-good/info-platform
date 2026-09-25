package com.info.platform.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.ai.ReadingEvent;
import com.info.platform.domain.ai.ReadingEventRepository;
import com.info.platform.domain.ai.ReadingEventType;
import com.info.platform.domain.common.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * ReadingEventService 单测（T29）：埋点受理主路径 / 窗口去重（幂等友好）/ 标的解析 / 参数校验。AAA 结构。
 *
 * <p>mock ReadingEventRepository 与 SubjectRepository，Clock 固定；不依赖容器与真实库。
 */
class ReadingEventServiceTest {

    private static final long USER_ID = 1L;
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-22T08:00:00Z"), ZoneOffset.UTC);

    private ReadingEventRepository repository;
    private SubjectRepository subjectRepository;
    private ReadingEventService service;

    @BeforeEach
    void setUp() {
        repository = mock(ReadingEventRepository.class);
        subjectRepository = mock(SubjectRepository.class);
        service = new ReadingEventService(repository, subjectRepository, CLOCK);
        when(repository.existsSince(anyLong(), any(), any(), any())).thenReturn(false);
        when(repository.save(any(ReadingEvent.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void record_subjectDetail_resolvesCodeAndSaves() {
        // Arrange：详情页埋点带标的代码，代码可解析
        when(subjectRepository.findByCode(SubjectCode.of("SH600519")))
                .thenReturn(Optional.of(subject(100L, "SH600519")));

        // Act
        boolean recorded = service.record(USER_ID, "SUBJECT_DETAIL", "SH600519", "SH600519", null);

        // Assert：落库且 subjectId 解析成功
        assertThat(recorded).isTrue();
        ArgumentCaptor<ReadingEvent> captor = ArgumentCaptor.forClass(ReadingEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSubjectId()).isEqualTo(100L);
        assertThat(captor.getValue().getContentType()).isEqualTo(ReadingEventType.SUBJECT_DETAIL);
    }

    @Test
    void record_policy_withoutSubject_savesWithNullSubject() {
        // Arrange / Act：政策阅读无标的关联
        boolean recorded = service.record(USER_ID, "POLICY", "42", null, null);

        // Assert：照常落库（subjectId=null），不拒收
        assertThat(recorded).isTrue();
        verify(repository).save(any(ReadingEvent.class));
    }

    @Test
    void record_unknownCode_savesWithNullSubject() {
        // Arrange：标的代码解析不到（已删）
        when(subjectRepository.findByCode(SubjectCode.of("SH999999"))).thenReturn(Optional.empty());

        // Act
        boolean recorded = service.record(USER_ID, "SUBJECT_DETAIL", "SH999999", "SH999999", null);

        // Assert：不因解析失败拒收（subjectId=null 落库）
        assertThat(recorded).isTrue();
        ArgumentCaptor<ReadingEvent> captor = ArgumentCaptor.forClass(ReadingEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSubjectId()).isNull();
    }

    @Test
    void record_duplicateWithinWindow_skipsAndReturnsFalse() {
        // Arrange：同 user+type+ref 在去重窗口内已存在（StrictMode 双触发/前端重试）
        when(repository.existsSince(eq(USER_ID), eq(ReadingEventType.AI_BRIEF), eq("7"), any()))
                .thenReturn(true);

        // Act
        boolean recorded = service.record(USER_ID, "AI_BRIEF", "7", null, 200L);

        // Assert：recorded=false 且不落库
        assertThat(recorded).isFalse();
        verify(repository, never()).save(any());
    }

    @Test
    void record_invalidType_paramInvalid() {
        // Arrange / Act / Assert：未知内容类型 → 2001（400）
        assertThatThrownBy(() -> service.record(USER_ID, "VIDEO", "x", null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("contentType");
    }

    @Test
    void record_blankRef_paramInvalid() {
        // Arrange / Act / Assert：空引用 → 2001（400）
        assertThatThrownBy(() -> service.record(USER_ID, "POLICY", " ", null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("contentRef");
    }

    @Test
    void record_subjectIdTakesPrecedenceOverCode() {
        // Arrange：同时给 subjectId 与 subjectCode，id 优先且不查代码
        // Act
        service.record(USER_ID, "AI_BRIEF", "7", "SH600519", 200L);

        // Assert：直接用 subjectId=200，不触发 findByCode
        verify(subjectRepository, never()).findByCode(any());
        ArgumentCaptor<ReadingEvent> captor = ArgumentCaptor.forClass(ReadingEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSubjectId()).isEqualTo(200L);
    }

    @Test
    void record_feedType_withSubjectCode_resolvesAndSaves() {
        // Arrange：信息流「点原文」埋点（REQ-20260925-08 故事 1 场景 1）——FEED + 稳定 contentId + 标的代码可解析
        when(subjectRepository.findByCode(SubjectCode.of("SH600519")))
                .thenReturn(Optional.of(subject(100L, "SH600519")));

        // Act
        boolean recorded = service.record(USER_ID, "FEED", "announce:a1", "SH600519", null);

        // Assert：受理落库，subjectId 由 subjectCode 解析，contentType=FEED
        assertThat(recorded).isTrue();
        ArgumentCaptor<ReadingEvent> captor = ArgumentCaptor.forClass(ReadingEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getContentType()).isEqualTo(ReadingEventType.FEED);
        assertThat(captor.getValue().getSubjectId()).isEqualTo(100L);
        assertThat(captor.getValue().getContentRef()).isEqualTo("announce:a1");
    }

    @Test
    void record_feedType_withoutSubject_savesWithNullSubject() {
        // Arrange / Act：政策条目 FEED 埋点无标的关联（REQ-20260925-08 故事 1 场景 4：只留痕，不拒收）
        boolean recorded = service.record(USER_ID, "FEED", "policy:42", null, null);

        // Assert：照常落库（subjectId=null，画像侧跳过）
        assertThat(recorded).isTrue();
        ArgumentCaptor<ReadingEvent> captor = ArgumentCaptor.forClass(ReadingEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getContentType()).isEqualTo(ReadingEventType.FEED);
        assertThat(captor.getValue().getSubjectId()).isNull();
    }

    @Test
    void record_feedDuplicateWithinWindow_skipsAndReturnsFalse() {
        // Arrange：同 user+FEED+contentRef 1 小时窗口内已存在（REQ-20260925-08 故事 1 场景 2）
        when(repository.existsSince(
                        eq(USER_ID), eq(ReadingEventType.FEED), eq("announce:a1"), any()))
                .thenReturn(true);

        // Act
        boolean recorded = service.record(USER_ID, "FEED", "announce:a1", "SH600519", null);

        // Assert：recorded=false 且不落库（去重键含 contentType=FEED，与其他类型互不干扰）
        assertThat(recorded).isFalse();
        verify(repository, never()).save(any());
    }

    // ==================== fixtures ====================

    private static Subject subject(long id, String code) {
        return Subject.reconstruct(
                id,
                SubjectCode.of(code),
                com.info.platform.domain.aggregation.Market.A_SHARE,
                com.info.platform.domain.aggregation.SubjectType.STOCK,
                "贵州茅台",
                java.util.Map.of(),
                "白酒",
                com.info.platform.domain.aggregation.SubjectStatus.ENABLED,
                0L,
                Instant.now(),
                Instant.now());
    }
}
