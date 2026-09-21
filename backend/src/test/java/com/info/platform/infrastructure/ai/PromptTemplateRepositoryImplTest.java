package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.ai.PromptTemplateRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * PromptTemplateRepositoryImpl 集成测试（T20）：SQLite 共享内存库 + Flyway V8 建表播种后， 测 4 类 v1 模板可查 +
 * findActiveByBriefType 返回 status=1 最新版本 + null 防御 + v1 模板正文完整性（分段标记 + system 含 "json"）。
 *
 * <p>@SpringBootTest 启动完整上下文（含 Flyway V8 迁移播种 4 行 v1.0 status=1）；@Transactional 每用例结束回滚 隔离（V8 播种行属
 * Flyway 事务，在测试外提交，每用例均可见）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PromptTemplateRepositoryImplTest {

    @Autowired private PromptTemplateRepository promptTemplateRepository;
    @Autowired private PromptTemplateMapper promptTemplateMapper;

    @Test
    void findActiveByBriefType_v8Seed_returnsAllFourActiveV1Templates() {
        // Act + Assert：V8 播种的 4 类 v1.0 status=1 模板均可查、版本 v1.0、启用
        for (BriefType type : BriefType.values()) {
            Optional<PromptTemplate> found = promptTemplateRepository.findActiveByBriefType(type);
            assertThat(found).as("briefType=%s 应有 v1.0 启用模板", type).isPresent();
            PromptTemplate t = found.get();
            assertThat(t.getBriefType()).isEqualTo(type);
            assertThat(t.getVersion()).isEqualTo("v1.0");
            assertThat(t.isActive()).isTrue();
        }
    }

    @Test
    void findActiveByBriefType_v1SeedTemplateHasMarkersAndJsonInSystem() {
        // Act：取个股 v1 模板（T21 依赖其分段与 json 前提）
        PromptTemplate stock =
                promptTemplateRepository.findActiveByBriefType(BriefType.STOCK).orElseThrow();

        // Assert：正文含分段标记 + system 段含 "json"（DeepSeek JSON mode 前提，Spike-2 §5.2）
        //        + 双花括号占位符 + 单花括号 JSON 输出格式约束
        String template = stock.getTemplate();
        assertThat(template).contains("---SYSTEM---").contains("---USER---");
        assertThat(template).contains("{{subjectName}}").contains("{{subjectCode}}");
        assertThat(template).contains("json");
        assertThat(template)
                .contains(
                        "{summary, keyEvents[], bias, biasReason, watchSuggestion, facts[], disclaimer}");
    }

    @Test
    void findActiveByBriefType_returnsStatus1LatestVersion() {
        // Arrange：在 V8 播种的 v1.0 status=1 基础上，灌入 v2.0 status=1（更新）与 v1.1 status=0（废弃）
        //          预期返回 status=1 中 version DESC 首行 = v2.0（v1.1 status=0 不入选）
        seed(BriefType.STOCK, "v2.0", 1);
        seed(BriefType.STOCK, "v1.1", 0);

        // Act
        Optional<PromptTemplate> found =
                promptTemplateRepository.findActiveByBriefType(BriefType.STOCK);

        // Assert：返回 v2.0（启用且版本最新）；v1.1 废弃不返回
        assertThat(found).isPresent();
        assertThat(found.get().getVersion()).isEqualTo("v2.0");
        assertThat(found.get().isActive()).isTrue();
    }

    @Test
    void findActiveByBriefType_allDisabled_returnsEmpty() {
        // Arrange：把 V8 播种的 STOCK v1.0 置废，无启用行
        promptTemplateMapper.selectList(null).stream()
                .filter(po -> po.getBriefType() == BriefType.STOCK.code())
                .forEach(
                        po -> {
                            po.setStatus(0);
                            promptTemplateMapper.updateById(po);
                        });

        // Act + Assert：无启用模板返回 empty（Service 据此抛 PROMPT_TEMPLATE_NOT_FOUND）
        assertThat(promptTemplateRepository.findActiveByBriefType(BriefType.STOCK)).isEmpty();
    }

    @Test
    void findActiveByBriefType_nullArg_returnsEmptyDefensively() {
        // Act + Assert：null 入参直接返回 empty（防御，避免 NPE）
        assertThat(promptTemplateRepository.findActiveByBriefType(null)).isEmpty();
    }

    /** 经 mapper 灌入一行（自定义 version/status，测试用例结束随 @Transactional 回滚）。 */
    private void seed(BriefType briefType, String version, int status) {
        PromptTemplatePO po = new PromptTemplatePO();
        po.setBriefType(briefType.code());
        po.setVersion(version);
        po.setTemplate("---SYSTEM---\ns\n---USER---\nu {{x}}");
        po.setStatus(status);
        po.setCreatedAt("2026-09-21T00:00:00Z");
        po.setUpdatedAt("2026-09-21T00:00:00Z");
        promptTemplateMapper.insert(po);
    }
}
