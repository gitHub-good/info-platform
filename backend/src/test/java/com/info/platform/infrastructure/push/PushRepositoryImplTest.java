package com.info.platform.infrastructure.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.push.PushRecord;
import com.info.platform.domain.push.PushRepository;
import com.info.platform.domain.push.PushStatus;
import com.info.platform.domain.push.PushType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * PushRepositoryImpl 集成测试（T14）：SQLite 共享内存库 + Flyway V6 建表后，测 saveIfAbsent 幂等防重（UNIQUE 兜底）、 history
 * 游标分页、findPendingByUser、update 状态翻转。 @SpringBootTest 启动完整上下文（含 Flyway 迁移）； @Transactional
 * 每用例结束回滚隔离。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PushRepositoryImplTest {

    @Autowired private PushRepository pushRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    private static final long USER_ID = 1L;
    private static final long SUBJECT_ID = 600519L;
    private static final Instant NOW = Instant.parse("2026-09-21T02:00:00Z");

    private PushRecord newRecord(long userId, String refId, PushType type) {
        return PushRecord.create(userId, SUBJECT_ID, type, refId, "content-" + refId);
    }

    @Test
    void saveIfAbsent_newRecord_backfillsIdAndTimestamps_statusPending() {
        PushRecord record = newRecord(USER_ID, "42", PushType.ANOMALY);

        Optional<PushRecord> saved = pushRepository.saveIfAbsent(record);

        assertThat(saved).isPresent();
        assertThat(saved.get().getId()).isNotNull();
        assertThat(saved.get().getCreatedAt()).isPresent();
        assertThat(saved.get().getUpdatedAt()).isPresent();
        assertThat(saved.get().getStatus()).isEqualTo(PushStatus.PENDING);
        assertThat(saved.get().getRetryCount()).isZero();
        assertThat(saved.get().getVersion()).isZero();
        assertThat(saved.get().getIdempotencyKey()).isEqualTo(USER_ID + ":1:42");
    }

    @Test
    void saveIfAbsent_sameIdempotencyKeyTwice_secondReturnsEmpty() {
        // Arrange：首次落库成功
        pushRepository.saveIfAbsent(newRecord(USER_ID, "42", PushType.ANOMALY));

        // Act：同幂等键（同 userId+pushType+refId）重复落库
        Optional<PushRecord> second =
                pushRepository.saveIfAbsent(newRecord(USER_ID, "42", PushType.ANOMALY));

        // Assert：防重推——应用层查重命中，返回 empty，绝不产生重复行
        assertThat(second).isEmpty();
    }

    @Test
    void saveIfAbsent_differentUsersOrRefId_bothSaved() {
        // 不同用户、不同 refId → 不同幂等键，均落库
        Optional<PushRecord> a =
                pushRepository.saveIfAbsent(newRecord(USER_ID, "42", PushType.ANOMALY));
        Optional<PushRecord> b = pushRepository.saveIfAbsent(newRecord(2L, "42", PushType.ANOMALY));
        Optional<PushRecord> c =
                pushRepository.saveIfAbsent(newRecord(USER_ID, "43", PushType.ANOMALY));
        assertThat(a).isPresent();
        assertThat(b).isPresent();
        assertThat(c).isPresent();
        assertThat(a.get().getId()).isNotEqualTo(b.get().getId());
        assertThat(a.get().getId()).isNotEqualTo(c.get().getId());
    }

    @Test
    void findByUserIdCursor_firstPage_returnsAscByIdLimited() {
        // Arrange：按 refId 降序插入 3 条（id 自增 = 插入序），验证 ORDER BY id ASC 而非 refId 序
        pushRepository.saveIfAbsent(newRecord(USER_ID, "43", PushType.ANOMALY));
        pushRepository.saveIfAbsent(newRecord(USER_ID, "42", PushType.ANOMALY));
        pushRepository.saveIfAbsent(newRecord(USER_ID, "41", PushType.ANOMALY));

        // Act：首页（cursor=null，limit=2）
        List<PushRecord> page = pushRepository.findByUserIdCursor(USER_ID, null, null, 2);

        // Assert：按 id 升序（= 插入序）取前 2 条 → refId 43、42
        assertThat(page).hasSize(2);
        assertThat(page.get(0).getRefId()).contains("43");
        assertThat(page.get(1).getRefId()).contains("42");
    }

    @Test
    void findByUserIdCursor_withCursor_returnsAfterCursor() {
        pushRepository.saveIfAbsent(newRecord(USER_ID, "41", PushType.ANOMALY));
        PushRecord second =
                pushRepository
                        .saveIfAbsent(newRecord(USER_ID, "42", PushType.ANOMALY))
                        .orElseThrow();
        pushRepository.saveIfAbsent(newRecord(USER_ID, "43", PushType.ANOMALY));

        // Act：cursor=second.id → 取之后
        List<PushRecord> page =
                pushRepository.findByUserIdCursor(USER_ID, second.getId(), null, 20);

        // Assert：仅返回 id > cursor 的（即 refId=43）
        assertThat(page).hasSize(1);
        assertThat(page.get(0).getRefId()).contains("43");
    }

    @Test
    void findByUserIdCursor_withTypeFilter_filters() {
        pushRepository.saveIfAbsent(newRecord(USER_ID, "41", PushType.ANOMALY));
        pushRepository.saveIfAbsent(newRecord(USER_ID, "42", PushType.POLICY));

        List<PushRecord> page =
                pushRepository.findByUserIdCursor(USER_ID, null, PushType.POLICY, 20);

        assertThat(page).hasSize(1);
        assertThat(page.get(0).getPushType()).isEqualTo(PushType.POLICY);
    }

    @Test
    void findByUserIdCursor_excludesOtherUsers() {
        pushRepository.saveIfAbsent(newRecord(USER_ID, "41", PushType.ANOMALY));
        pushRepository.saveIfAbsent(newRecord(2L, "99", PushType.ANOMALY));

        List<PushRecord> page = pushRepository.findByUserIdCursor(USER_ID, null, null, 20);

        assertThat(page).hasSize(1);
        assertThat(page.get(0).getUserId()).isEqualTo(USER_ID);
    }

    @Test
    void findPendingByUser_returnsOnlyStatus0OrderedAsc() {
        // Arrange：1 条已推 + 2 条待推（乱序插入）
        PushRecord done =
                pushRepository
                        .saveIfAbsent(newRecord(USER_ID, "50", PushType.ANOMALY))
                        .orElseThrow();
        done.markPushed(NOW);
        pushRepository.update(done);
        pushRepository.saveIfAbsent(newRecord(USER_ID, "41", PushType.ANOMALY));
        pushRepository.saveIfAbsent(newRecord(USER_ID, "42", PushType.ANOMALY));

        // Act
        List<PushRecord> pending = pushRepository.findPendingByUser(USER_ID);

        // Assert：仅 status=0，按 id 升序
        assertThat(pending).hasSize(2);
        assertThat(pending).allMatch(r -> r.getStatus() == PushStatus.PENDING);
        assertThat(pending.get(0).getRefId()).contains("41");
        assertThat(pending.get(1).getRefId()).contains("42");
    }

    @Test
    void findPending_returnsAllUsersStatus0OrderedAsc() {
        // Arrange：2 用户的待推 + 1 条已推 + 1 条失败（跨用户验证全量扫描）
        PushRecord user1Pending =
                pushRepository
                        .saveIfAbsent(newRecord(USER_ID, "41", PushType.ANOMALY))
                        .orElseThrow();
        pushRepository.saveIfAbsent(newRecord(2L, "52", PushType.ANOMALY));
        PushRecord done =
                pushRepository
                        .saveIfAbsent(newRecord(USER_ID, "50", PushType.ANOMALY))
                        .orElseThrow();
        done.markPushed(NOW);
        pushRepository.update(done);

        // Act：limit 充裕 + createdSince=Epoch（不设过期，推送成功路径语义不变）
        List<PushRecord> pending = pushRepository.findPending(100, Instant.EPOCH);

        // Assert：跨用户全量 status=0，按 id 升序（user1 在前）
        assertThat(pending).hasSize(2);
        assertThat(pending).allMatch(r -> r.getStatus() == PushStatus.PENDING);
        assertThat(pending.get(0).getId()).isEqualTo(user1Pending.getId());
        assertThat(pending).extracting(r -> r.getUserId()).containsExactly(USER_ID, 2L);
    }

    @Test
    void update_markPushed_persistsSuccessStatusAndPushedAt() {
        PushRecord saved =
                pushRepository
                        .saveIfAbsent(newRecord(USER_ID, "42", PushType.ANOMALY))
                        .orElseThrow();

        saved.markPushed(NOW);
        pushRepository.update(saved);

        List<PushRecord> reloaded = pushRepository.findByUserIdCursor(USER_ID, null, null, 20);
        assertThat(reloaded).hasSize(1);
        assertThat(reloaded.get(0).getStatus()).isEqualTo(PushStatus.SUCCESS);
        assertThat(reloaded.get(0).getPushedAt()).contains(NOW);
        assertThat(reloaded.get(0).getRetryCount()).isZero();
        // markPushed 后不再是 pending
        assertThat(pushRepository.findPendingByUser(USER_ID)).isEmpty();
    }

    @Test
    void update_markFailedAfterRetry_persistsFailedStatusAndRetryCount() {
        PushRecord saved =
                pushRepository
                        .saveIfAbsent(newRecord(USER_ID, "42", PushType.ANOMALY))
                        .orElseThrow();

        saved.recordRetry(); // 重试1次
        saved.markFailed();
        pushRepository.update(saved);

        List<PushRecord> reloaded = pushRepository.findByUserIdCursor(USER_ID, null, null, 20);
        assertThat(reloaded.get(0).getStatus()).isEqualTo(PushStatus.FAILED);
        assertThat(reloaded.get(0).getRetryCount()).isEqualTo(1);
    }

    // ==================== P0-3 同根治理（系统体检 20260924 P1-1 后端半段）：无界扫描 + PENDING 永久积压
    // ====================

    @Test
    void findPending_excludesPendingOlderThanRetention() {
        // Arrange：2 条待推——其一 created_at 回拨到 8 天前（超 7 天保留期）
        PushRecord fresh =
                pushRepository
                        .saveIfAbsent(newRecord(USER_ID, "60", PushType.ANOMALY))
                        .orElseThrow();
        PushRecord stale =
                pushRepository
                        .saveIfAbsent(newRecord(USER_ID, "61", PushType.ANOMALY))
                        .orElseThrow();
        jdbcTemplate.update(
                "UPDATE push_record SET created_at = ? WHERE id = ?",
                NOW.minus(Duration.ofDays(8)).toString(),
                stale.getId());

        // Act：保留期截止 = now - 7d
        List<PushRecord> pending = pushRepository.findPending(100, NOW.minus(Duration.ofDays(7)));

        // Assert：超期 PENDING 不再被补推 Job 扫中（前端未接 SSE 期间的存量积压止血）；新鲜待推仍入选
        assertThat(pending).extracting(PushRecord::getId).containsExactly(fresh.getId());
    }

    @Test
    void findPending_appliesScanLimit_oldestFirst() {
        // Arrange：3 条待推（id 升序 = 时间升序）
        pushRepository.saveIfAbsent(newRecord(USER_ID, "70", PushType.ANOMALY));
        pushRepository.saveIfAbsent(newRecord(USER_ID, "71", PushType.ANOMALY));
        PushRecord newest =
                pushRepository
                        .saveIfAbsent(newRecord(USER_ID, "72", PushType.ANOMALY))
                        .orElseThrow();

        // Act：LIMIT 2
        List<PushRecord> pending = pushRepository.findPending(2, Instant.EPOCH);

        // Assert：按 id 升序取前 2 条（先来先补推），第 3 条留下轮——防无界全量拉取
        assertThat(pending).hasSize(2);
        assertThat(pending).allMatch(r -> r.getId() < newest.getId());
    }
}
