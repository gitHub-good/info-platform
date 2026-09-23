package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.info.platform.application.ai.PromptTemplateAdminService;
import com.info.platform.application.ai.PromptTemplateAdminService.CreateCommand;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.ai.PromptTemplateRepository;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

/**
 * PromptTemplateAdminService 集成测试（T45，方案 §4.6 不变量事务）：SQLite 共享内存库真实事务（不挂 @Transactional
 * 外层，服务事务独立提交/回滚），验证「任何操作后每场景恒有且仅有一个 status=1」与冲突回滚零变更。
 *
 * <p>冲突回滚场景用 @SpyBean 模拟并发竞争的过期读（findAllByBriefType 返回不含冲突行的旧清单），生成器据此产出已存在版本 → UNIQUE 兜底 30070 →
 * 服务事务回滚。@AfterEach 恢复 V8/V14 种子态并 reset spy（共享内存库跨类可见，防污染）。
 */
@SpringBootTest
@ActiveProfiles("test")
class PromptTemplateAdminServiceIntegrationTest {

    /**
     * 测试用新模板：保留 V8 播种 v1.0 全部 17 个占位符（diff 基准 = 激活版全文，缺键即触发 30068 确认通道）， 仅改措辞——验证「改模板 → 保存即新版本激活 →
     * 下一次生成直查即用新值」主路径。
     */
    private static final String NEW_TEMPLATE =
            "---SYSTEM---\n你是金融信息分析师（v2 措辞）。生成结构化 json 简报。\n---USER---\n"
                    + "标的：{{subjectName}}({{subjectCode}})，行业：{{industry}}\n"
                    + "当前价：{{price}} 日涨跌幅：{{changePct}}% 昨收：{{preClose}}\n"
                    + "财务(报告期{{reportDate}})：营收{{revenue}} 归母净利{{netProfit}} 同比{{netProfitYoy}}%\n"
                    + "毛利率{{grossMargin}}% ROE{{roe}}%\n"
                    + "估值：PE(TTM){{peTtm}} PB{{pb}} PS{{ps}}\n"
                    + "近24h公告：{{announcementsList}}\n近7天新闻：{{newsList}}\n"
                    + "请输出 json：{summary, keyEvents[], bias, biasReason, watchSuggestion, facts[], disclaimer}";

    @Autowired private PromptTemplateAdminService adminService;
    @Autowired private PromptTemplateMapper promptTemplateMapper;

    @SpyBean private PromptTemplateRepository promptTemplateRepository;

    /** V8/V14 播种态快照（首个用例前捕获一次；物理删除也能整体复原）。 */
    private static List<PromptTemplatePO> seedSnapshot;

    @BeforeEach
    void snapshotSeedOnce() {
        if (seedSnapshot == null) {
            seedSnapshot =
                    promptTemplateMapper.selectList(null).stream()
                            .map(PromptTemplateAdminServiceIntegrationTest::copyOf)
                            .toList();
        }
    }

    @AfterEach
    void restoreSeed() {
        reset(promptTemplateRepository);
        // 整表复原（测试可能物理删除种子行，仅按版本/状态修补不够）
        promptTemplateMapper.delete(
                new LambdaQueryWrapper<PromptTemplatePO>().ge(PromptTemplatePO::getId, 0));
        seedSnapshot.forEach(po -> promptTemplateMapper.insert(copyOf(po)));
    }

    /** PO 拷贝（id 置空走自增，其余字段原样）。 */
    private static PromptTemplatePO copyOf(PromptTemplatePO po) {
        PromptTemplatePO copy = new PromptTemplatePO();
        copy.setBriefType(po.getBriefType());
        copy.setVersion(po.getVersion());
        copy.setTemplate(po.getTemplate());
        copy.setStatus(po.getStatus());
        copy.setCreatedAt(po.getCreatedAt());
        copy.setUpdatedAt(po.getUpdatedAt());
        return copy;
    }

    @Test
    void create_savesNewVersionAndKeepsSingleActiveInvariant() {
        // Arrange：种子 STOCK v1.0 激活
        PromptTemplate before =
                promptTemplateRepository.findActiveByBriefType(BriefType.STOCK).orElseThrow();

        // Act：保存即激活（MINOR）
        var result =
                adminService.create(
                        new CreateCommand(BriefType.STOCK, null, NEW_TEMPLATE, null, Set.of()));

        // Assert：新版本 v1.1 激活、旧版置废、恒一行 status=1、下一次生成直查即用新值
        assertThat(result.version()).isEqualTo("v1.1");
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.deactivatedVersion()).isEqualTo("v1.0");
        PromptTemplate activeNow =
                promptTemplateRepository.findActiveByBriefType(BriefType.STOCK).orElseThrow();
        assertThat(activeNow.getVersion()).isEqualTo("v1.1");
        assertThat(activeNow.getTemplate()).isEqualTo(NEW_TEMPLATE);
        List<PromptTemplate> rows = promptTemplateRepository.findAllByBriefType(BriefType.STOCK);
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().filter(PromptTemplate::isActive)).hasSize(1);
        assertThat(
                        rows.stream()
                                .filter(t -> "v1.0".equals(t.getVersion()))
                                .findFirst()
                                .orElseThrow())
                .satisfies(t -> assertThat(t.isActive()).isFalse());
        assertThat(before).isNotNull();
    }

    @Test
    void create_uniqueConflictRollsBack_priorActiveUntouched() {
        // Arrange：预埋 v1.1 置废行（模拟另一并发请求已创建）+ spy 过期读（版本清单不含 v1.1）
        seedRow(BriefType.STOCK, "v1.1", 0);
        doReturn(
                        promptTemplateRepository.findAllByBriefType(BriefType.STOCK).stream()
                                .filter(t -> "v1.0".equals(t.getVersion()))
                                .toList())
                .when(promptTemplateRepository)
                .findAllByBriefType(BriefType.STOCK);

        // Act：生成器据过期清单产出 v1.1 → 插入撞 UNIQUE → 30070 + 事务回滚
        assertThatThrownBy(
                        () ->
                                adminService.create(
                                        new CreateCommand(
                                                BriefType.STOCK,
                                                null,
                                                NEW_TEMPLATE,
                                                null,
                                                Set.of())))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.PROMPT_TEMPLATE_VERSION_CONFLICT));

        // Assert：回滚零变更——旧激活继续生效、预埋行未被顶掉、行数不变（经 mapper 读，绕开 spy 过期读桩）
        PromptTemplate active =
                promptTemplateRepository.findActiveByBriefType(BriefType.STOCK).orElseThrow();
        assertThat(active.getVersion()).isEqualTo("v1.0");
        List<PromptTemplatePO> stockRows =
                promptTemplateMapper.selectList(
                        new LambdaQueryWrapper<PromptTemplatePO>()
                                .eq(PromptTemplatePO::getBriefType, BriefType.STOCK.code()));
        assertThat(stockRows).hasSize(2);
        assertThat(stockRows.stream().filter(po -> po.getStatus() == 1)).hasSize(1);
        assertThat(
                        stockRows.stream()
                                .filter(po -> "v1.1".equals(po.getVersion()))
                                .findFirst()
                                .orElseThrow())
                .satisfies(po -> assertThat(po.getStatus()).isZero());
    }

    @Test
    void activate_rollsBackToOldVersion_singleActiveMaintained_andIdempotent() {
        // Arrange：先创建 v1.1（保存即激活）
        adminService.create(new CreateCommand(BriefType.STOCK, null, NEW_TEMPLATE, null, Set.of()));
        Long v10Id =
                promptTemplateRepository.findAllByBriefType(BriefType.STOCK).stream()
                        .filter(t -> "v1.0".equals(t.getVersion()))
                        .findFirst()
                        .orElseThrow()
                        .getId();

        // Act：回滚激活 v1.0
        var rolledBack = adminService.activate(v10Id);

        // Assert：唯一激活不变量 + 回执
        assertThat(rolledBack.version()).isEqualTo("v1.0");
        assertThat(rolledBack.deactivatedVersion()).isEqualTo("v1.1");
        assertThat(
                        promptTemplateRepository.findAllByBriefType(BriefType.STOCK).stream()
                                .filter(PromptTemplate::isActive))
                .hasSize(1)
                .first()
                .satisfies(t -> assertThat(t.getVersion()).isEqualTo("v1.0"));

        // Act + Assert：重复激活自身 → 幂等（无被顶版本）
        var again = adminService.activate(v10Id);
        assertThat(again.deactivatedVersion()).isNull();
        assertThat(
                        promptTemplateRepository.findAllByBriefType(BriefType.STOCK).stream()
                                .filter(PromptTemplate::isActive))
                .hasSize(1);
    }

    @Test
    void delete_guardsActiveVersion_butRemovesRetiredPhysically() {
        // Arrange：v1.0 激活 + 新建 v1.1（旧版置废）
        adminService.create(new CreateCommand(BriefType.STOCK, null, NEW_TEMPLATE, null, Set.of()));
        Long activeId =
                promptTemplateRepository
                        .findActiveByBriefType(BriefType.STOCK)
                        .orElseThrow()
                        .getId();
        Long retiredId =
                promptTemplateRepository.findAllByBriefType(BriefType.STOCK).stream()
                        .filter(t -> !t.isActive())
                        .findFirst()
                        .orElseThrow()
                        .getId();

        // Act + Assert：激活删除被守卫（30069），行仍在
        assertThatThrownBy(() -> adminService.delete(activeId))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(
                                                ErrorCode.PROMPT_TEMPLATE_ACTIVE_DELETE_FORBIDDEN));
        assertThat(promptTemplateRepository.findById(activeId)).isPresent();

        // Act + Assert：置废版本物理删除
        var deleted = adminService.delete(retiredId);
        assertThat(deleted.deleted()).isTrue();
        assertThat(promptTemplateRepository.findById(retiredId)).isEmpty();
        assertThat(
                        promptTemplateRepository.findAllByBriefType(BriefType.STOCK).stream()
                                .filter(PromptTemplate::isActive))
                .hasSize(1);
    }

    /** 经 mapper 预埋一行（测试结束由 restoreSeed 清理）。 */
    private void seedRow(BriefType briefType, String version, int status) {
        PromptTemplatePO po = new PromptTemplatePO();
        po.setBriefType(briefType.code());
        po.setVersion(version);
        po.setTemplate("---SYSTEM---\ns json\n---USER---\nu");
        po.setStatus(status);
        po.setCreatedAt("2026-09-22T00:00:00Z");
        po.setUpdatedAt("2026-09-22T00:00:00Z");
        promptTemplateMapper.insert(po);
    }
}
