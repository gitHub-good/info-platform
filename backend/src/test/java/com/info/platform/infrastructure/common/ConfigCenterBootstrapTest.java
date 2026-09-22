package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.application.common.RuntimeConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 配置中心启动引导集成测试（T34）：@SpringBootTest 上下文启动（含 ApplicationReadyEvent）后，各域种子已 seed-if-absent
 * 导入，快照可读、类型化视图可用——验证「启动 DB 值优先于 yml 种子、重启不丢」的引导链路。
 */
@SpringBootTest
@ActiveProfiles("test")
class ConfigCenterBootstrapTest {

    @Autowired private ConfigCenter configCenter;

    @Autowired private RuntimeConfigService configService;

    @Test
    void contextBootstraps_seedsImportedAndSnapshotReadable() {
        // Assert：LLM / 任务 / 聚合 / 数据源四域种子键均落库并进快照（测试 yml 任务开关全 false → 种子 enabled=false）
        assertThat(configService.read("llm.global")).isPresent();
        assertThat(configService.read("job.POLICY_FETCH")).isPresent();
        assertThat(configService.read("job.DAILY_RECOMMEND")).isPresent();
        assertThat(configService.read("aggregation.global")).isPresent();
        assertThat(configCenter.llmGlobal()).isPresent();
        assertThat(
                        configCenter
                                .document("job.DAILY_RECOMMEND")
                                .orElseThrow()
                                .get("enabled")
                                .asBoolean())
                .isFalse();
        // 数据源域 7 键随 T36 落地（测试 yml 无 adapter.mock.enabled → 缺省 true → 种子 mode=MOCK）
        assertThat(configService.read("datasource.QUOTE")).isPresent();
        assertThat(configService.read("datasource.EVENT")).isPresent();
        assertThat(configCenter.dataSource(com.info.platform.domain.aggregation.SourceCode.QUOTE).mode())
                .isEqualTo(com.info.platform.infrastructure.common.RuntimeDataSource.Mode.MOCK);
    }
}
