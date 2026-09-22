package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.info.platform.domain.aggregation.DataSourceEvent;
import com.info.platform.domain.aggregation.DataSourceEventRepository;
import com.info.platform.domain.aggregation.DataSourceEventType;
import com.info.platform.domain.aggregation.SourceCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * DataSourceEventRecorder 单元测试（T16 + T36）：mock {@link DataSourceEventRepository} 端口，验证 record 委托落库
 * + 落库失败不外抛（可观测性不拖垮主链路）+ OK 成功心跳 60s/源节流（窗口内不重复落库、窗口外再落一条）。 AAA 结构，纯单元（不启 Spring 上下文）。
 */
class DataSourceEventRecorderTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    private DataSourceEventRecorder recorder(
            DataSourceEventRepository repository, Instant... ticks) {
        Clock clock =
                new Clock() {
                    private int index;

                    @Override
                    public Instant instant() {
                        return index < ticks.length ? ticks[index++] : ticks[ticks.length - 1];
                    }

                    @Override
                    public ZoneOffset getZone() {
                        return ZoneOffset.UTC;
                    }

                    @Override
                    public Clock withZone(java.time.ZoneId zone) {
                        return this;
                    }
                };
        return new DataSourceEventRecorder(repository, clock);
    }

    @Test
    void record_delegatesToRepositoryWithCorrectEvent() {
        // Arrange
        DataSourceEventRepository repository = mock(DataSourceEventRepository.class);
        DataSourceEventRecorder recorder = recorder(repository, T0);

        // Act
        recorder.record(
                SourceCode.QUOTE, DataSourceEventType.TIMEOUT, 1L, "exhausted sourceCode=QUOTE");

        // Assert：委托 save 一条事件，字段正确
        ArgumentCaptor<DataSourceEvent> captor = ArgumentCaptor.forClass(DataSourceEvent.class);
        verify(repository).save(captor.capture());
        DataSourceEvent event = captor.getValue();
        assertThat(event.getSourceCode()).isEqualTo(SourceCode.QUOTE);
        assertThat(event.getEventType()).isEqualTo(DataSourceEventType.TIMEOUT);
        assertThat(event.getSubjectId()).contains(1L);
        assertThat(event.getDetail()).contains("exhausted sourceCode=QUOTE");
        assertThat(event.getId()).isNull();
    }

    @Test
    void record_withNullableSubjectIdAndDetail_stillPersists() {
        // Arrange：subjectId/detail 允许为空（DDL 允许，未来无标的上下文事件）
        DataSourceEventRepository repository = mock(DataSourceEventRepository.class);
        DataSourceEventRecorder recorder = recorder(repository, T0);

        // Act + Assert：不抛、仍委托 save
        assertThatCode(
                        () ->
                                recorder.record(
                                        SourceCode.POLICY, DataSourceEventType.MISSING, null, null))
                .doesNotThrowAnyException();
        verify(repository).save(any(DataSourceEvent.class));
    }

    @Test
    void record_repositoryThrows_doesNotPropagate_logsErrorInstead() {
        // Arrange：落库抛异常（模拟 DB 挂/锁等待超时）——可观测性不能拖垮主链路
        DataSourceEventRepository repository = mock(DataSourceEventRepository.class);
        doThrow(new RuntimeException("database locked"))
                .when(repository)
                .save(any(DataSourceEvent.class));
        DataSourceEventRecorder recorder = recorder(repository, T0);

        // Act + Assert：record 不外抛（调用方 adapter 降级返回值不受影响），save 仍被调用一次
        assertThatCode(
                        () ->
                                recorder.record(
                                        SourceCode.QUOTE, DataSourceEventType.ERROR, 1L, "boom"))
                .doesNotThrowAnyException();
        verify(repository).save(any(DataSourceEvent.class));
    }

    @Test
    void recordOkIfDue_firstSuccess_persistsOkEvent() {
        // Arrange
        DataSourceEventRepository repository = mock(DataSourceEventRepository.class);
        DataSourceEventRecorder recorder = recorder(repository, T0);

        // Act
        recorder.recordOkIfDue(SourceCode.QUOTE, 1L);

        // Assert：OK(5) 心跳落库一条
        ArgumentCaptor<DataSourceEvent> captor = ArgumentCaptor.forClass(DataSourceEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(DataSourceEventType.OK);
        assertThat(captor.getValue().getSourceCode()).isEqualTo(SourceCode.QUOTE);
    }

    @Test
    void recordOkIfDue_withinThrottleWindow_skipped() {
        // Arrange：两次成功相隔 30s（< 60s 窗口）
        DataSourceEventRepository repository = mock(DataSourceEventRepository.class);
        DataSourceEventRecorder recorder =
                recorder(repository, T0, T0.plus(Duration.ofSeconds(30)));

        // Act
        recorder.recordOkIfDue(SourceCode.QUOTE, 1L);
        recorder.recordOkIfDue(SourceCode.QUOTE, 2L);

        // Assert：仅窗口内首条落库
        verify(repository, times(1)).save(any(DataSourceEvent.class));
    }

    @Test
    void recordOkIfDue_afterThrottleWindow_persistsAgain_perSource() {
        // Arrange：QUOTE 两次相隔 61s（跨窗口），POLICY 首次（节流按源独立）
        DataSourceEventRepository repository = mock(DataSourceEventRepository.class);
        DataSourceEventRecorder recorder =
                recorder(
                        repository,
                        T0,
                        T0.plus(Duration.ofSeconds(61)),
                        T0.plus(Duration.ofSeconds(61)));

        // Act
        recorder.recordOkIfDue(SourceCode.QUOTE, 1L);
        recorder.recordOkIfDue(SourceCode.QUOTE, 1L);
        recorder.recordOkIfDue(SourceCode.POLICY, 1L);

        // Assert：QUOTE 跨窗口两条 + POLICY 一条
        verify(repository, times(3)).save(any(DataSourceEvent.class));
    }
}
