package com.info.platform.infrastructure.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.info.platform.domain.subscription.Subscription;
import com.info.platform.domain.subscription.SubscriptionChannel;
import com.info.platform.domain.subscription.SubscriptionRepository;
import com.info.platform.domain.subscription.SubscriptionStatus;
import com.info.platform.domain.subscription.SubscriptionType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * SubscriptionRepositoryImpl 集成测试（T26）：SQLite 共享内存库 + Flyway V11 建表后， 测 save/游标分页/行级过滤/
 * findByTypeAndKey 幂等查找/findAllActive/UNIQUE 幂等约束/乐观锁。 @SpringBootTest 启动完整上下文（含 Flyway
 * 迁移）， @Transactional 每个用例结束回滚隔离。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SubscriptionRepositoryImplTest {

    @Autowired private SubscriptionRepository repository;

    private static final long USER_A = 9001L;
    private static final long USER_B = 9002L;

    @Test
    void save_newSubscription_thenFindByOwnerIdAndId_roundTrip() {
        Subscription created =
                repository.save(
                        Subscription.create(
                                USER_A,
                                SubscriptionType.SUBJECT,
                                "600519",
                                SubscriptionChannel.IN_APP));

        Optional<Subscription> loaded = repository.findByOwnerIdAndId(USER_A, created.getId());

        assertThat(created.getId()).isNotNull();
        assertThat(created.getUserId()).isEqualTo(USER_A);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSubType()).isEqualTo(SubscriptionType.SUBJECT);
        assertThat(loaded.get().getSubKey()).isEqualTo("600519");
        assertThat(loaded.get().getChannel()).isEqualTo(SubscriptionChannel.IN_APP);
        assertThat(loaded.get().getStatus()).isEqualTo(SubscriptionStatus.SUBSCRIBED);
    }

    @Test
    void save_newNullChannel_defaultsToInApp() {
        Subscription saved =
                repository.save(Subscription.create(USER_A, SubscriptionType.TOPIC, "半导体", null));

        assertThat(saved.getChannel()).isEqualTo(SubscriptionChannel.IN_APP);
    }

    @Test
    void findByOwnerIdCursor_filtersByOwnerAndType() {
        repository.save(
                Subscription.create(
                        USER_A, SubscriptionType.SUBJECT, "600519", SubscriptionChannel.IN_APP));
        repository.save(
                Subscription.create(
                        USER_A, SubscriptionType.TOPIC, "半导体", SubscriptionChannel.IN_APP));
        repository.save(
                Subscription.create(
                        USER_B, SubscriptionType.SUBJECT, "600519", SubscriptionChannel.IN_APP));

        List<Subscription> aSubject =
                repository.findByOwnerIdCursor(USER_A, SubscriptionType.SUBJECT.code(), null, 20);

        // 仅 A 的标的订阅（行级 + 类型过滤）
        assertThat(aSubject).hasSize(1);
        assertThat(aSubject.get(0).getSubKey()).isEqualTo("600519");
        assertThat(aSubject.get(0).getUserId()).isEqualTo(USER_A);

        // null type → A 的全部订阅（2 条）
        assertThat(repository.findByOwnerIdCursor(USER_A, null, null, 20)).hasSize(2);
    }

    @Test
    void findByOwnerIdCursor_cursorPagination() {
        for (int i = 1; i <= 25; i++) {
            repository.save(
                    Subscription.create(
                            USER_A, SubscriptionType.SUBJECT, "s" + i, SubscriptionChannel.IN_APP));
        }

        // 首页 20 条，nextCursor=20
        List<Subscription> page1 = repository.findByOwnerIdCursor(USER_A, null, null, 20);
        assertThat(page1).hasSize(20);
        assertThat(page1.get(19).getSubKey()).isEqualTo("s20");

        // 次页（cursor=20）取 5 条
        List<Subscription> page2 =
                repository.findByOwnerIdCursor(USER_A, null, page1.get(19).getId(), 20);
        assertThat(page2).hasSize(5);
        assertThat(page2.get(0).getSubKey()).isEqualTo("s21");
    }

    @Test
    void rowLevel_findByOwnerIdAndId_otherUserReturnsEmpty() {
        Subscription aSub =
                repository.save(
                        Subscription.create(
                                USER_A,
                                SubscriptionType.SUBJECT,
                                "600519",
                                SubscriptionChannel.IN_APP));

        // 行级过滤：B 查 A 的订阅 → 空（非本人读不到）
        assertThat(repository.findByOwnerIdAndId(USER_B, aSub.getId())).isEmpty();
    }

    @Test
    void existsById_ignoresOwner() {
        Subscription saved =
                repository.save(
                        Subscription.create(
                                USER_A,
                                SubscriptionType.SUBJECT,
                                "600519",
                                SubscriptionChannel.IN_APP));

        assertThat(repository.existsById(saved.getId())).isTrue();
        assertThat(repository.existsById(999999L)).isFalse();
    }

    @Test
    void findByOwnerIdAndTypeAndKey_findsExistingAnyStatus_forReactivate() {
        // 已订阅
        Subscription active =
                repository.save(
                        Subscription.create(
                                USER_A,
                                SubscriptionType.SUBJECT,
                                "600519",
                                SubscriptionChannel.IN_APP));
        assertThat(
                        repository.findByOwnerIdAndTypeAndKey(
                                USER_A, SubscriptionType.SUBJECT.code(), "600519"))
                .isPresent()
                .get()
                .extracting(Subscription::getStatus)
                .isEqualTo(SubscriptionStatus.SUBSCRIBED);

        // 退订后仍能找到（status=0），供重新激活复用同一行
        active.unsubscribe();
        repository.save(active);
        Optional<Subscription> found =
                repository.findByOwnerIdAndTypeAndKey(
                        USER_A, SubscriptionType.SUBJECT.code(), "600519");
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(active.getId()); // 复用同一行
        assertThat(found.get().getStatus()).isEqualTo(SubscriptionStatus.UNSUBSCRIBED);
    }

    @Test
    void findByOwnerIdAndTypeAndKey_scopedToOwner() {
        repository.save(
                Subscription.create(
                        USER_A, SubscriptionType.SUBJECT, "600519", SubscriptionChannel.IN_APP));
        repository.save(
                Subscription.create(
                        USER_B, SubscriptionType.SUBJECT, "600519", SubscriptionChannel.IN_APP));

        // B 的自然键查询不命中 A 的订阅（行级）
        assertThat(
                        repository.findByOwnerIdAndTypeAndKey(
                                USER_B, SubscriptionType.SUBJECT.code(), "600519"))
                .isPresent()
                .get()
                .extracting(Subscription::getUserId)
                .isEqualTo(USER_B);
    }

    @Test
    void findAllActive_returnsOnlySubscribedExcludesUnsubscribed() {
        repository.save(
                Subscription.create(
                        USER_A, SubscriptionType.SUBJECT, "600519", SubscriptionChannel.IN_APP));
        Subscription toUnsub =
                repository.save(
                        Subscription.create(
                                USER_A, SubscriptionType.TOPIC, "半导体", SubscriptionChannel.IN_APP));
        toUnsub.unsubscribe();
        repository.save(toUnsub); // status=0

        List<Subscription> active = repository.findAllActive();

        assertThat(active).hasSize(1);
        assertThat(active.get(0).getSubKey()).isEqualTo("600519"); // 仅订阅中
    }

    @Test
    void save_duplicateNaturalKey_uniqueConstraintFires() {
        // DB UNIQUE(user_id, sub_type, sub_key) 为幂等最后防线：重复 INSERT 触发 SQLite 唯一约束。
        // 注：MyBatis-Plus+SQLite 下 UNIQUE 违例落为 UncategorizedSQLException（未翻译为
        // DuplicateKeyException），故应用层靠服务级 findByOwnerIdAndTypeAndKey 预检返回幂等结果，
        // DB 异常只在预检漏掉的极小概率竞态下兜底（→500）。此处断言约束确实存在并触发。
        repository.save(
                Subscription.create(
                        USER_A, SubscriptionType.SUBJECT, "600519", SubscriptionChannel.IN_APP));

        assertThatThrownBy(
                        () ->
                                repository.save(
                                        Subscription.create(
                                                USER_A,
                                                SubscriptionType.SUBJECT,
                                                "600519",
                                                SubscriptionChannel.IN_APP)))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("UNIQUE constraint failed: subscription_config");
    }

    @Test
    void save_unsubscribe_thenReactivate_incrementsVersion() {
        // 乐观锁：create→unsubscribe→reactivate，version 递增
        Subscription saved =
                repository.save(
                        Subscription.create(
                                USER_A,
                                SubscriptionType.SUBJECT,
                                "600519",
                                SubscriptionChannel.IN_APP));
        assertThat(saved.getVersion()).isZero();

        saved.unsubscribe();
        Subscription afterUnsub = repository.save(saved);
        assertThat(afterUnsub.getStatus()).isEqualTo(SubscriptionStatus.UNSUBSCRIBED);

        afterUnsub.reactivate();
        Subscription afterReactive = repository.save(afterUnsub);
        assertThat(afterReactive.getStatus()).isEqualTo(SubscriptionStatus.SUBSCRIBED);

        Optional<Subscription> reloaded =
                repository.findByOwnerIdAndId(USER_A, afterReactive.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getVersion()).isEqualTo(2L); // 两次 UPDATE
    }
}
