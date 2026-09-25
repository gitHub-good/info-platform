package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V19/V20 条件数据迁移语义测试（M12 REQ-20260925-09，方案 §4.2 / ADR-0037 决策 5）。
 *
 * <p>用<b>独立 SQLite 内存库</b>（cache=shared，哨兵连接保活）+ Flyway 编程式 API 分两段执行：
 * 先 target 到前一版本建表 → JDBC 手工插入「迁移前形态」的 runtime_config 行（种子行/用户已改行）→ 继续迁移触发 V19/V20 →
 * 断言只动缺省值行。U19/U20 为社区版手动回滚脚本（不自动执行），以脚本原文经 JDBC 直执行验证还原语义。
 *
 * <p>语义红线（ADR-0037）：条件 UPDATE 幂等——仅当现值为旧缺省时改写，用户手改过的行<b>永不触碰</b>（用户意图优先）；
 * 重放安全（同一 SQL 再执行不产生二次变化）。
 */
class SectionPaginationMigrationTest {

    private static final String OLD_POLICY_URL = "https://www.gov.cn/zhengce/";

    private static final String NEW_POLICY_URL =
            "https://www.gov.cn/zhengce/zuixin/ZUIXINZHENGCE.json";

    private Connection sentinel;

    @BeforeEach
    void setUp() throws Exception {
        // 共享内存库：哨兵连接保活，Flyway 与断言连接看到同一份库（与测试 profile 同机制）
        sentinel =
                DriverManager.getConnection("jdbc:sqlite:file:m12migr?mode=memory&cache=shared");
    }

    @AfterEach
    void tearDown() throws Exception {
        sentinel.close();
    }

    // ---- V19 · announcePageSize 3→10 ----

    @Test
    void v19_migratesOnlySeedDefaultRow_keepsUserModifiedRow() throws Exception {
        try (Connection conn = open()) {
            migrateTo(conn, "18");
            insertConfigRow(
                    conn,
                    "datasource.ANNOUNCE",
                    "{\"params\":{\"announceUrl\":\"https://a\",\"announcePageSize\":3}}");
            insertConfigRow(
                    conn,
                    "datasource.ANNOUNCE.CUSTOM",
                    "{\"params\":{\"announcePageSize\":5}}");

            migrateLatest(conn);

            // 种子缺省 3 → 10；键名不同（非 datasource.ANNOUNCE）的行不受影响
            assertThat(paramOf(conn, "datasource.ANNOUNCE", "announcePageSize")).isEqualTo(10);
            assertThat(paramOf(conn, "datasource.ANNOUNCE.CUSTOM", "announcePageSize")).isEqualTo(5);
        }
    }

    @Test
    void v19_keepsUserModifiedValue_replayIsIdempotent() throws Exception {
        try (Connection conn = open()) {
            migrateTo(conn, "18");
            // 用户已把页大小手改为 5（非种子缺省 3）→ V19 不动（用户意图优先）
            insertConfigRow(
                    conn,
                    "datasource.ANNOUNCE",
                    "{\"params\":{\"announceUrl\":\"https://a\",\"announcePageSize\":5}}");

            migrateLatest(conn);
            assertThat(paramOf(conn, "datasource.ANNOUNCE", "announcePageSize")).isEqualTo(5);

            // 重放安全：V19 SQL 原文再执行一遍（现值 5 ≠ 3）→ 仍为 5
            executeScript(conn, "V19__migrate_announce_page_size.sql");
            assertThat(paramOf(conn, "datasource.ANNOUNCE", "announcePageSize")).isEqualTo(5);
        }
    }

    @Test
    void v19_thenU19_roundTripsSeedDefaultRow() throws Exception {
        try (Connection conn = open()) {
            migrateTo(conn, "18");
            insertConfigRow(
                    conn,
                    "datasource.ANNOUNCE",
                    "{\"params\":{\"announceUrl\":\"https://a\",\"announcePageSize\":3}}");
            migrateLatest(conn);
            assertThat(paramOf(conn, "datasource.ANNOUNCE", "announcePageSize")).isEqualTo(10);

            // U19 手动回滚：仅当现值=10 时还原 3
            executeScript(conn, "U19__revert_announce_page_size.sql");
            assertThat(paramOf(conn, "datasource.ANNOUNCE", "announcePageSize")).isEqualTo(3);
        }
    }

    // ---- V20 · policyUrl → ZUIXINZHENGCE.json + policyMaxItems ----

    @Test
    void v20_migratesOnlyOldCatalogUrlRow_addsMaxItems() throws Exception {
        try (Connection conn = open()) {
            migrateTo(conn, "19");
            insertConfigRow(
                    conn,
                    "datasource.POLICY",
                    "{\"params\":{\"policyUrl\":\"" + OLD_POLICY_URL
                            + "\",\"policyReferer\":\"https://www.gov.cn/\"}}");

            migrateLatest(conn);

            assertThat(paramStringOf(conn, "datasource.POLICY", "policyUrl"))
                    .isEqualTo(NEW_POLICY_URL);
            assertThat(paramOf(conn, "datasource.POLICY", "policyMaxItems")).isEqualTo(30);
        }
    }

    @Test
    void v20_keepsUserModifiedUrl_replayIsIdempotent() throws Exception {
        try (Connection conn = open()) {
            migrateTo(conn, "19");
            // 用户已把 policyUrl 手改为自定义页面 → V20 不动、也不注入 policyMaxItems
            insertConfigRow(
                    conn,
                    "datasource.POLICY",
                    "{\"params\":{\"policyUrl\":\"https://my.example.com/zhengce/\"}}");

            migrateLatest(conn);
            assertThat(paramStringOf(conn, "datasource.POLICY", "policyUrl"))
                    .isEqualTo("https://my.example.com/zhengce/");
            assertThat(paramOf(conn, "datasource.POLICY", "policyMaxItems")).isEqualTo(0);

            // 重放安全：现值非旧目录页 → 条件不命中
            executeScript(conn, "V20__migrate_policy_source_url.sql");
            assertThat(paramStringOf(conn, "datasource.POLICY", "policyUrl"))
                    .isEqualTo("https://my.example.com/zhengce/");
        }
    }

    @Test
    void v20_thenU20_roundTripsAndRemovesMaxItems() throws Exception {
        try (Connection conn = open()) {
            migrateTo(conn, "19");
            insertConfigRow(
                    conn,
                    "datasource.POLICY",
                    "{\"params\":{\"policyUrl\":\"" + OLD_POLICY_URL + "\"}}");
            migrateLatest(conn);

            // U20 手动回滚：还原旧目录页 + json_remove policyMaxItems
            executeScript(conn, "U20__revert_policy_source_url.sql");
            assertThat(paramStringOf(conn, "datasource.POLICY", "policyUrl"))
                    .isEqualTo(OLD_POLICY_URL);
            assertThat(paramOf(conn, "datasource.POLICY", "policyMaxItems")).isEqualTo(0);
        }
    }

    // ---- helpers ----

    private static Connection open() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:file:m12migr?mode=memory&cache=shared");
    }

    /** 全量迁移到 target 版本（分段执行：先到前版本，供手工插种子行）。 */
    private static void migrateTo(Connection conn, String target) {
        Flyway.configure()
                .dataSource("jdbc:sqlite:file:m12migr?mode=memory&cache=shared", "", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .target(target)
                .load()
                .migrate();
    }

    /** 迁移到最新（第二段：触发 V19/V20）。 */
    private static void migrateLatest(Connection conn) {
        Flyway.configure()
                .dataSource("jdbc:sqlite:file:m12migr?mode=memory&cache=shared", "", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load()
                .migrate();
    }

    /** 从 classpath 读迁移脚本原文直执行（U 脚本不自动执行 / V 重放幂等验证）。 */
    private static void executeScript(Connection conn, String fileName) throws Exception {
        String sql =
                new String(
                        Files.readAllBytes(
                                Path.of(
                                        "src/main/resources/db/migration/"
                                                + fileName)),
                        java.nio.charset.StandardCharsets.UTF_8);
        // 去除行注释后按分号切分逐句执行（SQLite JDBC 不接受多语句单次 execute）
        StringBuilder cleaned = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (line.trim().startsWith("--")) {
                continue;
            }
            cleaned.append(line).append('\n');
        }
        try (Statement st = conn.createStatement()) {
            for (String statement : cleaned.toString().split(";")) {
                if (!statement.trim().isEmpty()) {
                    st.execute(statement);
                }
            }
        }
    }

    private static void insertConfigRow(Connection conn, String key, String json) throws Exception {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "INSERT INTO runtime_config (config_key, config_value, created_at,"
                                + " updated_at) VALUES (?, ?, '2026-09-20T00:00:00Z',"
                                + " '2026-09-20T00:00:00Z')")) {
            ps.setString(1, key);
            ps.setString(2, json);
            ps.executeUpdate();
        }
    }

    /** 读 config_value JSON 里 params.<name>（数字口径）；缺失返回 0。 */
    private static long paramOf(Connection conn, String key, String name) throws Exception {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT json_extract(config_value, '$.params." + name + "')"
                                + " FROM runtime_config WHERE config_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getObject(1) != null ? rs.getLong(1) : 0;
            }
        }
    }

    /** 读 config_value JSON 里 params.<name>（字符串口径）；缺失返回 null。 */
    private static String paramStringOf(Connection conn, String key, String name)
            throws Exception {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT json_extract(config_value, '$.params." + name + "')"
                                + " FROM runtime_config WHERE config_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
