package com.info.platform.application.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.push.AnomalyDetectedEvent;
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
import java.time.Duration;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * PushService 单元测试（T14）：mock 全部外部依赖（推送仓储/异动仓储/订阅解析/SSE 通道/时钟）， 直调 {@code handleAnomaly}
 * 验证「查订阅→幂等防重→在线推送/离线待推→失败重试1次→anomaly pushed=1」各分支。 @Async/@EventListener 不触发（直调绕过代理）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushServiceTest {

    private static final long SUBJECT_ID = 600519L;
    private static final long ANOMALY_ID = 42L;
    private static final long PUSH_RECORD_ID = 100L;
    private static final long USER_ID = 1L;
    private static final Instant TRIGGER_TIME = Instant.parse("2026-09-21T02:00:00Z");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-21T02:00:05Z"), ZoneOffset.UTC);

    @Mock private PushRepository pushRepository;
    @Mock private AnomalyRepository anomalyRepository;
    @Mock private SubscriptionResolver subscriptionResolver;
    @Mock private NotificationChannel channel;
    @Mock private SubjectRepository subjectRepository;
    @Mock private com.info.platform.domain.common.UserRepository userRepository;

    private PushService service;
    private AnomalyDetectedEvent event;
    private AnomalyRecord anomaly;

    @BeforeEach
    void setUp() {
        service =
                new PushService(
                        pushRepository,
                        anomalyRepository,
                        subscriptionResolver,
                        channel,
                        subjectRepository,
                        userRepository,
                        FIXED_CLOCK,
                        7);
        event =
                new AnomalyDetectedEvent(
                        SUBJECT_ID,
                        AnomalyType.PRICE_CHANGE,
                        new BigDecimal("5.00"),
                        new BigDecimal("1680.50"),
                        TRIGGER_TIME);
        anomaly =
                AnomalyRecord.reconstruct(
                        ANOMALY_ID,
                        SUBJECT_ID,
                        AnomalyType.PRICE_CHANGE,
                        new BigDecimal("5.00"),
                        new BigDecimal("1680.50"),
                        TRIGGER_TIME,
                        "日涨跌幅 5.00% 触发阈值 3.00%",
                        false,
                        Instant.parse("2026-09-21T02:00:00Z"),
                        Instant.parse("2026-09-21T02:00:00Z"));
        // 默认：能定位到 anomaly_record（同 subjectId + type + triggerTime 唯一匹配）
        when(anomalyRepository.findBySubjectId(SUBJECT_ID)).thenReturn(List.of(anomaly));
        // 默认：subjectId 可回查到标的代码（P1-1 subjectCode 增量）
        when(subjectRepository.findById(SUBJECT_ID))
                .thenReturn(Optional.of(subjectFixture(SUBJECT_ID, "SH600519")));
        // 默认：saveIfAbsent 成功落库并回填 id
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
    }

    // ---- 异动事件消费：在线推送成功 ----

    @Test
    void handleAnomaly_onlineUser_pushSuccess_marksPushedAndAnomalyPushed() {
        // Arrange：1 个目标用户在线
        when(subscriptionResolver.resolveAnomalyTargets(SUBJECT_ID)).thenReturn(Set.of(USER_ID));
        when(channel.isOnline(USER_ID)).thenReturn(true);
        when(channel.send(eq(USER_ID), any(NotificationEvent.class), eq(PUSH_RECORD_ID)))
                .thenReturn(true);

        // Act
        service.handleAnomaly(event);

        // Assert：幂等落库 + 单次推送 + 更新为 SUCCESS
        verify(pushRepository).saveIfAbsent(any(PushRecord.class));
        verify(channel, times(1)).send(eq(USER_ID), any(), eq(PUSH_RECORD_ID));
        ArgumentCaptor<PushRecord> updateCaptor = ArgumentCaptor.forClass(PushRecord.class);
        verify(pushRepository).update(updateCaptor.capture());
        PushRecord updated = updateCaptor.getValue();
        assertThat(updated.getStatus()).isEqualTo(PushStatus.SUCCESS);
        assertThat(updated.getRetryCount()).isZero();
        assertThat(updated.getPushedAt()).contains(FIXED_CLOCK.instant());

        // anomaly_record 置 pushed=1 并落库
        ArgumentCaptor<AnomalyRecord> anomalyCaptor = ArgumentCaptor.forClass(AnomalyRecord.class);
        verify(anomalyRepository).save(anomalyCaptor.capture());
        assertThat(anomalyCaptor.getValue().isPushed()).isTrue();
    }

    // ---- 离线用户：留 status=0 待推 ----

    @Test
    void handleAnomaly_offlineUser_leavesPending_noSendNoUpdate() {
        // Arrange：目标用户离线
        when(subscriptionResolver.resolveAnomalyTargets(SUBJECT_ID)).thenReturn(Set.of(USER_ID));
        when(channel.isOnline(USER_ID)).thenReturn(false);

        // Act
        service.handleAnomaly(event);

        // Assert：push_record 以 PENDING 落库（saveIfAbsent 已置 PENDING）；不推送、不 update 状态
        verify(pushRepository).saveIfAbsent(any(PushRecord.class));
        verify(channel, never()).send(anyLong(), any(), anyLong());
        verify(pushRepository, never()).update(any());
        // anomaly 仍标记已处理（防 findPending 重复拾取）
        verify(anomalyRepository).save(any(AnomalyRecord.class));
    }

    // ---- 推送失败：重试 1 次仍失败 → status=2 告警 ----

    @Test
    void handleAnomaly_pushFailsRetriesOnceStillFails_marksFailed() {
        // Arrange：在线但两次 send 均失败
        when(subscriptionResolver.resolveAnomalyTargets(SUBJECT_ID)).thenReturn(Set.of(USER_ID));
        when(channel.isOnline(USER_ID)).thenReturn(true);
        when(channel.send(eq(USER_ID), any(), eq(PUSH_RECORD_ID))).thenReturn(false);

        // Act
        service.handleAnomaly(event);

        // Assert：send 调 2 次（初次 + 重试1次）；update 为 FAILED，retryCount=1
        verify(channel, times(2)).send(eq(USER_ID), any(), eq(PUSH_RECORD_ID));
        ArgumentCaptor<PushRecord> updateCaptor = ArgumentCaptor.forClass(PushRecord.class);
        verify(pushRepository).update(updateCaptor.capture());
        PushRecord updated = updateCaptor.getValue();
        assertThat(updated.getStatus()).isEqualTo(PushStatus.FAILED);
        assertThat(updated.getRetryCount()).isEqualTo(1);
        // anomaly 仍标记 pushed（事件已处理）
        verify(anomalyRepository).save(any(AnomalyRecord.class));
    }

    @Test
    void handleAnomaly_firstFailRetrySuccess_marksSuccessWithRetry() {
        // Arrange：首次失败、重试成功
        when(subscriptionResolver.resolveAnomalyTargets(SUBJECT_ID)).thenReturn(Set.of(USER_ID));
        when(channel.isOnline(USER_ID)).thenReturn(true);
        when(channel.send(eq(USER_ID), any(), eq(PUSH_RECORD_ID)))
                .thenReturn(false) // 首次失败
                .thenReturn(true); // 重试成功

        // Act
        service.handleAnomaly(event);

        // Assert：send 2 次；update 为 SUCCESS，retryCount=1（记一次重试）
        verify(channel, times(2)).send(eq(USER_ID), any(), eq(PUSH_RECORD_ID));
        ArgumentCaptor<PushRecord> updateCaptor = ArgumentCaptor.forClass(PushRecord.class);
        verify(pushRepository).update(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getStatus()).isEqualTo(PushStatus.SUCCESS);
        assertThat(updateCaptor.getValue().getRetryCount()).isEqualTo(1);
    }

    // ---- 幂等防重：重复事件不重推 ----

    @Test
    void handleAnomaly_duplicateEvent_saveIfAbsentEmpty_skipsPush() {
        // Arrange：幂等键已存在 → saveIfAbsent 返回 empty（防重）
        when(subscriptionResolver.resolveAnomalyTargets(SUBJECT_ID)).thenReturn(Set.of(USER_ID));
        when(channel.isOnline(USER_ID)).thenReturn(true);
        when(pushRepository.saveIfAbsent(any(PushRecord.class))).thenReturn(Optional.empty());

        // Act
        service.handleAnomaly(event);

        // Assert：不推送、不 update（防重推）；anomaly 仍标记已处理
        verify(channel, never()).send(anyLong(), any(), anyLong());
        verify(pushRepository, never()).update(any());
        verify(anomalyRepository).save(any(AnomalyRecord.class));
    }

    // ---- 无订阅者：仍标记 anomaly pushed ----

    @Test
    void handleAnomaly_noSubscribers_marksAnomalyPushedWithoutPush() {
        // Arrange：无订阅目标
        when(subscriptionResolver.resolveAnomalyTargets(SUBJECT_ID)).thenReturn(Set.of());

        // Act
        service.handleAnomaly(event);

        // Assert：不写 push_record、不推送；anomaly 标记已处理（防 findPending 拾取）
        verify(pushRepository, never()).saveIfAbsent(any());
        verifyNoInteractions(channel);
        verify(anomalyRepository).save(any(AnomalyRecord.class));
    }

    // ---- 异动记录未定位到：跳过 ----

    @Test
    void handleAnomaly_anomalyNotFound_skipsWithoutMarking() {
        // Arrange：findBySubjectId 返回空（事件与记录不一致，理论不应发生）
        when(anomalyRepository.findBySubjectId(SUBJECT_ID)).thenReturn(List.of());

        // Act
        service.handleAnomaly(event);

        // Assert：不查订阅、不落库、不标记 anomaly
        verifyNoInteractions(subscriptionResolver);
        verify(pushRepository, never()).saveIfAbsent(any());
        verify(anomalyRepository, never()).save(any());
    }

    // ---- 异动记录已 pushed：跳过 ----

    @Test
    void handleAnomaly_anomalyAlreadyPushed_skipsWithoutResaving() {
        // Arrange：记录已 pushed=1（重投事件）
        AnomalyRecord pushed =
                AnomalyRecord.reconstruct(
                        ANOMALY_ID,
                        SUBJECT_ID,
                        AnomalyType.PRICE_CHANGE,
                        new BigDecimal("5.00"),
                        new BigDecimal("1680.50"),
                        TRIGGER_TIME,
                        "detail",
                        true,
                        Instant.parse("2026-09-21T02:00:00Z"),
                        Instant.parse("2026-09-21T02:00:00Z"));
        when(anomalyRepository.findBySubjectId(SUBJECT_ID)).thenReturn(List.of(pushed));

        // Act
        service.handleAnomaly(event);

        // Assert：不查订阅、不落库、不再 save anomaly（幂等）
        verifyNoInteractions(subscriptionResolver);
        verify(pushRepository, never()).saveIfAbsent(any());
        verify(anomalyRepository, never()).save(any());
    }

    // ---- 单用户推送异常不阻断其他用户 ----

    @Test
    void handleAnomaly_oneUserThrows_doesNotBlockOthers() {
        // Arrange：两个目标用户，第二个 send 抛异常（模拟 SSE 通道故障）；不阻断第一个
        long userA = 1L;
        long userB = 2L;
        when(subscriptionResolver.resolveAnomalyTargets(SUBJECT_ID))
                .thenReturn(Set.of(userA, userB));
        when(channel.isOnline(userA)).thenReturn(true);
        when(channel.isOnline(userB)).thenReturn(true);
        when(channel.send(eq(userA), any(), eq(PUSH_RECORD_ID))).thenReturn(true);
        when(channel.send(eq(userB), any(), eq(PUSH_RECORD_ID)))
                .thenThrow(new RuntimeException("SSE 通道瞬时故障"));

        // Act：不应抛
        service.handleAnomaly(event);

        // Assert：两个 saveIfAbsent 都调；anomaly 仍标记 pushed（整批不阻断）
        verify(pushRepository, times(2)).saveIfAbsent(any());
        verify(anomalyRepository).save(any(AnomalyRecord.class));
    }

    // ---- onAnomalyDetected 异常兜底不外抛 ----

    @Test
    void onAnomalyDetected_repositoryThrows_loggedAndSwallowed() {
        // Arrange：anomalyRepository.findBySubjectId 抛异常
        when(anomalyRepository.findBySubjectId(SUBJECT_ID))
                .thenThrow(new RuntimeException("DB 抖动"));

        // Act：@Async 监听器异常兜底，不外抛
        service.onAnomalyDetected(event);

        // Assert：未触达后续编排（无订阅查询）
        verifyNoInteractions(subscriptionResolver);
    }

    // ---- openStream：注册连接 + 重连补拉待推 ----

    @Test
    void openStream_registersEmitterAndFlushesPending() {
        // Arrange：用户重连，有 1 条待推记录
        SseEmitter emitter = new SseEmitter();
        when(channel.open(USER_ID)).thenReturn(emitter);
        when(pushRepository.findPendingByUser(eq(USER_ID), any(Instant.class)))
                .thenReturn(List.of(pendingRecord(50L)));
        when(channel.send(eq(USER_ID), any(), eq(50L))).thenReturn(true);

        // Act
        SseEmitter result = service.openStream(USER_ID, null);

        // Assert：返回注册的 emitter；补拉发送 + 翻转 SUCCESS
        assertThat(result).isSameAs(emitter);
        verify(channel).send(eq(USER_ID), any(), eq(50L));
        ArgumentCaptor<PushRecord> updateCaptor = ArgumentCaptor.forClass(PushRecord.class);
        verify(pushRepository).update(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getStatus()).isEqualTo(PushStatus.SUCCESS);
    }

    @Test
    void openStream_lastEventIdSkipsAlreadySeenPending() {
        // Arrange：待推记录 id=50，客户端已见 lastEventId=50 → 跳过补拉
        SseEmitter emitter = new SseEmitter();
        when(channel.open(USER_ID)).thenReturn(emitter);
        when(pushRepository.findPendingByUser(eq(USER_ID), any(Instant.class)))
                .thenReturn(List.of(pendingRecord(50L)));

        // Act
        service.openStream(USER_ID, 50L);

        // Assert：id <= lastEventId 跳过，不发送、不 update（留待推）
        verify(channel, never()).send(eq(USER_ID), any(), anyLong());
        verify(pushRepository, never()).update(any());
    }

    @Test
    void openStream_flushPending_appliesPendingRetentionFilter() {
        // P1-1 批 1 遗留项：SSE 补拉下发保留期截止（超期 PENDING 由仓储过滤，服务层负责传对截止值）
        SseEmitter emitter = new SseEmitter();
        when(channel.open(USER_ID)).thenReturn(emitter);
        when(pushRepository.findPendingByUser(eq(USER_ID), any(Instant.class)))
                .thenReturn(List.of());

        // Act
        service.openStream(USER_ID, null);

        // Assert：createdSince = 固定时钟 - 7 天（对齐 push.retry.pending-retention-days 语义）
        ArgumentCaptor<Instant> sinceCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(pushRepository).findPendingByUser(eq(USER_ID), sinceCaptor.capture());
        assertThat(sinceCaptor.getValue())
                .isEqualTo(FIXED_CLOCK.instant().minus(Duration.ofDays(7)));
    }

    // ---- history：游标分页 ----

    @Test
    void history_pageNotFull_returnsItemsWithNullNextCursor() {
        // Arrange：2 条记录（< 20）→ nextCursor null
        List<PushRecord> records =
                List.of(
                        buildHistoryRecord(10L, PushType.ANOMALY, SUBJECT_ID),
                        buildHistoryRecord(11L, PushType.ANOMALY, SUBJECT_ID));
        when(pushRepository.findByUserIdCursor(eq(USER_ID), eq(5L), eq(null), eq(20)))
                .thenReturn(records);

        // Act
        NotificationHistory history = service.history(USER_ID, 5L, null);

        // Assert
        assertThat(history.items()).hasSize(2);
        assertThat(history.nextCursor()).isNull();
        assertThat(history.items().get(0).id()).isEqualTo(10L);
        assertThat(history.items().get(0).type()).isEqualTo("anomaly");
        assertThat(history.items().get(1).id()).isEqualTo(11L);
    }

    @Test
    void history_pageFull_returnsNextCursorAsLastId() {
        // Arrange：满页 20 条 → nextCursor = 末条 id
        java.util.List<PushRecord> records = new java.util.ArrayList<>();
        for (long i = 1; i <= PushService.HISTORY_PAGE_SIZE; i++) {
            records.add(buildHistoryRecord(i, PushType.ANOMALY, SUBJECT_ID));
        }
        when(pushRepository.findByUserIdCursor(eq(USER_ID), eq(null), eq(null), eq(20)))
                .thenReturn(records);

        // Act
        NotificationHistory history = service.history(USER_ID, null, null);

        // Assert
        assertThat(history.items()).hasSize(PushService.HISTORY_PAGE_SIZE);
        assertThat(history.nextCursor()).isEqualTo((long) PushService.HISTORY_PAGE_SIZE);
    }

    // ---- P1-1（系统体检 20260924）：subjectCode 增量 + 最近 N 条 history ----

    @Test
    void handleAnomaly_onlineUser_payloadCarriesSubjectCode() {
        // Arrange：标的可回查代码
        when(subscriptionResolver.resolveAnomalyTargets(SUBJECT_ID)).thenReturn(Set.of(USER_ID));
        when(channel.isOnline(USER_ID)).thenReturn(true);
        when(channel.send(eq(USER_ID), any(NotificationEvent.class), eq(PUSH_RECORD_ID)))
                .thenReturn(true);

        // Act
        service.handleAnomaly(event);

        // Assert：SSE 载荷带内部统一代码（前端按 code 跳标的详情）
        ArgumentCaptor<NotificationEvent> payloadCaptor =
                ArgumentCaptor.forClass(NotificationEvent.class);
        verify(channel).send(eq(USER_ID), payloadCaptor.capture(), eq(PUSH_RECORD_ID));
        assertThat(payloadCaptor.getValue().subjectCode()).isEqualTo("SH600519");
    }

    @Test
    void handleAnomaly_subjectMissing_payloadStillSentWithNullCode() {
        // Arrange：标的已删（回查空）→ 仍推送，subjectCode=null（不阻断）
        when(subscriptionResolver.resolveAnomalyTargets(999L)).thenReturn(Set.of(USER_ID));
        when(anomalyRepository.findBySubjectId(999L))
                .thenReturn(
                        List.of(
                                AnomalyRecord.reconstruct(
                                        ANOMALY_ID,
                                        999L,
                                        AnomalyType.PRICE_CHANGE,
                                        new BigDecimal("5.00"),
                                        new BigDecimal("1680.50"),
                                        TRIGGER_TIME,
                                        "detail",
                                        false,
                                        TRIGGER_TIME,
                                        TRIGGER_TIME)));
        when(subjectRepository.findById(999L)).thenReturn(Optional.empty());
        when(channel.isOnline(USER_ID)).thenReturn(true);
        when(channel.send(eq(USER_ID), any(NotificationEvent.class), eq(PUSH_RECORD_ID)))
                .thenReturn(true);

        // Act
        service.handleAnomaly(
                new AnomalyDetectedEvent(
                        999L,
                        AnomalyType.PRICE_CHANGE,
                        new BigDecimal("5.00"),
                        new BigDecimal("1680.50"),
                        TRIGGER_TIME));

        // Assert：推送不因回查失败而中断
        ArgumentCaptor<NotificationEvent> payloadCaptor =
                ArgumentCaptor.forClass(NotificationEvent.class);
        verify(channel).send(eq(USER_ID), payloadCaptor.capture(), eq(PUSH_RECORD_ID));
        assertThat(payloadCaptor.getValue().subjectCode()).isNull();
    }

    @Test
    void history_itemsCarrySubjectCode() {
        // Arrange：同页同标的 → 单次回查复用（codeCache）
        List<PushRecord> records =
                List.of(
                        buildHistoryRecord(10L, PushType.ANOMALY, SUBJECT_ID),
                        buildHistoryRecord(11L, PushType.ANOMALY, SUBJECT_ID));
        when(pushRepository.findByUserIdCursor(eq(USER_ID), eq(null), eq(null), eq(20)))
                .thenReturn(records);

        // Act
        NotificationHistory history = service.history(USER_ID, null, null);

        // Assert：视图带 subjectCode；同页同标的只回查一次
        assertThat(history.items().get(0).subjectCode()).isEqualTo("SH600519");
        assertThat(history.items().get(1).subjectCode()).isEqualTo("SH600519");
        verify(subjectRepository, times(1)).findById(SUBJECT_ID);
    }

    @Test
    void latestHistory_returnsItemsWithNullCursor() {
        // Arrange：最近 2 条（仓储返回升序）
        when(pushRepository.findLatestByUser(eq(USER_ID), eq(null), eq(20)))
                .thenReturn(
                        List.of(
                                buildHistoryRecord(10L, PushType.ANOMALY, SUBJECT_ID),
                                buildHistoryRecord(11L, PushType.ANOMALY, SUBJECT_ID)));

        // Act
        NotificationHistory history = service.latestHistory(USER_ID, 20, null);

        // Assert：单次拉取语义（无续页游标）+ 带代码
        assertThat(history.items()).hasSize(2);
        assertThat(history.nextCursor()).isNull();
        assertThat(history.items().get(1).subjectCode()).isEqualTo("SH600519");
    }

    private static PushRecord pendingRecord(long id) {
        return PushRecord.reconstruct(
                id,
                USER_ID,
                SUBJECT_ID,
                PushType.ANOMALY,
                String.valueOf(ANOMALY_ID),
                "content",
                USER_ID + ":1:" + ANOMALY_ID,
                PushStatus.PENDING,
                null,
                0,
                0L,
                null,
                null);
    }

    // ---- M14 T115：源异常/恢复推送（广播链路） ----

    @Test
    void handleSourceAlert_onlineUser_pushedWithContentAndEpisodeRefId() {
        when(userRepository.findAll())
                .thenReturn(
                        List.of(
                                com.info.platform.domain.common.User.reconstruct(
                                        USER_ID, "admin", "hash", 0L, null, null)));
        when(channel.isOnline(USER_ID)).thenReturn(true);
        when(channel.send(eq(USER_ID), any(NotificationEvent.class), eq(PUSH_RECORD_ID)))
                .thenReturn(true);

        service.handleSourceAlert(
                new com.info.platform.domain.push.SourceAlertEvent(
                        com.info.platform.domain.push.SourceAlertEvent.Kind.ALERT,
                        "jin10_flash",
                        "金十数据·快讯",
                        5,
                        FIXED_CLOCK.instant().minus(Duration.ofMinutes(45)),
                        "FeedFetchException: 连接超时",
                        FIXED_CLOCK.instant()));

        // push_record 落库：类型 SOURCE_ALERT、幂等键含 episodeKey（恢复后新一轮不被误吞）
        ArgumentCaptor<PushRecord> saved = ArgumentCaptor.forClass(PushRecord.class);
        verify(pushRepository).saveIfAbsent(saved.capture());
        assertThat(saved.getValue().getPushType()).isEqualTo(PushType.SOURCE_ALERT);
        assertThat(saved.getValue().getIdempotencyKey())
                .isEqualTo(USER_ID + ":6:jin10_flash:" + FIXED_CLOCK.instant());
        // SSE 载荷：type=source_alert、content 含源名/失败轮数/摘要/持续时长
        ArgumentCaptor<NotificationEvent> payload =
                ArgumentCaptor.forClass(NotificationEvent.class);
        verify(channel).send(eq(USER_ID), payload.capture(), eq(PUSH_RECORD_ID));
        assertThat(payload.getValue().type()).isEqualTo("source_alert");
        assertThat(payload.getValue().content())
                .contains("金十数据·快讯")
                .contains("连续失败 5 轮")
                .contains("连接超时")
                .contains("持续 45 分钟");
        verify(pushRepository).update(any(PushRecord.class));
    }

    @Test
    void handleSourceAlert_recoveredEvent_usesSourceRecoveredType() {
        when(userRepository.findAll())
                .thenReturn(
                        List.of(
                                com.info.platform.domain.common.User.reconstruct(
                                        USER_ID, "admin", "hash", 0L, null, null)));
        when(channel.isOnline(USER_ID)).thenReturn(true);
        when(channel.send(eq(USER_ID), any(NotificationEvent.class), eq(PUSH_RECORD_ID)))
                .thenReturn(true);

        service.handleSourceAlert(
                new com.info.platform.domain.push.SourceAlertEvent(
                        com.info.platform.domain.push.SourceAlertEvent.Kind.RECOVERED,
                        "jin10_flash",
                        "金十数据·快讯",
                        5,
                        null,
                        null,
                        FIXED_CLOCK.instant()));

        ArgumentCaptor<NotificationEvent> payload =
                ArgumentCaptor.forClass(NotificationEvent.class);
        verify(channel).send(eq(USER_ID), payload.capture(), eq(PUSH_RECORD_ID));
        assertThat(payload.getValue().type()).isEqualTo("source_recovered");
        assertThat(payload.getValue().content()).contains("[源恢复]").contains("已恢复抓取");
    }

    @Test
    void handleSourceAlert_offlineUser_leavesPendingForReconnectPull() {
        when(userRepository.findAll())
                .thenReturn(
                        List.of(
                                com.info.platform.domain.common.User.reconstruct(
                                        USER_ID, "admin", "hash", 0L, null, null)));
        when(channel.isOnline(USER_ID)).thenReturn(false);

        service.handleSourceAlert(
                new com.info.platform.domain.push.SourceAlertEvent(
                        com.info.platform.domain.push.SourceAlertEvent.Kind.ALERT,
                        "t115_src",
                        "源",
                        6,
                        null,
                        "DNS 解析失败",
                        FIXED_CLOCK.instant()));

        verify(pushRepository).saveIfAbsent(any(PushRecord.class));
        verify(channel, never()).send(anyLong(), any(NotificationEvent.class), anyLong());
        verify(pushRepository, never()).update(any(PushRecord.class));
    }

    @Test
    void handleSourceAlert_oneUserFailsOthersStillPushed() {
        when(userRepository.findAll())
                .thenReturn(
                        List.of(
                                com.info.platform.domain.common.User.reconstruct(
                                        1L, "a", "hash", 0L, null, null),
                                com.info.platform.domain.common.User.reconstruct(
                                        2L, "b", "hash", 0L, null, null)));
        // 用户 1 落库即抛（如 DB 竞态），用户 2 不受阻断
        when(pushRepository.saveIfAbsent(any(PushRecord.class)))
                .thenThrow(new RuntimeException("db down"))
                .thenAnswer(
                        inv -> {
                            PushRecord arg = inv.getArgument(0);
                            return Optional.of(
                                    PushRecord.reconstruct(
                                            PUSH_RECORD_ID,
                                            arg.getUserId(),
                                            null,
                                            arg.getPushType(),
                                            arg.getRefId().orElse(null),
                                            arg.getContent(),
                                            arg.getIdempotencyKey(),
                                            PushStatus.PENDING,
                                            null,
                                            0,
                                            0L,
                                            null,
                                            null));
                        });

        service.handleSourceAlert(
                new com.info.platform.domain.push.SourceAlertEvent(
                        com.info.platform.domain.push.SourceAlertEvent.Kind.ALERT,
                        "t115_src",
                        "源",
                        6,
                        null,
                        "超时",
                        FIXED_CLOCK.instant()));

        verify(pushRepository, times(2)).saveIfAbsent(any(PushRecord.class));
    }

    private static Subject subjectFixture(long id, String code) {
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
                null,
                null);
    }

    private static PushRecord buildHistoryRecord(long id, PushType type, Long subjectId) {
        return PushRecord.reconstruct(
                id,
                USER_ID,
                subjectId,
                type,
                "ref-" + id,
                "content-" + id,
                USER_ID + ":" + type.code() + ":ref-" + id,
                PushStatus.SUCCESS,
                Instant.parse("2026-09-21T02:00:00Z"),
                0,
                0L,
                Instant.parse("2026-09-21T02:00:00Z"),
                Instant.parse("2026-09-21T02:00:00Z"));
    }

    // ---- T125：管道熔断告警广播（PipelineFusedEvent → PIPELINE_FUSED(8)，方案 §4.6） ----

    @Test
    void handlePipelineFused_broadcastsFusedAlertToAllUsers() throws Exception {
        when(userRepository.findAll())
                .thenReturn(
                        List.of(
                                com.info.platform.domain.common.User.reconstruct(
                                        USER_ID, "admin", "hash", 0L, null, null)));
        when(channel.isOnline(USER_ID)).thenReturn(true);
        when(channel.send(eq(USER_ID), any(NotificationEvent.class), eq(PUSH_RECORD_ID)))
                .thenReturn(true);

        service.handlePipelineFused(
                new com.info.platform.domain.push.PipelineFusedEvent(
                        1_830_000L, 2_000_000L, FIXED_CLOCK.instant()));

        // push_record：类型 PIPELINE_FUSED(8)、幂等键含触发时刻（episode 间不误吞）
        ArgumentCaptor<PushRecord> saved = ArgumentCaptor.forClass(PushRecord.class);
        verify(pushRepository).saveIfAbsent(saved.capture());
        assertThat(saved.getValue().getPushType()).isEqualTo(PushType.PIPELINE_FUSED);
        assertThat(saved.getValue().getIdempotencyKey())
                .isEqualTo(USER_ID + ":8:pipeline_fused:" + FIXED_CLOCK.instant());
        // SSE 载荷：type=pipeline_fused、content 含成本/预算（横幅三处同源文案）
        ArgumentCaptor<NotificationEvent> payload =
                ArgumentCaptor.forClass(NotificationEvent.class);
        verify(channel).send(eq(USER_ID), payload.capture(), eq(PUSH_RECORD_ID));
        assertThat(payload.getValue().type()).isEqualTo("pipeline_fused");
        assertThat(payload.getValue().content()).contains("1830000").contains("2000000");
        verify(pushRepository).update(any(PushRecord.class));
    }

    // ---- T124：行业日报完成提醒广播（IndustryReportReadyEvent → INDUSTRY_REPORT(9)，方案 §4.5 步骤 5） ----

    @Test
    void handleIndustryReportReady_broadcastsReportNoticeToAllUsers() throws Exception {
        when(userRepository.findAll())
                .thenReturn(
                        List.of(
                                com.info.platform.domain.common.User.reconstruct(
                                        USER_ID, "admin", "hash", 0L, null, null)));
        when(channel.isOnline(USER_ID)).thenReturn(true);
        when(channel.send(eq(USER_ID), any(NotificationEvent.class), eq(PUSH_RECORD_ID)))
                .thenReturn(true);

        service.handleIndustryReportReady(
                new com.info.platform.domain.push.IndustryReportReadyEvent(
                        "2026-09-22", false, FIXED_CLOCK.instant()));

        // push_record：类型 INDUSTRY_REPORT(9)、幂等键含覆盖日（每日一条天然节流）
        ArgumentCaptor<PushRecord> saved = ArgumentCaptor.forClass(PushRecord.class);
        verify(pushRepository).saveIfAbsent(saved.capture());
        assertThat(saved.getValue().getPushType()).isEqualTo(PushType.INDUSTRY_REPORT);
        assertThat(saved.getValue().getIdempotencyKey())
                .isEqualTo(USER_ID + ":9:industry_report:2026-09-22");
        // SSE 载荷：type=industry_report、content 含覆盖日（前端通知面板跳日报详情）
        ArgumentCaptor<NotificationEvent> payload =
                ArgumentCaptor.forClass(NotificationEvent.class);
        verify(channel).send(eq(USER_ID), payload.capture(), eq(PUSH_RECORD_ID));
        assertThat(payload.getValue().type()).isEqualTo("industry_report");
        assertThat(payload.getValue().content()).contains("2026-09-22");
        verify(pushRepository).update(any(PushRecord.class));
    }

    @Test
    void handleIndustryReportReady_degradedReport_contentMarksStatsOnly() throws Exception {
        when(userRepository.findAll())
                .thenReturn(
                        List.of(
                                com.info.platform.domain.common.User.reconstruct(
                                        USER_ID, "admin", "hash", 0L, null, null)));
        when(channel.isOnline(USER_ID)).thenReturn(true);
        when(channel.send(eq(USER_ID), any(NotificationEvent.class), eq(PUSH_RECORD_ID)))
                .thenReturn(true);

        service.handleIndustryReportReady(
                new com.info.platform.domain.push.IndustryReportReadyEvent(
                        "2026-09-22", true, FIXED_CLOCK.instant()));

        // 降级版通知如实标注「纯统计版」（不误导晨读——叙述缺失可见）
        ArgumentCaptor<NotificationEvent> payload =
                ArgumentCaptor.forClass(NotificationEvent.class);
        verify(channel).send(eq(USER_ID), payload.capture(), eq(PUSH_RECORD_ID));
        assertThat(payload.getValue().content()).contains("统计");
    }
}
