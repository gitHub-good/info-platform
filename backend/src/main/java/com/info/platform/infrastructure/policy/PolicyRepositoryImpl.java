package com.info.platform.infrastructure.policy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.info.platform.domain.policy.AiTendency;
import com.info.platform.domain.policy.PolicyItem;
import com.info.platform.domain.policy.PolicyRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PolicyRepository} 端口的 SQLite/MyBatis-Plus 实现（基础设施层）。
 *
 * <p>PO↔Entity 转换集中于此；时间戳存 ISO-8601 整秒文本（created_at/updated_at），published_at 存 ISO-8601 日期文本
 * （yyyy-MM-dd，字典序即时间序，命中 {@code idx_policy_published} 范围扫描）。
 *
 * <h2>游标分页</h2>
 *
 * newest-first（id DESC）：首页 cursor=null/0 取最新 limit 条；翻页 {@code WHERE id &lt; cursor} 续取。days 时间窗按
 * {@code published_at &gt;= today - days}（UTC 自然日，整日期字符串比较）；行业过滤按 related_industries JSON 文本 LIKE
 * {@code %"industry"%}（引号作 token 边界，防「银行」误命中「商业银行」）。{@code LIMIT n} 经 {@code .last("LIMIT n")}
 * 追加，防深分页 OFFSET（对齐 §4.4）。
 */
@Repository
public class PolicyRepositoryImpl implements PolicyRepository {

    private static final Logger log = LoggerFactory.getLogger(PolicyRepositoryImpl.class);

    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 90;

    private final PolicyMapper mapper;

    public PolicyRepositoryImpl(PolicyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public List<PolicyItem> saveAll(List<PolicyItem> items) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        String now = Instant.now().toString();
        List<PolicyItem> saved = new ArrayList<>(items.size());
        for (PolicyItem item : items) {
            PolicyItemPO po = toPO(item);
            po.setCreatedAt(now);
            po.setUpdatedAt(now);
            if (po.getAiTendency() == null) {
                po.setAiTendency(AiTendency.UNJUDGED.code());
            }
            mapper.insert(po);
            saved.add(toEntity(po));
        }
        log.info("批量落库政策条目 {} 条", saved.size());
        return saved;
    }

    @Override
    public boolean existsBySourceUrl(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            return false;
        }
        return mapper.exists(
                new LambdaQueryWrapper<PolicyItemPO>().eq(PolicyItemPO::getSourceUrl, sourceUrl));
    }

    @Override
    public Optional<PolicyItem> findById(Long id) {
        PolicyItemPO po = mapper.selectById(id);
        return Optional.ofNullable(po).map(PolicyRepositoryImpl::toEntity);
    }

    @Override
    public List<PolicyItem> findRecent(int days, String industry, Long cursor, int limit) {
        int safeDays = days <= 0 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
        String since = LocalDate.now(ZoneOffset.UTC).minusDays(safeDays).toString();
        LambdaQueryWrapper<PolicyItemPO> w =
                new LambdaQueryWrapper<PolicyItemPO>().ge(PolicyItemPO::getPublishedAt, since);
        if (industry != null && !industry.isBlank()) {
            // related_industries 存 JSON 数组文本（["白酒","银行"]），按 "industry" 子串 LIKE 匹配
            // （引号作 token 边界，防「银行」误命中「商业银行」）。
            w.like(PolicyItemPO::getRelatedIndustries, "\"" + industry + "\"");
        }
        if (cursor != null && cursor > 0) {
            w.lt(PolicyItemPO::getId, cursor);
        }
        w.orderByDesc(PolicyItemPO::getId).last("LIMIT " + limit);
        List<PolicyItemPO> pos = mapper.selectList(w);
        return pos.stream().map(PolicyRepositoryImpl::toEntity).toList();
    }

    private static PolicyItem toEntity(PolicyItemPO po) {
        LocalDate pubDate =
                po.getPublishedAt() == null ? null : LocalDate.parse(po.getPublishedAt());
        return PolicyItem.reconstruct(
                po.getId(),
                po.getTitle(),
                po.getSource(),
                pubDate,
                po.getSummary(),
                po.getRelatedIndustries(),
                AiTendency.fromCode(po.getAiTendency()),
                po.getSourceUrl(),
                po.getCreatedAt() == null ? null : Instant.parse(po.getCreatedAt()),
                po.getUpdatedAt() == null ? null : Instant.parse(po.getUpdatedAt()));
    }

    private static PolicyItemPO toPO(PolicyItem item) {
        PolicyItemPO po = new PolicyItemPO();
        po.setId(item.getId());
        po.setTitle(item.getTitle());
        po.setSource(item.getSource());
        po.setPublishedAt(item.getPublishedAt() == null ? null : item.getPublishedAt().toString());
        po.setSummary(item.getSummary());
        po.setRelatedIndustries(item.getRelatedIndustries());
        po.setAiTendency(item.getAiTendency().code());
        po.setSourceUrl(item.getSourceUrl());
        po.setCreatedAt(item.getCreatedAt() == null ? null : item.getCreatedAt().toString());
        po.setUpdatedAt(item.getUpdatedAt() == null ? null : item.getUpdatedAt().toString());
        return po;
    }
}
