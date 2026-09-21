package com.info.platform.domain.ai;

import java.util.Optional;

/**
 * 提示词模板仓储端口（依赖倒置：领域层定义、基础设施层 {@code PromptTemplateRepositoryImpl} 实现）。
 *
 * <p>领域层纯净接口，不依赖 MyBatis/Spring 等框架类型。
 *
 * <h2>读取端口</h2>
 *
 * {@link #findActiveByBriefType} 供 {@code PromptTemplateService}（应用层，T20）按 {@code briefType}
 * 加载当前启用模板， 对齐技术方案 §4.3 流程 2「加载 {@code prompt_template(version, status=1)}」。返回 {@code status=1} 中按
 * {@code version} 降序的首行（启用版本的最新版，Spike-2 §7.5 版本管理）；无启用模板返回 {@link Optional#empty()}。
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
}
