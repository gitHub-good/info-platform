package com.info.platform.application.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.info.platform.domain.push.SourceAlertEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * SourceAlertService 单元测试（T115，REQ 故事 3）：mock 事件发布器—— 双阈值（连续失败轮数 / 退避窗时长，取先到）、同源节流（恢复前不重复）、
 * 恢复通知（Should 可关、恢复后重新武装）、旁路契约（发布异常不上抛）。
 */
@ExtendWith(MockitoExtension.class)
class SourceAlertServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** 缺省阈值（REQ：连续 5 轮或退避 30 分钟，取先到）。 */
    private static final int FAILURE_THRESHOLD = 5;

    private static final long BACKOFF_THRESHOLD_MINUTES = 30;

    @Mock private ApplicationEventPublisher publisher;

    private SourceAlertService service;

    @BeforeEach
    void setUp() {
        service =
                new SourceAlertService(
                        publisher, CLOCK, FAILURE_THRESHOLD, BACKOFF_THRESHOLD_MINUTES, true);
    }

    // —— 阈值：连续失败轮数（REQ 场景 1） ——

    @Test
    void countThreshold_firesOnFifthConsecutiveFailure_notEarlier() {
        // 退避窗全程 < 30min（隔离轮数阈值）：前 4 轮不告警
        for (int i = 1; i < FAILURE_THRESHOLD; i++) {
            service.onRoundFinished(failure(i, NOW.plus(Duration.ofMinutes(10))));
        }
        verify(publisher, never()).publishEvent(any(SourceAlertEvent.class));

        service.onRoundFinished(failure(FAILURE_THRESHOLD, NOW.plus(Duration.ofMinutes(20))));

        ArgumentCaptor<SourceAlertEvent> captor = ArgumentCaptor.forClass(SourceAlertEvent.class);
        verify(publisher).publishEvent(captor.capture());
        SourceAlertEvent event = captor.getValue();
        assertThat(event.kind()).isEqualTo(SourceAlertEvent.Kind.ALERT);
        assertThat(event.sourceCode()).isEqualTo("t115_src");
        assertThat(event.consecutiveFailures()).isEqualTo(FAILURE_THRESHOLD);
        assertThat(event.errorSummary()).contains("超时");
    }

    // —— 阈值：退避窗 ≥30 分钟先行（快源慢源取先到） ——

    @Test
    void backoffThreshold_firesOnFirstFailure_whenBackoffWindowReaches30Minutes() {
        // 30min 官方源首轮失败退避 60min ≥ 30min：时间阈值先于轮数阈值命中
        service.onRoundFinished(failure(1, NOW.plus(Duration.ofMinutes(60))));

        ArgumentCaptor<SourceAlertEvent> captor = ArgumentCaptor.forClass(SourceAlertEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().kind()).isEqualTo(SourceAlertEvent.Kind.ALERT);
        assertThat(captor.getValue().consecutiveFailures()).isEqualTo(1);
    }

    @Test
    void belowBothThresholds_noAlert() {
        // 5min 源第 1 轮失败：轮数 1 < 5、退避 10min < 30min
        service.onRoundFinished(failure(1, NOW.plus(Duration.ofMinutes(10))));

        verify(publisher, never()).publishEvent(any(SourceAlertEvent.class));
    }

    // —— 节流：告警后恢复前同源不重复（REQ 场景 2） ——

    @Test
    void throttle_noRepeatForSameSourceUntilRecovery_otherSourceUnaffected() {
        service.onRoundFinished(failure(FAILURE_THRESHOLD, NOW.plus(Duration.ofHours(2))));
        // 后续失败继续发生：第 6、10 轮不再推
        service.onRoundFinished(failure(6, NOW.plus(Duration.ofHours(4))));
        service.onRoundFinished(failure(10, NOW.plus(Duration.ofHours(6))));

        // 其他源告警不受节流影响
        service.onRoundFinished(
                new SourceAlertService.SourceAlertRound(
                        "t115_other",
                        "他源",
                        false,
                        1,
                        NOW,
                        NOW.plus(Duration.ofMinutes(60)),
                        NOW.minus(Duration.ofMinutes(30)),
                        "DNS 解析失败"));

        ArgumentCaptor<SourceAlertEvent> captor = ArgumentCaptor.forClass(SourceAlertEvent.class);
        verify(publisher, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(SourceAlertEvent::sourceCode)
                .containsExactly("t115_src", "t115_other");
    }

    // —— 恢复通知（REQ 场景 3 Should） ——

    @Test
    void recovery_sendsOneSourceRecovered_andRearmsAlert() {
        service.onRoundFinished(failure(FAILURE_THRESHOLD, NOW.plus(Duration.ofHours(2))));
        service.onRoundFinished(success());
        service.onRoundFinished(success()); // 二次成功不重复恢复通知

        ArgumentCaptor<SourceAlertEvent> captor = ArgumentCaptor.forClass(SourceAlertEvent.class);
        verify(publisher, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        SourceAlertEvent recovered = captor.getAllValues().get(1);
        assertThat(recovered.kind()).isEqualTo(SourceAlertEvent.Kind.RECOVERED);
        assertThat(recovered.sourceCode()).isEqualTo("t115_src");
        assertThat(recovered.consecutiveFailures()).isEqualTo(FAILURE_THRESHOLD);

        // 重新武装：新一轮连续失败达阈值再次告警
        for (int i = 1; i < FAILURE_THRESHOLD; i++) {
            service.onRoundFinished(failure(i, NOW.plus(Duration.ofMinutes(10))));
        }
        service.onRoundFinished(failure(FAILURE_THRESHOLD, NOW.plus(Duration.ofHours(2))));
        verify(publisher, org.mockito.Mockito.times(3)).publishEvent(any(SourceAlertEvent.class));
    }

    @Test
    void recovery_whenDisabled_skipsNotification_butStillRearms() {
        SourceAlertService disabledRecovery =
                new SourceAlertService(
                        publisher, CLOCK, FAILURE_THRESHOLD, BACKOFF_THRESHOLD_MINUTES, false);
        disabledRecovery.onRoundFinished(failure(FAILURE_THRESHOLD, NOW.plus(Duration.ofHours(2))));
        disabledRecovery.onRoundFinished(success());

        // 无恢复通知，仅首条告警
        verify(publisher, org.mockito.Mockito.times(1)).publishEvent(any(SourceAlertEvent.class));

        // 重新武装仍在：新一轮达阈值再次告警（episodeKey 不同，不因幂等键吞掉）
        for (int i = 1; i <= FAILURE_THRESHOLD; i++) {
            disabledRecovery.onRoundFinished(failure(i, NOW.plus(Duration.ofHours(2))));
        }
        verify(publisher, org.mockito.Mockito.times(2)).publishEvent(any(SourceAlertEvent.class));
    }

    @Test
    void success_withoutPriorAlert_publishesNothing() {
        service.onRoundFinished(success());

        verify(publisher, never()).publishEvent(any(SourceAlertEvent.class));
    }

    // —— 旁路契约：告警链路异常不拖垮轮次（REQ 场景 4 不阻塞） ——

    @Test
    void publisherThrows_swallowedNotPropagated() {
        doThrow(new RuntimeException("SSE 不可用")).when(publisher).publishEvent(any());
        assertThatCode(
                        () ->
                                service.onRoundFinished(
                                        failure(FAILURE_THRESHOLD, NOW.plus(Duration.ofHours(2)))))
                .doesNotThrowAnyException();
    }

    // —— 事件值对象校验（构造防线） ——

    @Test
    void sourceAlertEvent_validation_rejectsMissingKindCodeAndEpisodeKey() {
        assertThat(
                        org.assertj.core.api.Assertions.assertThatThrownBy(
                                        () ->
                                                new SourceAlertEvent(
                                                        null, "t115_src", "名", 1, null, null, NOW))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("kind"))
                .isNotNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                new SourceAlertEvent(
                                        SourceAlertEvent.Kind.ALERT, "", "名", 1, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceCode");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                new SourceAlertEvent(
                                        SourceAlertEvent.Kind.ALERT,
                                        "t115_src",
                                        "名",
                                        1,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("episodeKey");
    }

    // —— 夹具 ——

    /** 5min 快讯源失败轮（failingSince = 昨日成功基线 → 告警文案可算持续时长）。 */
    private static SourceAlertService.SourceAlertRound failure(
            int consecutiveFailures, Instant backoffUntil) {
        return new SourceAlertService.SourceAlertRound(
                "t115_src",
                "测试快讯源",
                false,
                consecutiveFailures,
                NOW,
                backoffUntil,
                NOW.minus(Duration.ofHours(20)),
                "FeedFetchException: 连接超时");
    }

    private static SourceAlertService.SourceAlertRound success() {
        return new SourceAlertService.SourceAlertRound(
                "t115_src", "测试快讯源", true, 0, NOW, null, NOW, null);
    }
}
