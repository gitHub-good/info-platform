package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.info.platform.domain.ai.AiBrief;
import com.info.platform.domain.ai.AiBriefRepository;
import com.info.platform.domain.ai.BriefStatus;
import com.info.platform.domain.ai.BriefType;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;

/**
 * AiBriefRepositoryImpl 集成测试（T21）：SQLite 共享内存库 + Flyway（V9 建 ai_brief 表）后，测 save 往返 /
 * findByIdempotencyKey / CAS claim（成功 bump version / 失败版本不符 / 终态不可领） / 终态 updateById
 * 乐观锁。 @SpringBootTest 启动完整上下文（含 Flyway 迁移），@ActiveProfiles("test") 注入测试 profile（内存库 + 关闭调度）。
 *
 * <p>对齐 SubjectRepositoryImplTest 模式。
 */
@SpringBootTest
@ActiveProfiles("test")
class AiBriefRepositoryImplTest {

    @Autowired private AiBriefRepository repository;

    @Test
    void save_newBrief_thenFindById_roundTrip() {
        // Arrange
        AiBrief brief = AiBrief.createNew(100L, BriefType.STOCK, "100:1:20260921");

        // Act
        AiBrief saved = repository.save(brief);

        // Assert：PENDING、version=0、content/model/promptVersion 空串占位
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getStatus()).isEqualTo(BriefStatus.PENDING);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getContent()).isEmpty();
        assertThat(saved.getModel()).isEmpty();

        Optional<AiBrief> loaded = repository.findById(saved.getId());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getIdempotencyKey()).isEqualTo("100:1:20260921");
        assertThat(loaded.get().getBriefType()).isEqualTo(BriefType.STOCK);
    }

    @Test
    void findByIdempotencyKey_returnsBrief() {
        // Arrange
        AiBrief brief = AiBrief.createNew(101L, BriefType.POLICY, "101:3:20260921");
        repository.save(brief);

        // Act + Assert
        Optional<AiBrief> loaded = repository.findByIdempotencyKey("101:3:20260921");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSubjectId()).isEqualTo(101L);
    }

    @Test
    void findByIdempotencyKey_nullOrBlank_returnsEmpty() {
        assertThat(repository.findByIdempotencyKey(null)).isEmpty();
        assertThat(repository.findByIdempotencyKey("  ")).isEmpty();
    }

    @Test
    void claim_success_returnsBumpedVersion() {
        // Arrange
        AiBrief brief = repository.save(AiBrief.createNew(102L, BriefType.STOCK, "102:1:20260921"));

        // Act：以期望 version（0）领取
        Optional<AiBrief> claimed = repository.claim(brief.getId(), 0L);

        // Assert：bumped version=1，status 仍 PENDING（claim 不改 status，只 bump version）
        assertThat(claimed).isPresent();
        assertThat(claimed.get().getVersion()).isEqualTo(1L);
        assertThat(claimed.get().getStatus()).isEqualTo(BriefStatus.PENDING);

        // 再领一次（旧 version）应失败——version 已 bump
        assertThat(repository.claim(brief.getId(), 0L)).isEmpty();
    }

    @Test
    void claim_wrongVersion_returnsEmpty() {
        // Arrange
        AiBrief brief = repository.save(AiBrief.createNew(103L, BriefType.STOCK, "103:1:20260921"));

        // Act + Assert：错误 version（99）→ 0 行，empty（被并发领走语义）
        assertThat(repository.claim(brief.getId(), 99L)).isEmpty();
    }

    @Test
    void claim_onTerminal_returnsEmpty() {
        // Arrange：置终态后再领
        AiBrief brief = repository.save(AiBrief.createNew(104L, BriefType.STOCK, "104:1:20260921"));
        AiBrief claimed = repository.claim(brief.getId(), 0L).orElseThrow();
        claimed.complete("{}", "[]", 100, "deepseek-flash", "v1.0");
        repository.save(claimed); // status=DONE, version=2

        // Act + Assert：终态（status≠0）不可领
        assertThat(repository.claim(brief.getId(), claimed.getVersion())).isEmpty();
    }

    @Test
    void save_terminalUpdate_incrementsVersion() {
        // Arrange：受理 → 领取（version 0→1）→ 完成 + 保存（version 1→2）
        AiBrief brief = repository.save(AiBrief.createNew(105L, BriefType.STOCK, "105:1:20260921"));
        AiBrief claimed = repository.claim(brief.getId(), 0L).orElseThrow();
        assertThat(claimed.getVersion()).isEqualTo(1L);

        // Act
        claimed.complete("{\"summary\":\"s\"}", "[\"http://f\"]", 200, "deepseek-flash", "v1.0");
        AiBrief updated = repository.save(claimed);

        // Assert：version=2，DONE，content/model/promptVersion 回填
        assertThat(updated.getVersion()).isEqualTo(2L);
        assertThat(updated.getStatus()).isEqualTo(BriefStatus.DONE);
        assertThat(updated.getContent()).isEqualTo("{\"summary\":\"s\"}");
        assertThat(updated.getModel()).isEqualTo("deepseek-flash");
        assertThat(updated.getPromptVersion()).isEqualTo("v1.0");
        assertThat(updated.getCostTokens()).isEqualTo(200);

        Optional<AiBrief> reloaded = repository.findById(brief.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getStatus()).isEqualTo(BriefStatus.DONE);
        assertThat(reloaded.get().getSourceLinks()).isEqualTo("[\"http://f\"]");
    }

    @Test
    void save_markFailed_persistsFailedStatus() {
        // Arrange
        AiBrief brief = repository.save(AiBrief.createNew(106L, BriefType.STOCK, "106:1:20260921"));
        AiBrief claimed = repository.claim(brief.getId(), 0L).orElseThrow();

        // Act
        claimed.markFailed();
        AiBrief updated = repository.save(claimed);

        // Assert
        assertThat(updated.getStatus()).isEqualTo(BriefStatus.FAILED);
    }

    @Test
    void findById_null_returnsEmpty() {
        assertThat(repository.findById(null)).isEmpty();
    }

    @Test
    void save_duplicateIdempotencyKey_translatedToDuplicateKeyException() {
        // Arrange：同幂等键首条已落库（并发双 POST 败者：应用层查重后、INSERT 前对手方才提交）
        repository.save(AiBrief.createNew(200L, BriefType.STOCK, "200:1:20260922"));

        // Act + Assert：败者 INSERT 撞 uq_ai_brief_idempotency → 须译 DuplicateKeyException
        // 供应用层按幂等命中处理，而非 UncategorizedSQLException 冒泡 500/50000（体检 P2 后端条目）
        assertThatThrownBy(
                        () ->
                                repository.save(
                                        AiBrief.createNew(200L, BriefType.STOCK, "200:1:20260922")))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
