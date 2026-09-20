package com.info.platform.infrastructure.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/**
 * SQLite PRAGMA 验证：确认三条 PRAGMA（journal_mode=WAL / foreign_keys=ON / busy_timeout=5000）全部生效。
 *
 * <p>方案演进：最初用 HikariCP {@code connection-init-sql} 以 ';' 分隔三条 PRAGMA，实测 xerial sqlite-jdbc 的 {@code
 * Statement.execute} 对多语句只执行首条（foreign_keys/busy_timeout 不生效）。 改用 xerial URL 参数（{@code ?key=value}
 * 被驱动转为 {@code PRAGMA key=value}），经本测试验证三条全生效。 用文件库（WAL 仅对文件库有意义），临时文件含 -wal/-shm 副本一并清理。不走 Spring
 * 上下文，纯 JDBC 直查。
 */
class SqlitePragmaVerificationTest {

    @Test
    void allThreePragmasTakeEffectViaUrlParams() throws Exception {
        Path dbFile = Files.createTempFile("pragma-verify", ".db");
        HikariConfig config = new HikariConfig();
        // URL 参数传三条 PRAGMA（xerial 把 ?key=value 转为 PRAGMA key=value 执行）
        config.setJdbcUrl(
                "jdbc:sqlite:"
                        + dbFile.toAbsolutePath()
                        + "?journal_mode=WAL&foreign_keys=on&busy_timeout=5000");
        config.setPoolName("pragma-verify");
        config.setMaximumPoolSize(1);
        try (HikariDataSource ds = new HikariDataSource(config);
                Connection conn = ds.getConnection();
                Statement st = conn.createStatement()) {
            String journalMode = pragma(st, "journal_mode");
            int foreignKeys = Integer.parseInt(pragma(st, "foreign_keys"));
            int busyTimeout = Integer.parseInt(pragma(st, "busy_timeout"));
            assertEquals("wal", journalMode.toLowerCase(), "WAL 应经 URL 参数生效");
            assertEquals(1, foreignKeys, "外键应经 URL 参数开启");
            assertEquals(5000, busyTimeout, "写锁等待应经 URL 参数设为 5000ms");
        } finally {
            Files.deleteIfExists(dbFile);
            Files.deleteIfExists(Path.of(dbFile + "-wal"));
            Files.deleteIfExists(Path.of(dbFile + "-shm"));
        }
    }

    private static String pragma(Statement st, String name) throws Exception {
        try (ResultSet rs = st.executeQuery("PRAGMA " + name)) {
            assertTrue(rs.next(), "PRAGMA " + name + " 应有返回值");
            return rs.getString(1);
        }
    }
}
