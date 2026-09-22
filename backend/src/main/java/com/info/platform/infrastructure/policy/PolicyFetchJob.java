package com.info.platform.infrastructure.policy;

import com.info.platform.domain.policy.PolicyIndustryClassifier;
import com.info.platform.domain.policy.PolicyItem;
import com.info.platform.domain.policy.PolicyRepository;
import com.info.platform.infrastructure.aggregation.GovPolicyClient;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 政策抓取 Job（基础设施层，复用 T07 {@link GovPolicyClient} 抓取能力）。
 *
 * <p>@Scheduled（默认 1h，{@code policy.fetch.interval-millis} 可配）调 {@link
 * GovPolicyClient#fetchPolicies()} 抓 gov.cn/zhengce 最近约 9 条政策 → {@link
 * PolicyRepository#existsBySourceUrl} 去重（同一 source_url 不重复入库）→ {@link
 * PolicyIndustryClassifier#classify} 按标题标注 related_industries → {@link PolicyRepository#saveAll}
 * 落库。
 *
 * <p>受 {@code policy.fetch.enabled} 开关约束：生产 true、测试 false（@SpringBootTest 不装配、@Scheduled 不触发， 对齐 04
 * 测试规范）；{@code @EnableScheduling} 复用 {@code AnomalySchedulingConfig}（与 T15 补推 job 同策略）。
 *
 * <p>容错：fetchPolicies 抛异常 → 记 ERROR 跳过本轮（调度无调用方可上抛，下次调度重试，不阻断）； 单条缺 pubDate → 兜底今日（published_at NOT
 * NULL，记 WARN）；summary 列表页无（待详情页/T28 填，置 null）；source 置「国务院政策」 （gov.cn/zhengce 来源标签，与 T07 {@code
 * PolicySourceAdapter#sourceLabel} 一致）。
 */
@Component
@ConditionalOnProperty(name = "policy.fetch.enabled", havingValue = "true")
public class PolicyFetchJob {

    private static final Logger log = LoggerFactory.getLogger(PolicyFetchJob.class);

    /** 来源标签（gov.cn/zhengce 政策库，与 T07 PolicySourceAdapter.sourceLabel 一致）。 */
    private static final String SOURCE_LABEL = "国务院政策";

    private final GovPolicyClient client;
    private final PolicyRepository repository;

    public PolicyFetchJob(GovPolicyClient client, PolicyRepository repository) {
        this.client = client;
        this.repository = repository;
    }

    /**
     * 政策抓取轮询入口（@Scheduled，默认 1h）。
     *
     * <p>整轮异常不抛出（调度无调用方），记 ERROR 跳过，下次调度重试。
     */
    @Scheduled(fixedDelayString = "${policy.fetch.interval-millis:3600000}")
    public void fetch() {
        Optional<List<Map<String, Object>>> raw;
        try {
            raw = client.fetchPolicies();
        } catch (Exception e) {
            log.error("政策抓取失败，跳过本轮: {}", e.toString());
            return;
        }
        if (raw.isEmpty()) {
            log.debug("政策抓取无数据，跳过本轮");
            return;
        }
        List<PolicyItem> toSave = new ArrayList<>();
        for (Map<String, Object> r : raw.get()) {
            String title = stringOf(r.get("title"));
            String url = stringOf(r.get("url"));
            if (title.isBlank() || url.isBlank()) {
                continue;
            }
            if (repository.existsBySourceUrl(url)) {
                continue;
            }
            LocalDate publishedAt = parseDate(stringOf(r.get("pubDate")));
            List<String> industries = PolicyIndustryClassifier.classify(title);
            toSave.add(PolicyItem.create(title, SOURCE_LABEL, publishedAt, null, industries, url));
        }
        if (toSave.isEmpty()) {
            log.debug("政策抓取去重后无可新增条目");
            return;
        }
        repository.saveAll(toSave);
        log.info("政策抓取入库 {} 条", toSave.size());
    }

    /** 解析 gov.cn {@code <span>} 日期（yyyy-MM-dd）；空/不可解析兜底今日（published_at NOT NULL，记 WARN）。 */
    private static LocalDate parseDate(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) {
            log.warn("政策条目缺发布日期，兜底今日");
            return LocalDate.now(ZoneOffset.UTC);
        }
        try {
            return LocalDate.parse(pubDate.trim());
        } catch (Exception e) {
            log.warn("政策发布日期解析失败 [{}]，兜底今日: {}", pubDate, e.toString());
            return LocalDate.now(ZoneOffset.UTC);
        }
    }

    private static String stringOf(Object value) {
        return value == null ? "" : value.toString();
    }
}
