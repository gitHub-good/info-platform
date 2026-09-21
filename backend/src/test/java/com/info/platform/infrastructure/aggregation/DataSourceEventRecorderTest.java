package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.info.platform.domain.aggregation.DataSourceEvent;
import com.info.platform.domain.aggregation.DataSourceEventRepository;
import com.info.platform.domain.aggregation.DataSourceEventType;
import com.info.platform.domain.aggregation.SourceCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * DataSourceEventRecorder 单元测试（T16）：mock {@link DataSourceEventRepository} 端口，验证 record 委托落库 +
 * 落库失败不外抛（可观测性不拖垮主链路）。 AAA 结构，纯单元（不启 Spring 上下文）。
 */
class DataSourceEventRecorderTest {

    @Test
    void record_delegatesToRepositoryWithCorrectEvent() {
        // Arrange
        DataSourceEventRepository repository = mock(DataSourceEventRepository.class);
        DataSourceEventRecorder recorder = new DataSourceEventRecorder(repository);

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
        DataSourceEventRecorder recorder = new DataSourceEventRecorder(repository);

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
        DataSourceEventRecorder recorder = new DataSourceEventRecorder(repository);

        // Act + Assert：record 不外抛（调用方 adapter 降级返回值不受影响），save 仍被调用一次
        assertThatCode(
                        () ->
                                recorder.record(
                                        SourceCode.QUOTE, DataSourceEventType.ERROR, 1L, "boom"))
                .doesNotThrowAnyException();
        verify(repository).save(any(DataSourceEvent.class));
    }
}
