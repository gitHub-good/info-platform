package com.info.platform.domain.ai;

import java.util.List;
import java.util.Optional;

/**
 * 提示词模板仓储端口（依赖倒置：领域层定义、基础设施层 {@code PromptTemplateRepositoryImpl} 实现）。
 *
 * <p>领域层纯净接口，不依赖 MyBatis/Spring 等框架类型。
 *
 * <h2>读取端口（T20 生成链路）</h2>
 *
 * {@link #findActiveByBriefType} 供 {@code PromptTemplateService}（应用层，T20）按 {@code briefType}
 * 加载当前启用模板， 对齐技术方案 §4.3 流程 2「加载 {@code prompt_template(version, status=1)}」。返回 {@code status=1} 中按
 * {@code version} 降序的首行（启用版本的最新版，Spike-2 §7.5 版本管理）；无启用模板返回 {@link Optional#empty()}。
 *
 * <h2>管理端口（T45 M5 可视化治理）</h2>
 *
 * 列表/按 id 查/插入新版本/置废/改状态/物理删除，供 {@code PromptTemplateAdminService} 编排「编辑即新版本、激活切换唯一不变量、 置废可删」三通道（方案
 * §4.6）：create 与 activate 的「先置废后激活」由应用层在同一事务内编排，本端口保持单条 SQL 语义。
 */
public interface PromptTemplateRepository {

    /**
     * 按简报类型加载当前启用模板的最新版本。
     *
     * <p>查询语义：{@code WHERE brief_type=? AND status=1 ORDER BY version DESC LIMIT 1}。版本为语义字符串（如
     * {@code v1.0}），字典序降序在同类前缀内与时间序一致（{@code v1.0 < v1.1 < v2.0}）。
     *
     * @param briefType 简报类型
     * @return 启用模板；未配置启用模板时返回 {@link Optional#empty()}
     */
    Optional<PromptTemplate> findActiveByBriefType(BriefType briefType);

    /**
     * 按简报类型取全部版本行（含置废；M5 管理面分组列表与版本号生成器的基数）。
     *
     * <p>返回顺序不保证（真实版本序由应用层按 (major, minor) 数值排序表达，ADR-0021）。
     */
    List<PromptTemplate> findAllByBriefType(BriefType briefType);

    /**
     * 按主键查版本行（M5 详情/激活切换/删除守卫的底稿基准）。
     *
     * @return 版本行；不存在返回 {@link Optional#empty()}
     */
    Optional<PromptTemplate> findById(Long id);

    /**
     * 插入新版本行（时间戳由实现层落 {@code Instant.now()}；{@code status} 取实体值——管理面保存即激活传 1）。
     *
     * @return 落库后实体（回填 id 与时间戳）
     */
    PromptTemplate insert(PromptTemplate template);

    /**
     * 将该场景当前全部启用行置废（create/activate 事务内的「先置废」步骤）。
     *
     * @return 置废行数（正常态为 0 或 1；&gt;1 = 唯一激活不变量曾被手工改库破坏，调用方自行记日志）
     */
    int deactivateActive(BriefType briefType);

    /**
     * 更新单行状态（activate 事务内的「后激活」步骤；同步刷新 {@code updated_at}）。
     *
     * @return 是否命中（id 不存在返回 false）
     */
    boolean updateStatus(Long id, int status);

    /**
     * 物理删除单行（仅置废版本可删——激活守卫在应用层，30069）。
     *
     * @return 是否命中（id 不存在返回 false）
     */
    boolean deleteById(Long id);
}
