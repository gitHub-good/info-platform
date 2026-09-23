package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.PromptTemplate;
import java.sql.SQLException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.UncategorizedSQLException;

/**
 * DEFECT-1 回归（翻译层）：SQLite 并发写冲突的异常翻译——BUSY 族（生产文件库 WAL：SQLITE_BUSY / SQLITE_BUSY_SNAPSHOT，过期快照
 * busy_timeout 不挽救）与 LOCKED 族（测试共享内存库：SQLITE_LOCKED / SQLITE_LOCKED_SHAREDCACHE，见集成测试实证）都须译为 {@link
 * CannotAcquireLockException}，供应用层转 409/30070 「请刷新重试」。
 *
 * <p>外层包装按 MyBatis-Plus + SQLite 实证取 {@link UncategorizedSQLException}（sqlite-jdbc 不抛 JDBC4
 * 子类，Spring 无 SQLite 错误码表，落 uncategorized）；cause 消息携带 sqlite 码族标识——与生产报文同构 （cause 为
 * org.sqlite.SQLiteException，本测试以同文案 java.sql.SQLException 等价模拟，检测按消息码族判）。 非并发冲突（如
 * SQLITE_ERROR）不吞不译，原样冒泡。
 */
class PromptTemplateRepositoryImplConflictTranslationTest {

    private PromptTemplateMapper mapper;
    private PromptTemplateRepositoryImpl repository;

    /** 纯单测无 Spring 上下文，LambdaUpdateWrapper 解析列名需实体元数据——按需初始化（已有则跳过，不污染共享缓存）。 */
    @BeforeAll
    static void initTableInfoIfAbsent() {
        if (TableInfoHelper.getTableInfo(PromptTemplatePO.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                    PromptTemplatePO.class);
        }
    }

    @BeforeEach
    void setUp() {
        mapper = mock(PromptTemplateMapper.class);
        repository = new PromptTemplateRepositoryImpl(mapper);
    }

    @Test
    void insert_busySnapshotConflict_translatedToLockConflict() {
        // Arrange：生产 WAL 签名（DEFECT-1 日志根因）：读写同事务 + 另一事务其间提交 → 写命中过期快照
        when(mapper.insert(any(PromptTemplatePO.class)))
                .thenThrow(sqliteConflict("[SQLITE_BUSY_SNAPSHOT] The database file is locked"));

        // Act + Assert
        assertThatThrownBy(
                        () ->
                                repository.insert(
                                        PromptTemplate.newVersion(
                                                BriefType.STOCK,
                                                "v1.1",
                                                "---SYSTEM---\ns json\n---USER---\nu")))
                .isInstanceOf(CannotAcquireLockException.class)
                .hasMessageContaining("v1.1");
    }

    @Test
    void insert_busyConflict_translatedToLockConflict() {
        // Arrange：写锁竞争族（busy_timeout 排队后仍冲突）
        when(mapper.insert(any(PromptTemplatePO.class)))
                .thenThrow(sqliteConflict("[SQLITE_BUSY] The database file is locked"));

        // Act + Assert
        assertThatThrownBy(
                        () ->
                                repository.insert(
                                        PromptTemplate.newVersion(
                                                BriefType.STOCK,
                                                "v1.2",
                                                "---SYSTEM---\ns json\n---USER---\nu")))
                .isInstanceOf(CannotAcquireLockException.class);
    }

    @Test
    void deactivateActive_sharedCacheLockedConflict_translatedToLockConflict() {
        // Arrange：测试共享内存库实证签名（败者冲突点在先置废 UPDATE）
        when(mapper.update(eq((PromptTemplatePO) null), any()))
                .thenThrow(
                        sqliteConflict(
                                "[SQLITE_LOCKED_SHAREDCACHE] Contention with a different database"
                                        + " connection that shares the cache (database table is"
                                        + " locked: prompt_template)"));

        // Act + Assert
        assertThatThrownBy(() -> repository.deactivateActive(BriefType.STOCK))
                .isInstanceOf(CannotAcquireLockException.class)
                .hasMessageContaining("briefType=1");
    }

    @Test
    void updateStatus_busyConflict_translatedToLockConflict() {
        // Arrange：activate 写阶段同样暴露（读目标行后置 1 的读后写事务）
        when(mapper.update(eq((PromptTemplatePO) null), any()))
                .thenThrow(sqliteConflict("[SQLITE_BUSY] The database file is locked"));

        // Act + Assert
        assertThatThrownBy(() -> repository.updateStatus(1L, 1))
                .isInstanceOf(CannotAcquireLockException.class);
    }

    @Test
    void write_nonConflictSqliteError_passesThroughUntranslated() {
        // Arrange：非并发冲突（语法/约束外的普通 sqlite 错误）不得误译——原样冒泡由全局处理器兜底
        UncategorizedSQLException raw = sqliteConflict("[SQLITE_ERROR] SQL logic error");
        when(mapper.insert(any(PromptTemplatePO.class))).thenThrow(raw);

        // Act + Assert：同一实例原样抛出（不吞不换型）
        assertThatThrownBy(
                        () ->
                                repository.insert(
                                        PromptTemplate.newVersion(
                                                BriefType.STOCK,
                                                "v1.3",
                                                "---SYSTEM---\ns json\n---USER---\nu")))
                .isSameAs(raw);
        assertThat(raw).isNotInstanceOf(CannotAcquireLockException.class);
    }

    /** 构造与 MyBatis-Plus + SQLite 实证同构的 UncategorizedSQLException（cause 文案 = sqlite 报文）。 */
    private static UncategorizedSQLException sqliteConflict(String sqliteMessage) {
        return new UncategorizedSQLException(
                "### Error updating database. Cause: " + sqliteMessage,
                "UPDATE prompt_template SET status=?, updated_at=?",
                new SQLException(sqliteMessage));
    }
}
