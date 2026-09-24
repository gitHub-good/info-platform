package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.info.platform.domain.ai.AiBrief;
import com.info.platform.domain.ai.BriefType;
import java.sql.SQLException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.UncategorizedSQLException;

/**
 * 体检 P2 后端条目回归（翻译层，照抄 ADR-0023-2/5 先例 {@link PromptTemplateRepositoryImplConflictTranslationTest}）：
 * ai_brief 并发创建败者的写冲突翻译——UNIQUE(idempotency_key) → {@link DuplicateKeyException}（供应用层按幂等命中处理），
 * SQLITE_BUSY/LOCKED 码族 → {@link CannotAcquireLockException}（并发写冲突），非冲突码不吞不译原样冒泡。
 *
 * <p>外层包装按 MyBatis-Plus + SQLite 实证取 {@link UncategorizedSQLException}（sqlite-jdbc 不抛 JDBC4 子类，Spring
 * 无 SQLite 错误码表，落 uncategorized）；cause 消息携带 sqlite 码族标识——与生产报文同构。
 */
class AiBriefRepositoryImplConflictTranslationTest {

    private AiBriefMapper mapper;
    private AiBriefRepositoryImpl repository;

    /** 纯单测无 Spring 上下文，LambdaQueryWrapper 解析列名需实体元数据——按需初始化（已有则跳过，不污染共享缓存）。 */
    @BeforeAll
    static void initTableInfoIfAbsent() {
        if (TableInfoHelper.getTableInfo(AiBriefPO.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiBriefPO.class);
        }
    }

    @BeforeEach
    void setUp() {
        mapper = mock(AiBriefMapper.class);
        repository = new AiBriefRepositoryImpl(mapper);
    }

    @Test
    void save_uniqueConflict_translatedToDuplicateKey() {
        // Arrange：并发双 POST 败者——应用层查重后、INSERT 前对手方提交同幂等键，撞 uq_ai_brief_idempotency
        when(mapper.insert(any(AiBriefPO.class)))
                .thenThrow(
                        sqliteConflict(
                                "[SQLITE_CONSTRAINT] UNIQUE constraint failed: ai_brief.idempotency_key"));

        // Act + Assert：译 DuplicateKeyException（幂等命中语义），消息带幂等键上下文
        assertThatThrownBy(() -> repository.save(AiBrief.createNew(100L, BriefType.STOCK, "100:1:20260922")))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("100:1:20260922");
    }

    @Test
    void save_busySnapshotConflict_translatedToLockConflict() {
        // Arrange：生产 WAL 签名——读后写同事务遇并发提交命中过期快照（busy_timeout 不挽救）
        when(mapper.insert(any(AiBriefPO.class)))
                .thenThrow(sqliteConflict("[SQLITE_BUSY_SNAPSHOT] The database file is locked"));

        // Act + Assert
        assertThatThrownBy(() -> repository.save(AiBrief.createNew(101L, BriefType.STOCK, "101:1:20260922")))
                .isInstanceOf(CannotAcquireLockException.class);
    }

    @Test
    void save_sharedCacheLockedConflict_translatedToLockConflict() {
        // Arrange：共享内存库实证签名（跨连接表锁竞争）
        when(mapper.insert(any(AiBriefPO.class)))
                .thenThrow(
                        sqliteConflict(
                                "[SQLITE_LOCKED_SHAREDCACHE] database table is locked: ai_brief"));

        // Act + Assert
        assertThatThrownBy(() -> repository.save(AiBrief.createNew(102L, BriefType.STOCK, "102:1:20260922")))
                .isInstanceOf(CannotAcquireLockException.class);
    }

    @Test
    void save_nonConflictSqliteError_passesThroughUntranslated() {
        // Arrange：非并发冲突（语法/普通 sqlite 错误）不得误译——原样冒泡由全局处理器兜底
        UncategorizedSQLException raw = sqliteConflict("[SQLITE_ERROR] SQL logic error");
        when(mapper.insert(any(AiBriefPO.class))).thenThrow(raw);

        // Act + Assert：同一实例原样抛出（不吞不换型）
        assertThatThrownBy(() -> repository.save(AiBrief.createNew(103L, BriefType.STOCK, "103:1:20260922")))
                .isSameAs(raw);
        assertThat(raw).isNotInstanceOf(CannotAcquireLockException.class);
        assertThat(raw).isNotInstanceOf(DuplicateKeyException.class);
    }

    /** 构造与 MyBatis-Plus + SQLite 实证同构的 UncategorizedSQLException（cause 文案 = sqlite 报文）。 */
    private static UncategorizedSQLException sqliteConflict(String sqliteMessage) {
        return new UncategorizedSQLException(
                "### Error updating database. Cause: " + sqliteMessage,
                "INSERT INTO ai_brief ...",
                new SQLException(sqliteMessage));
    }
}
