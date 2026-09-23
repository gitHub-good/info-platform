package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link LlmDefaults} 纯构造单测（ADR-0020，替代原 {@code LlmConfigTest} 的 yml 绑定测试）。
 *
 * <p>覆盖：内置 provider 清单与逐字段值（原 yml {@code llm:} 段原样迁移的防漂移断言）、按名查找、 全局常量、缓存 TTL 分档解析、 环境变量 key
 * 命名映射与缺失归一（ENV 正向流转由 ConfigCenter/Facade 测试经 mockStatic 本类注入验证——System 不可 mock）。
 */
class LlmDefaultsTest {

    @Test
    void providers_matchRetiredYmlSectionFieldByField() {
        // 原 application.yml llm.providers 段逐字段迁移防漂移（ADR-0020 迁移清单的机器可读形态）
        assertThat(LlmDefaults.providers())
                .extracting(LlmDefaults.Provider::name)
                .containsExactly("deepseek", "glm", "qwen", "kimi");

        LlmDefaults.Provider deepseek = LlmDefaults.providerByName("deepseek").orElseThrow();
        assertThat(deepseek.baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(deepseek.model()).isEqualTo("deepseek-flash");
        assertThat(deepseek.enabled()).isTrue();
        assertThat(deepseek.isDefault()).isTrue();
        assertThat(deepseek.fallback()).isEqualTo("glm");
        assertThat(deepseek.inputPricePerMillion()).isEqualTo(1.0);
        assertThat(deepseek.outputPricePerMillion()).isEqualTo(4.0);

        LlmDefaults.Provider glm = LlmDefaults.providerByName("glm").orElseThrow();
        assertThat(glm.baseUrl()).isEqualTo("https://open.bigmodel.cn/api/paas/v4");
        assertThat(glm.model()).isEqualTo("glm-4-flash-250414");
        assertThat(glm.enabled()).isTrue();
        assertThat(glm.isDefault()).isFalse();
        assertThat(glm.fallback()).isEqualTo("deepseek");
        assertThat(glm.inputPricePerMillion()).isZero();
        assertThat(glm.outputPricePerMillion()).isZero();

        // 预留两家：停用、指回 deepseek、免费档不估算（原 yml「预留（enabled=false，按需启用）」）
        for (String reserved : new String[] {"qwen", "kimi"}) {
            LlmDefaults.Provider p = LlmDefaults.providerByName(reserved).orElseThrow();
            assertThat(p.enabled()).as(reserved + " 缺省停用").isFalse();
            assertThat(p.isDefault()).as(reserved + " 非默认").isFalse();
            assertThat(p.fallback()).isEqualTo("deepseek");
            assertThat(p.inputPricePerMillion()).isZero();
            assertThat(p.outputPricePerMillion()).isZero();
        }
        assertThat(LlmDefaults.providerByName("qwen").orElseThrow().baseUrl())
                .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(LlmDefaults.providerByName("qwen").orElseThrow().model()).isEqualTo("qwen-plus");
        assertThat(LlmDefaults.providerByName("kimi").orElseThrow().baseUrl())
                .isEqualTo("https://api.moonshot.cn/v1");
        assertThat(LlmDefaults.providerByName("kimi").orElseThrow().model())
                .isEqualTo("moonshot-v1-8k");
    }

    @Test
    void providerByName_unknownOrNull_returnsEmpty() {
        assertThat(LlmDefaults.providerByName("nonexistent")).isEqualTo(Optional.empty());
        assertThat(LlmDefaults.providerByName(null)).isEqualTo(Optional.empty());
    }

    @Test
    void globalConstants_matchRetiredYmlSection() {
        // 原 yml llm 段全局值原样迁移：超时/重试/日预算/告警阈值/缓存默认 TTL/容量
        assertThat(LlmDefaults.TIMEOUT_SECONDS).isEqualTo(30L);
        assertThat(LlmDefaults.RETRY).isEqualTo(1);
        assertThat(LlmDefaults.DAILY_TOKEN_BUDGET_PER_USER).isEqualTo(20000L);
        assertThat(LlmDefaults.BUDGET_WARN_RATIO).isEqualTo(0.8);
        assertThat(LlmDefaults.CACHE_DEFAULT_TTL_SECONDS).isEqualTo(3600L);
        assertThat(LlmDefaults.CACHE_MAXIMUM_SIZE).isEqualTo(1000L);
        assertThat(LlmDefaults.timeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void ttlFor_resolvesByBriefTypeOrDefault() {
        // 原 yml cache.ttl 分档：个股 1h / 政策解读 1h / 每日推荐 24h；未分档回落默认 1h
        assertThat(LlmDefaults.CACHE_TTL_SECONDS)
                .containsEntry("brief-type-1", 3600L)
                .containsEntry("brief-type-3", 3600L)
                .containsEntry("brief-type-4", 86400L)
                .hasSize(3);
        assertThat(LlmDefaults.ttlFor("1")).isEqualTo(Duration.ofSeconds(3600));
        assertThat(LlmDefaults.ttlFor("3")).isEqualTo(Duration.ofSeconds(3600));
        assertThat(LlmDefaults.ttlFor("4")).isEqualTo(Duration.ofSeconds(86400));
        assertThat(LlmDefaults.ttlFor("9")).isEqualTo(Duration.ofSeconds(3600)); // 未配置→默认
    }

    @Test
    void apiKeyEnvVar_mapsProviderNameToUpperSnakeCase() {
        // 命名约定：provider 名大写 + _API_KEY（原 yml 占位符 ${DEEPSEEK_API_KEY:} 的平移，一等来源不动）
        assertThat(LlmDefaults.apiKeyEnvVar("deepseek")).isEqualTo("DEEPSEEK_API_KEY");
        assertThat(LlmDefaults.apiKeyEnvVar("glm")).isEqualTo("GLM_API_KEY");
        assertThat(LlmDefaults.apiKeyEnvVar("qwen")).isEqualTo("QWEN_API_KEY");
        assertThat(LlmDefaults.apiKeyEnvVar("kimi")).isEqualTo("KIMI_API_KEY");
    }

    @Test
    void envApiKey_absentOrNullName_returnsNull() {
        // 未设变量（getenv 真调；测试 JVM 不注入该变量）与 null 名均归一为 null（无 key 不 fail-fast）
        assertThat(LlmDefaults.envApiKey("zzenvless")).isNull();
        assertThat(LlmDefaults.envApiKey(null)).isNull();
    }
}
