package com.info.platform.application.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.domain.push.AnomalyRecord;
import com.info.platform.domain.push.AnomalyRepository;
import com.info.platform.domain.push.AnomalyType;
import com.info.platform.domain.push.NotificationEvent;
import com.info.platform.domain.push.PushRecord;
import com.info.platform.domain.push.PushRepository;
import com.info.platform.domain.push.PushStatus;
import com.info.platform.domain.push.PushType;
import com.info.platform.domain.push.SubscriptionResolver;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * PushRetryJob 单元测试（T15）：mock 全部外部依赖（推送/异动仓储、订阅解析、SSE 通道、时钟），装配真实 {@link PushService} 验证「补推 job →
 * PushService.retryPending/processAnomaly → deliver」全链路。
 *
 * <p>双扫描各分支：在线补推成功 / 离线跳过 / 失败重试1次仍失败告警 / 重启恢复 / 幂等防重复 / 单条异常不阻断整轮。 @Scheduled 不触发（对齐 04 测试规范），直调
 * {@code retry} / {@code recoverPendingAnomalies} / {@code retryPendingRecords}。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushRetryJobTest {

    private static final long SUBJECT_ID = 600519L;
    private static final long SUBJECT_ID_B = 600520L;
    private static final long ANOMALY_ID = 42L;
    private static final long PUSH_RECORD_ID = 100L;
    private static final long USER_ID = 1L;
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-21T02:00:05Z"), ZoneOffset.UTC);

    @Mock private PushRepository pushRepository;
    @Mock private AnomalyRepository anomalyRepository;
    @Mock private SubscriptionResolver subscriptionResolver;
    @Mock private NotificationChannel channel;

    private PushService pushService;
    private PushRetryJob job;

    @BeforeEach
    void setUp() {
        // 默认：saveIfAbsent 成功落库并回填 id=PUSH_RECORD_ID（Scan B 在线推送用）；Scan A 不触达 saveIfAbsent。
        when(pushRepository.saveIfAbsent(any(PushRecord.class)))
                .thenAnswer(
                        inv -> {
                            PushRecord arg = inv.getArgument(0);
                            return Optional.of(
                                    PushRecord.reconstruct(
                                            PUSH_RECORD_ID,
                                            arg.getUserId(),
                                            arg.getSubjectId().orElse(null),
                                            arg.getPushType(),
                                            arg.getRefId().orElse(null),
                                            arg.getContent(),
                                            arg.getIdempotencyKey(),
                                            PushStatus.PENDING,
                                            null,
                                            arg.getRetryCount(),
                                            0L,
                                            null,
                                            null));
                        });
        pushService =
                new PushService(
                        pushRepository,
                        anomalyRepository,
                        subscriptionResolver,
                        channel,
                        FIXED_CLOCK);
        job = new PushRetryJob(pushService, pushRepository, anomalyRepository);
    }

    // ---- 两扫描均空：整轮无副作用 ----

    @Test
    void retry_bothEmpty_noSideEffects() {
        // Arrange：两个 findPending 默认返回空（Mockito 集合默认空）

        // Act
        job.retry();

        // Assert：仅各扫一次表，无任何落库/推送/状态翻转
        verify(pushRepository).findPending();
        verify(anomalyRepository).findPending();
        verify(pushRepository, never()).saveIfAbsent(any());
        verify(channel, never()).send(anyLong(), any(), anyLong());
        verify(pushRepository, never()).update(any());
        verify(anomalyRepository, never()).save(any());
    }

    // ---- 待推补推：在线 → 补推成功 status=1 ----

    @Test
    void retryPendingRecords_onlineUser_pushesAndFlipsToSuccess() {
        // Arrange：1 条待推记录，用户当前在线，send 成功
        when(pushRepository.findPending())
                .thenReturn(List.of(buildPendingRecord(PUSH_RECORD_ID, USER_ID, SUBJECT_ID, "42")));
        when(channel.isOnline(USER_ID)).thenReturn(true);
        when(channel.send(eq(USER_ID), any(NotificationEvent.class), eq(PUSH_RECORD_ID)))
                .thenReturn(true);

        // Act
        job.retry();

        // Assert：send 一次 + update 翻 SUCCESS + pushedAt 回填；不重新 saveIfAbsent（防重）
        verify(channel, times(1)).send(eq(USER_ID), any(), eq(PUSH_RECORD_ID));
        verify(pushRepository, never()).saveIfAbsent(any());
        ArgumentCaptor<PushRecord> captor = ArgumentCaptor.forClass(PushRecord.class);
        verify(pushRepository).update(captor.capture());
        PushRecord updated = captor.getValue();
        assertThat(updated.getStatus()).isEqualTo(PushStatus.SUCCESS);
        assertThat(updated.getPushedAt()).contains(FIXED_CLOCK.instant());
        assertThat(updated.getRetryCount()).isZero();
    }

    // ---- 待推补推：离线 → 跳过留下次 ----

    @Test
    void retryPendingRecords_offlineUser_skipsLeavesPending() {
        // Arrange：用户仍离线
        when(pushRepository.findPending())
                .thenReturn(List.of(buildPendingRecord(PUSH_RECORD_ID, USER_ID, SUBJECT_ID, "42")));
        when(channel.isOnline(USER_ID)).thenReturn(false);

        // Act
        job.retry();

        // Assert：不推送、不 update（status 留 0 待下轮/重连补拉）
        verify(channel, never()).send(anyLong(), any(), anyLong());
        verify(pushRepository, never()).update(any());
    }

    // ---- 待推补推：失败重试1次仍失败 → status=2 告警 ----

    @Test
    void retryPendingRecords_sendFailsRetriesOnceStillFails_marksFailed() {
        // Arrange：在线但两次 send 均失败
        when(pushRepository.findPending())
                .thenReturn(List.of(buildPendingRecord(PUSH_RECORD_ID, USER_ID, SUBJECT_ID, "42")));
        when(channel.isOnline(USER_ID)).thenReturn(true);
        when(channel.send(eq(USER_ID), any(), eq(PUSH_RECORD_ID))).thenReturn(false);

        // Act
        job.retry();

        // Assert：send 2 次（初次 + 重试1次）；update 翻 FAILED，retryCount=1
        verify(channel, times(2)).send(eq(USER_ID), any(), eq(PUSH_RECORD_ID));
        ArgumentCaptor<PushRecord> captor = ArgumentCaptor.forClass(PushRecord.class);
        verify(pushRepository).update(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PushStatus.FAILED);
        assertThat(captor.getValue().getRetryCount()).isEqualTo(1);
    }

    // ---- 重启恢复：anomaly pushed=0 → 扫到补推 + 标记 pushed=1 ----

    @Test
    void recoverPendingAnomalies_pushedZero_onlineUser_pushesAndMarksPushed() {
        // Arrange：1 条 pushed=0 异动，1 个在线目标用户
        when(anomalyRepository.findPending())
                .thenReturn(List.of(buildAnomaly(ANOMALY_ID, SUBJECT_ID, false)));
        when(subscriptionResolver.resolveAnomalyTargets(SUBJECT_ID)).thenReturn(Set.of(USER_ID));
        when(channel.isOnline(USER_ID)).thenReturn(true);
        when(channel.send(eq(USER_ID), any(), eq(PUSH_RECORD_ID))).thenReturn(true);

        // Act
        job.retry();

        // Assert：saveIfAbsent 建记录 + send 一次 + update SUCCESS + anomaly 标 pushed=1
        verify(pushRepository).saveIfAbsent(any());
        verify(channel).send(eq(USER_ID), any(), eq(PUSH_RECORD_ID));
        verify(pushRepository).update(any());
        ArgumentCaptor<AnomalyRecord> anomalyCaptor = ArgumentCaptor.forClass(AnomalyRecord.class);
        verify(anomalyRepository).save(anomalyCaptor.capture());
        assertThat(anomalyCaptor.getValue().isPushed()).isTrue();
    }

    // ---- 两扫描同轮：Scan B 离线建待推 → Scan A 在线补推 ----

    @Test
    void recoverThenRetry_offlineAnomalyThenOnlineRetry_pushesOnceSameRound() {
        // Arrange：异动处理时用户离线（建 status=0 待推），同轮 Scan A 用户已上线
        when(anomalyRepository.findPending())
                .thenReturn(List.of(buildAnomaly(ANOMALY_ID, SUBJECT_ID, false)));
        when(subscriptionResolver.resolveAnomalyTargets(SUBJECT_ID)).thenReturn(Set.of(USER_ID));
        // isOnline 第一次（Scan B 的 pushAnomalyToOne）离线；第二次（Scan A 的 retryPending）在线
        when(channel.isOnline(USER_ID)).thenReturn(false).thenReturn(true);
        when(pushRepository.findPending())
                .thenReturn(List.of(buildPendingRecord(PUSH_RECORD_ID, USER_ID, SUBJECT_ID, "42")));
        when(channel.send(eq(USER_ID), any(), eq(PUSH_RECORD_ID))).thenReturn(true);

        // Act
        job.retry();

        // Assert：saveIfAbsent 一次（Scan B 建记录）；send 一次（仅 Scan A，Scan B 离线未发）；anomaly 标 pushed
        verify(pushRepository).saveIfAbsent(any());
        verify(channel, times(1)).send(eq(USER_ID), any(), eq(PUSH_RECORD_ID));
        verify(pushRepository).update(any());
        verify(anomalyRepository).save(any());
    }

    // ---- 幂等：pushed=0 且已有 status=0 记录 → 补推与实时推不重复 ----

    @Test
    void idempotency_pushedZeroWithExistingPendingRecord_noDuplicateSend() {
        // Arrange：重启恢复场景——异动 pushed=0，但 push_record 已存在（崩溃在 saveIfAbsent 后、markPushed 前）
        when(anomalyRepository.findPending())
                .thenReturn(List.of(buildAnomaly(ANOMALY_ID, SUBJECT_ID, false)));
        when(subscriptionResolver.resolveAnomalyTargets(SUBJECT_ID)).thenReturn(Set.of(USER_ID));
        // saveIfAbsent 返回 empty：记录已存在，Scan B 跳过推送（防重）
        when(pushRepository.saveIfAbsent(any(PushRecord.class))).thenReturn(Optional.empty());
        // Scan A 拾取已存在的待推记录补推
        when(pushRepository.findPending())
                .thenReturn(List.of(buildPendingRecord(PUSH_RECORD_ID, USER_ID, SUBJECT_ID, "42")));
        when(channel.isOnline(USER_ID)).thenReturn(true);
        when(channel.send(eq(USER_ID), any(), eq(PUSH_RECORD_ID))).thenReturn(true);

        // Act
        job.retry();

        // Assert：saveIfAbsent 一次（返回 empty，Scan B 不发）；send 仅一次（Scan A）；anomaly 标 pushed
        // —— 同 idempotency_key 下补推与实时推不重复
        verify(pushRepository).saveIfAbsent(any());
        verify(channel, times(1)).send(eq(USER_ID), any(), eq(PUSH_RECORD_ID));
        verify(pushRepository).update(any());
        ArgumentCaptor<AnomalyRecord> anomalyCaptor = ArgumentCaptor.forClass(AnomalyRecord.class);
        verify(anomalyRepository).save(anomalyCaptor.capture());
        assertThat(anomalyCaptor.getValue().isPushed()).isTrue();
    }

    // ---- 单条异常不阻断整轮：Scan A ----

    @Test
    void retryPendingRecords_firstRecordThrows_doesNotBlockSecond() {
        // Arrange：2 条待推，第一条 send 抛异常；第二条正常
        when(pushRepository.findPending())
                .thenReturn(
                        List.of(
                                buildPendingRecord(PUSH_RECORD_ID, USER_ID, SUBJECT_ID, "42"),
                                buildPendingRecord(101L, 2L, SUBJECT_ID_B, "43")));
        when(channel.isOnline(USER_ID)).thenReturn(true);
        when(channel.isOnline(2L)).thenReturn(true);
        when(channel.send(eq(USER_ID), any(), eq(PUSH_RECORD_ID)))
                .thenThrow(new RuntimeException("SSE 通道瞬时故障"));
        when(channel.send(eq(2L), any(), eq(101L))).thenReturn(true);

        // Act：不应抛
        job.retry();

        // Assert：第二条仍补推成功（update 一次 SUCCESS）；第一条抛异常前未 update
        verify(channel).send(eq(2L), any(), eq(101L));
        ArgumentCaptor<PushRecord> captor = ArgumentCaptor.forClass(PushRecord.class);
        verify(pushRepository).update(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PushStatus.SUCCESS);
    }

    // ---- 单条异常不阻断整轮：Scan B ----

    @Test
    void recoverPendingAnomalies_firstThrows_doesNotBlockSecond() {
        // Arrange：2 条 pushed=0 异动；第一条解析订阅抛异常，第二条正常
        when(anomalyRepository.findPending())
                .thenReturn(
                        List.of(
                                buildAnomaly(ANOMALY_ID, SUBJECT_ID, false),
                                buildAnomaly(43L, SUBJECT_ID_B, false)));
        when(subscriptionResolver.resolveAnomalyTargets(SUBJECT_ID))
                .thenThrow(new RuntimeException("订阅解析故障"));
        when(subscriptionResolver.resolveAnomalyTargets(SUBJECT_ID_B)).thenReturn(Set.of(USER_ID));
        when(channel.isOnline(USER_ID)).thenReturn(true);
        when(channel.send(eq(USER_ID), any(), eq(PUSH_RECORD_ID))).thenReturn(true);

        // Act：不应抛
        job.retry();

        // Assert：第二条异动仍处理完成（anomaly.save 一次 pushed=true + send 一次）
        verify(channel).send(eq(USER_ID), any(), eq(PUSH_RECORD_ID));
        ArgumentCaptor<AnomalyRecord> captor = ArgumentCaptor.forClass(AnomalyRecord.class);
        verify(anomalyRepository).save(captor.capture());
        assertThat(captor.getValue().isPushed()).isTrue();
    }

    // ---- fixtures ----

    private static AnomalyRecord buildAnomaly(long id, long subjectId, boolean pushed) {
        return AnomalyRecord.reconstruct(
                id,
                subjectId,
                AnomalyType.PRICE_CHANGE,
                new BigDecimal("5.00"),
                new BigDecimal("1680.50"),
                Instant.parse("2026-09-21T02:00:00Z"),
                "日涨跌幅 5.00% 触发阈值 3.00%",
                pushed,
                Instant.parse("2026-09-21T02:00:00Z"),
                Instant.parse("2026-09-21T02:00:00Z"));
    }

    private static PushRecord buildPendingRecord(
            long id, long userId, long subjectId, String refId) {
        return PushRecord.reconstruct(
                id,
                userId,
                subjectId,
                PushType.ANOMALY,
                refId,
                "日涨跌幅 5.00% 触发阈值 3.00%",
                userId + ":" + PushType.ANOMALY.code() + ":" + refId,
                PushStatus.PENDING,
                null,
                0,
                0L,
                null,
                null);
    }
}
