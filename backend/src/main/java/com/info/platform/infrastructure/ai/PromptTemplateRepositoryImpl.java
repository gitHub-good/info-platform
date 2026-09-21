package com.info.platform.infrastructure.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.ai.PromptTemplateRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * {@link PromptTemplateRepository} 端口的 SQLite/MyBatis-Plus 实现（基础设施层，T20）。
 *
 * <p>PO↔Entity 转换集中于此。{@link #findActiveByBriefType} 查询语义： {@code WHERE brief_type=? AND status=1
 * ORDER BY version DESC LIMIT 1}——启用（{@code status=1}）行中按版本降序取首行，即"启用版本的最新版"（Spike-2 §7.5）。
 *
 * <p>版本为语义字符串（如 {@code v1.0}），字典序降序在同类前缀内与版本序一致（{@code v1.0 < v1.1 < v2.0}）； 用 {@code last("LIMIT
 * 1")} 兜底多启用行场景（理论上同一 {@code briefType} 同时仅一行 {@code status=1}，防御重复播种）。
 */
@Repository
public class PromptTemplateRepositoryImpl implements PromptTemplateRepository {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateRepositoryImpl.class);

    private static final int STATUS_ACTIVE = 1;

    private final PromptTemplateMapper promptTemplateMapper;

    public PromptTemplateRepositoryImpl(PromptTemplateMapper promptTemplateMapper) {
        this.promptTemplateMapper = promptTemplateMapper;
    }

    @Override
    public Optional<PromptTemplate> findActiveByBriefType(BriefType briefType) {
        if (briefType == null) {
            return Optional.empty();
        }
        PromptTemplatePO po =
                promptTemplateMapper.selectOne(
                        new LambdaQueryWrapper<PromptTemplatePO>()
                                .eq(PromptTemplatePO::getBriefType, briefType.code())
                                .eq(PromptTemplatePO::getStatus, STATUS_ACTIVE)
                                .orderByDesc(PromptTemplatePO::getVersion)
                                .last("LIMIT 1"));
        if (po == null) {
            log.warn("未找到启用提示词模板: briefType={}", briefType);
            return Optional.empty();
        }
        return Optional.of(toEntity(po));
    }

    private static PromptTemplate toEntity(PromptTemplatePO po) {
        return PromptTemplate.reconstruct(
                po.getId(),
                BriefType.fromCode(po.getBriefType()),
                po.getVersion(),
                po.getTemplate(),
                po.getStatus());
    }
}
