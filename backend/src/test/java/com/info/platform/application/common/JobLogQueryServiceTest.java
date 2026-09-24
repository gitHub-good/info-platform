package com.info.platform.application.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.info.platform.domain.common.JobExecutionLog;
import com.info.platform.domain.common.JobExecutionLogRepository;
import com.info.platform.domain.common.JobExecutionStatus;
import com.info.platform.domain.common.JobLogFilter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * JobLogQueryService 单元测试（T33 游标 + M9 T61 页码模式）：mock {@link JobExecutionLogRepository}，验证页码模式
 * count+byFilterPage 同一 filter、jobName blank 归一、status 透传、越界空页如实回显（不依赖真实 DB，FIRST）。
 */
@ExtendWith(MockitoExtension.class)
class JobLogQueryServiceTest {

    private static final Instant T1 = Instant.parse("2026-09-22T01:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-22T01:00:05Z");

    @Mock private JobExecutionLogRepository repository;

    private JobLogQueryService service;

    @BeforeEach
    void setUp() {
        service = new JobLogQueryService(repository);
    }

    private static JobExecutionLog finished(long id, String jobName, JobExecutionStatus status) {
        return JobExecutionLog.reconstruct(
                id,
                jobName,
                T1,
                T2,
                status,
                5000L,
                3,
                status == JobExecutionStatus.FAILED ? 1 : 0,
                status == JobExecutionStatus.FAILED ? "boom" : null,
                T1,
                T2);
    }

    @Test
    void listPaged_countAndPageSameFilter_returnsTotalAndEcho() {
        // Arrange：jobName blank 归一为 null；同一 filter 走 count + byFilterPage
        JobLogFilter filter = new JobLogFilter(null, JobExecutionStatus.FAILED);
        when(repository.countByFilter(filter)).thenReturn(7L);
        when(repository.byFilterPage(filter, 1, 20))
                .thenReturn(List.of(finished(12385L, "PolicyFetchJob", JobExecutionStatus.FAILED)));

        // Act
        JobLogPagedView view = service.listPaged("  ", JobExecutionStatus.FAILED, 1, 20);

        // Assert：total 精确、page/size 如实回显、视图字段与游标模式一致（status 用枚举名）
        assertThat(view.total()).isEqualTo(7L);
        assertThat(view.page()).isEqualTo(1);
        assertThat(view.size()).isEqualTo(20);
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).id()).isEqualTo(12385L);
        assertThat(view.items().get(0).status()).isEqualTo("FAILED");
        assertThat(view.items().get(0).durationMillis()).isEqualTo(5000L);
        assertThat(view.items().get(0).errorMessage()).isEqualTo("boom");
    }

    @Test
    void listPaged_jobNameTrimmed_statusNull_noFilter() {
        // Arrange：jobName trim 后透传；status=null 不过滤
        JobLogFilter filter = new JobLogFilter("PolicyFetchJob", null);
        when(repository.countByFilter(filter)).thenReturn(43L);
        when(repository.byFilterPage(filter, 3, 50)).thenReturn(List.of());

        // Act + Assert
        JobLogPagedView view = service.listPaged(" PolicyFetchJob ", null, 3, 50);
        assertThat(view.total()).isEqualTo(43L);
        assertThat(view.items()).isEmpty();
        assertThat(view.page()).isEqualTo(3);
        assertThat(view.size()).isEqualTo(50);
    }

    @Test
    void listPaged_outOfRangePage_emptyListWithRealTotal() {
        // Arrange：越界页 → 空列表 + 真实 total（ADR-0035：200 + 空列表 + 如实回显）
        JobLogFilter filter = new JobLogFilter(null, null);
        when(repository.countByFilter(filter)).thenReturn(7L);
        when(repository.byFilterPage(filter, 99, 20)).thenReturn(List.of());

        // Act + Assert
        JobLogPagedView view = service.listPaged(null, null, 99, 20);
        assertThat(view.items()).isEmpty();
        assertThat(view.total()).isEqualTo(7L);
        assertThat(view.page()).isEqualTo(99);
    }

    @Test
    void list_cursorMode_unchanged_t33() {
        // Arrange：游标路径回归——不满页 nextCursor=null
        when(repository.byJobNameCursor("PushRetryJob", null, 20))
                .thenReturn(
                        List.of(
                                finished(21L, "PushRetryJob", JobExecutionStatus.SUCCESS),
                                finished(20L, "PushRetryJob", JobExecutionStatus.SUCCESS)));

        // Act + Assert
        JobLogPage page = service.list("PushRetryJob", null);
        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNull();
        assertThat(page.items().get(0).id()).isEqualTo(21L);
    }
}
