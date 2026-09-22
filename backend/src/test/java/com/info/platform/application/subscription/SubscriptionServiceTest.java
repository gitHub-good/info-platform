package com.info.platform.application.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.UserContext;
import com.info.platform.domain.subscription.Subscription;
import com.info.platform.domain.subscription.SubscriptionChannel;
import com.info.platform.domain.subscription.SubscriptionRepository;
import com.info.platform.domain.subscription.SubscriptionStatus;
import com.info.platform.domain.subscription.SubscriptionType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SubscriptionService 单测（T26）：subscribe 幂等（全新/已订阅直返/已退订重新激活）+ unsubscribe 软退订/行级权限 +
 * listSubscriptions 游标分页，AAA 结构。
 *
 * <p>mock {@link SubscriptionRepository}；{@link UserContext} 在 @BeforeEach 写入当前用户（模拟
 * JwtAuthFilter），@AfterEach 清空防线程池复用串味。 行级权限：用户 A（userId=1）不能操作用户 B 的订阅→30051。
 */
class SubscriptionServiceTest {

    private static final long ME = 1L;
    private static final long OTHER_USER = 2L;
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");

    private SubscriptionRepository repository;
    private SubscriptionService service;

    @BeforeEach
    void setUp() {
        repository = mock(SubscriptionRepository.class);
        service = new SubscriptionService(repository);
        UserContext.set(new UserContext.Principal(ME, "alice"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ---- subscribe：全新 ----

    @Test
    void subscribe_new_savesAndReturnsView() {
        when(repository.findByOwnerIdAndTypeAndKey(ME, SubscriptionType.SUBJECT.code(), "600519"))
                .thenReturn(Optional.empty());
        when(repository.save(any(Subscription.class)))
                .thenReturn(
                        subscription(
                                10L,
                                ME,
                                SubscriptionType.SUBJECT,
                                "600519",
                                SubscriptionStatus.SUBSCRIBED));

        SubscriptionView result =
                service.subscribe(SubscriptionType.SUBJECT, "600519", SubscriptionChannel.IN_APP);

        assertThat(result.id()).isEqualTo(10L);
        assertThat(result.subType()).isEqualTo(2);
        assertThat(result.subKey()).isEqualTo("600519");
        assertThat(result.channel()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(1);
        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(ME);
        assertThat(captor.getValue().getSubType()).isEqualTo(SubscriptionType.SUBJECT);
    }

    @Test
    void subscribe_newNullChannel_defaultsToInApp() {
        when(repository.findByOwnerIdAndTypeAndKey(eq(ME), anyInt(), eq("半导体")))
                .thenReturn(Optional.empty());
        when(repository.save(any(Subscription.class)))
                .thenReturn(
                        subscription(
                                11L,
                                ME,
                                SubscriptionType.TOPIC,
                                "半导体",
                                SubscriptionStatus.SUBSCRIBED));

        service.subscribe(SubscriptionType.TOPIC, "半导体", null);

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getChannel()).isEqualTo(SubscriptionChannel.IN_APP);
    }

    // ---- subscribe：幂等（已订阅直返） ----

    @Test
    void subscribe_alreadyActive_returnsDirectlyNoSave() {
        // 幂等：已订阅（status=1）→ 直返，不 save、不报错
        Subscription existing =
                subscription(
                        10L, ME, SubscriptionType.SUBJECT, "600519", SubscriptionStatus.SUBSCRIBED);
        when(repository.findByOwnerIdAndTypeAndKey(ME, SubscriptionType.SUBJECT.code(), "600519"))
                .thenReturn(Optional.of(existing));

        SubscriptionView result =
                service.subscribe(SubscriptionType.SUBJECT, "600519", SubscriptionChannel.IN_APP);

        assertThat(result.id()).isEqualTo(10L);
        assertThat(result.status()).isEqualTo(1);
        verify(repository, never()).save(any(Subscription.class));
    }

    // ---- subscribe：重新激活（已退订→status=1） ----

    @Test
    void subscribe_unsubscribed_reactivatesAndSavesReusingSameRow() {
        // 已退订（status=0）→ 重新激活（翻 status=1，复用同一行，UPDATE 不新增行）
        Subscription existing =
                subscription(
                        10L,
                        ME,
                        SubscriptionType.SUBJECT,
                        "600519",
                        SubscriptionStatus.UNSUBSCRIBED);
        when(repository.findByOwnerIdAndTypeAndKey(ME, SubscriptionType.SUBJECT.code(), "600519"))
                .thenReturn(Optional.of(existing));
        when(repository.save(any(Subscription.class))).thenReturn(existing); // save 回填 status=1

        SubscriptionView result =
                service.subscribe(SubscriptionType.SUBJECT, "600519", SubscriptionChannel.IN_APP);

        assertThat(result.id()).isEqualTo(10L); // 复用同一行（id 不变）
        assertThat(result.status()).isEqualTo(1); // 翻回订阅中
        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isTrue(); // reactivate 已翻 status
    }

    // ---- unsubscribe ----

    @Test
    void unsubscribe_ownedActive_unsubscribesAndSaves() {
        when(repository.existsById(10L)).thenReturn(true);
        when(repository.findByOwnerIdAndId(ME, 10L))
                .thenReturn(
                        Optional.of(
                                subscription(
                                        10L,
                                        ME,
                                        SubscriptionType.SUBJECT,
                                        "600519",
                                        SubscriptionStatus.SUBSCRIBED)));

        service.unsubscribe(10L);

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isFalse(); // 软退订 status=0
    }

    @Test
    void unsubscribe_notExists_throws30050() {
        when(repository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> service.unsubscribe(999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SUBSCRIPTION_NOT_FOUND);
        verify(repository, never()).findByOwnerIdAndId(anyLong(), anyLong());
        verify(repository, never()).save(any());
    }

    @Test
    void unsubscribe_notOwned_throws30051() {
        // 行级权限：订阅存在但非本人 → 30051（不泄露是否存在）
        when(repository.existsById(20L)).thenReturn(true);
        when(repository.findByOwnerIdAndId(ME, 20L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unsubscribe(20L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SUBSCRIPTION_FORBIDDEN);
        verify(repository, never()).save(any());
    }

    @Test
    void unsubscribe_alreadyUnsubscribed_idempotentNoSave() {
        // 幂等：已退订（status=0）→ 无副作用不报错、不 save
        when(repository.existsById(10L)).thenReturn(true);
        when(repository.findByOwnerIdAndId(ME, 10L))
                .thenReturn(
                        Optional.of(
                                subscription(
                                        10L,
                                        ME,
                                        SubscriptionType.SUBJECT,
                                        "600519",
                                        SubscriptionStatus.UNSUBSCRIBED)));

        service.unsubscribe(10L); // 不抛异常

        verify(repository, never()).save(any());
    }

    // ---- listSubscriptions ----

    @Test
    void listSubscriptions_byType_filtersByTypeAndOwner() {
        when(repository.findByOwnerIdCursor(
                        eq(ME),
                        eq(SubscriptionType.SUBJECT.code()),
                        eq(null),
                        eq(SubscriptionService.LIST_PAGE_SIZE)))
                .thenReturn(
                        List.of(
                                subscription(
                                        10L,
                                        ME,
                                        SubscriptionType.SUBJECT,
                                        "600519",
                                        SubscriptionStatus.SUBSCRIBED)));

        SubscriptionListView result = service.listSubscriptions(SubscriptionType.SUBJECT, null);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).subType()).isEqualTo(2);
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void listSubscriptions_nullType_passesNullFilter() {
        when(repository.findByOwnerIdCursor(
                        eq(ME), eq(null), eq(null), eq(SubscriptionService.LIST_PAGE_SIZE)))
                .thenReturn(List.of());

        SubscriptionListView result = service.listSubscriptions(null, null);

        assertThat(result.items()).isEmpty();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void listSubscriptions_partialPage_nextCursorNull() {
        // 2 条（< 20）→ nextCursor null
        when(repository.findByOwnerIdCursor(eq(ME), any(), eq(5L), anyInt()))
                .thenReturn(
                        List.of(
                                subscription(
                                        6L,
                                        ME,
                                        SubscriptionType.SUBJECT,
                                        "600519",
                                        SubscriptionStatus.SUBSCRIBED),
                                subscription(
                                        7L,
                                        ME,
                                        SubscriptionType.TOPIC,
                                        "半导体",
                                        SubscriptionStatus.SUBSCRIBED)));

        SubscriptionListView result = service.listSubscriptions(null, 5L);

        assertThat(result.items()).hasSize(2);
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void listSubscriptions_fullPage_nextCursorIsLastId() {
        // 满页 20 条 → nextCursor = 末条 id
        List<Subscription> records =
                IntStream.rangeClosed(1, SubscriptionService.LIST_PAGE_SIZE)
                        .mapToObj(
                                i ->
                                        subscription(
                                                (long) i,
                                                ME,
                                                SubscriptionType.SUBJECT,
                                                String.valueOf(i),
                                                SubscriptionStatus.SUBSCRIBED))
                        .toList();
        when(repository.findByOwnerIdCursor(
                        eq(ME), any(), eq(null), eq(SubscriptionService.LIST_PAGE_SIZE)))
                .thenReturn(records);

        SubscriptionListView result = service.listSubscriptions(null, null);

        assertThat(result.items()).hasSize(SubscriptionService.LIST_PAGE_SIZE);
        assertThat(result.nextCursor()).isEqualTo((long) SubscriptionService.LIST_PAGE_SIZE);
    }

    @Test
    void currentUserId_nullContext_throwsTokenInvalid() {
        UserContext.clear(); // 模拟未认证上下文（不应发生，防御性 fail-fast）
        assertThatThrownBy(() -> service.listSubscriptions(null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    // ---- fixtures ----

    private static Subscription subscription(
            Long id, long userId, SubscriptionType type, String subKey, SubscriptionStatus status) {
        return Subscription.reconstruct(
                id, userId, type, subKey, SubscriptionChannel.IN_APP, status, 0L, NOW, NOW);
    }
}
