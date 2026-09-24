package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.application.aggregation.SubjectSyncConfigValidator;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.aggregation.SourceCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/**
 * 数据源配置来源变更的启动链路集成测试（ADR-0032，对齐 ADR-0020 {@code LlmDefaultsBootstrapTest} 先例）： application.yml 已无
 * {@code adapter:} 段，代码内置缺省（{@link DataSourceDefaults}）经种子链路保证全功能可用。
 *
 * <p>三条语义保证（共享上下文 + SQLite 内存库，ApplicationReadyEvent 已随上下文启动触发种子）： ① 全新库首启——datasource.{CODE} 7 键 +
 * subject.sync 由内置缺省播种、类型化视图全链路可用（含热化的备选源开关与补齐的 announceReferer）； ② DB 已有值——再次种子（重启模拟）不覆盖页面权威值
 * （seed-if-absent）； ③ 备选源开关热切换——写 runtime_config 即换快照，消费点视图下次读取即新值（无重启，页面保存即生效）。
 */
@SpringBootTest
@ActiveProfiles("test")
class DataSourceDefaultsBootstrapTest {

    @Autowired private ConfigCenter configCenter;

    @Autowired private RuntimeConfigService configService;

    @Autowired private Environment environment;

    @Test
    void freshBoot_seedsAllDatasourceKeysFromCodeDefaults_viewsFullyFunctional() {
        // yml 已无 adapter 段（ADR-0032）：Environment 不含任何 adapter.* 键——本用例通过 = 键只能来自代码缺省种子
        assertThat(environment.getProperty("adapter.mock.enabled")).isNull();
        assertThat(environment.getProperty("adapter.quote-source")).isNull();
        assertThat(environment.getProperty("adapter.eastmoney.quote-url")).isNull();
        assertThat(environment.getProperty("adapter.tencent.quote-url")).isNull();

        // 7 源键 + 标的池同步键全部播种
        for (SourceCode code : SourceCode.values()) {
            assertThat(configService.read(ConfigCenter.KEY_DATASOURCE_PREFIX + code.name()))
                    .as("datasource.%s", code)
                    .isPresent();
        }
        assertThat(configService.read(SubjectSyncConfigValidator.KEY)).isPresent();

        // 消费点视图全链路可用：行情 URL/字段/备选源开关、公告 referer（T36 缺口补齐）、mode 缺省 MOCK（测试隔离语义等价平移）
        RuntimeDataSource quote = configCenter.dataSource(SourceCode.QUOTE);
        assertThat(quote.mode()).isEqualTo(RuntimeDataSource.Mode.MOCK);
        assertThat(quote.paramString("quoteUrl", null))
                .isEqualTo("https://push2.eastmoney.com/api/qt/stock/get");
        assertThat(quote.paramString("fields", null))
                .isEqualTo("f43,f44,f45,f46,f47,f48,f57,f58,f60,f168,f169,f170,f171");
        assertThat(quote.paramString("backupSource", null)).isEqualTo("auto");
        assertThat(configCenter.dataSource(SourceCode.VALUATION).paramString("backupSource", null))
                .isEqualTo("auto");
        assertThat(
                        configCenter
                                .dataSource(SourceCode.ANNOUNCE)
                                .paramString("announceReferer", null))
                .isEqualTo("https://data.eastmoney.com/");
        assertThat(
                        configCenter
                                .document(SubjectSyncConfigValidator.KEY)
                                .orElseThrow()
                                .path("aShareSource")
                                .asText())
                .isEqualTo("auto");
    }

    @Test
    void existingDbValues_surviveReseed_pageStaysAuthoritative() {
        // Arrange：模拟页面已改值（行情 URL 指向私有网关 + 备选源强制腾讯），保留原文以便还原
        String originalJson =
                configService
                        .read(ConfigCenter.KEY_DATASOURCE_PREFIX + "QUOTE")
                        .orElseThrow()
                        .json();
        configService.write(
                ConfigCenter.KEY_DATASOURCE_PREFIX + "QUOTE",
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":1500,\"retries\":0,"
                        + "\"cacheTtlSeconds\":5,\"failureCacheTtlSeconds\":10,"
                        + "\"params\":{\"quoteUrl\":\"https://quote.example.com/get\","
                        + "\"fields\":\"f43,f57\",\"backupSource\":\"tencent\"}}",
                null);
        try {
            // Act：重启模拟——再走一遍启动就绪（种子导入 + 冻结快照）
            configCenter.onApplicationReady();

            // Assert：DB 已有键不被内置缺省种子覆盖（seed-if-absent，页面值即权威）
            RuntimeDataSource quote = configCenter.dataSource(SourceCode.QUOTE);
            assertThat(quote.paramString("quoteUrl", null))
                    .isEqualTo("https://quote.example.com/get");
            assertThat(quote.paramString("backupSource", null)).isEqualTo("tencent");
            assertThat(quote.mode()).isEqualTo(RuntimeDataSource.Mode.REAL);
        } finally {
            // 还原共享上下文的 DB 行与快照，避免污染其他集成测试（rollback 不会自动回内存快照）
            configService.write(ConfigCenter.KEY_DATASOURCE_PREFIX + "QUOTE", originalJson, null);
        }
    }

    @Test
    void backupSourceSwitch_writeSwapsSnapshot_nextReadUsesNewValueWithoutRestart() {
        // 行情备选源开关：写 runtime_config（校验通过）→ 快照整体替换 → 消费点视图立即读新值
        String originalJson =
                configService
                        .read(ConfigCenter.KEY_DATASOURCE_PREFIX + "VALUATION")
                        .orElseThrow()
                        .json();
        try {
            configService.write(
                    ConfigCenter.KEY_DATASOURCE_PREFIX + "VALUATION",
                    "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":2000,\"retries\":0,"
                            + "\"cacheTtlSeconds\":3600,\"failureCacheTtlSeconds\":30,"
                            + "\"params\":{\"quoteUrl\":\"https://push2.eastmoney.com/api/qt/stock/get\","
                            + "\"valuationFields\":\"f57,f162,f167\",\"backupSource\":\"tencent\"}}",
                    null);
            assertThat(
                            configCenter
                                    .dataSource(SourceCode.VALUATION)
                                    .paramString("backupSource", null))
                    .isEqualTo("tencent");

            // 非法取值被写路径 oneOf 校验拦截（2001 PARAM_INVALID），不进快照
            assertThatIllegalBackupSourceRejected();
        } finally {
            configService.write(
                    ConfigCenter.KEY_DATASOURCE_PREFIX + "VALUATION", originalJson, null);
        }

        // A 股列表源开关：subject.sync.aShareSource 同语义（写即热生效）
        String originalSubjectSync =
                configService.read(SubjectSyncConfigValidator.KEY).orElseThrow().json();
        try {
            configService.write(
                    SubjectSyncConfigValidator.KEY, "{\"aShareSource\":\"sina\"}", null);
            assertThat(
                            configCenter
                                    .document(SubjectSyncConfigValidator.KEY)
                                    .orElseThrow()
                                    .path("aShareSource")
                                    .asText())
                    .isEqualTo("sina");
        } finally {
            configService.write(SubjectSyncConfigValidator.KEY, originalSubjectSync, null);
        }
    }

    /** 非法备选源开关写入被校验器拦截（msg 带字段级原因），不进快照。 */
    private void assertThatIllegalBackupSourceRejected() {
        try {
            configService.write(
                    ConfigCenter.KEY_DATASOURCE_PREFIX + "VALUATION",
                    "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":2000,\"retries\":0,"
                            + "\"cacheTtlSeconds\":3600,\"failureCacheTtlSeconds\":30,"
                            + "\"params\":{\"backupSource\":\"sina\"}}",
                    null);
            throw new AssertionError("非法 backupSource 应被校验器拦截");
        } catch (com.info.platform.domain.common.BusinessException expected) {
            assertThat(expected.getMessage()).contains("backupSource");
        }
    }
}
