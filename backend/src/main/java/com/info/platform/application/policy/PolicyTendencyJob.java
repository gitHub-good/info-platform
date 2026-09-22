package com.info.platform.application.policy;

import com.info.platform.domain.policy.AiTendency;
import com.info.platform.domain.policy.PolicyItem;
import com.info.platform.domain.policy.PolicyRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 政策倾向批量判断 Job（应用层，T28，对齐技术方案 §4.3 流程 2 + 方案 08 定时任务 + ADR-0006）。
 *
 * <p>{@link Scheduled}（fixedDelay 可配 {@code policy.tendency.interval-millis}，默认 30min）扫近期 {@code
 * ai_tendency=0} 的政策条目 → 逐条调 {@link PolicyTendencyService#judgeTendency} 填 {@code
 * ai_tendency}。批量倾向判断走异步 @Scheduled 而非 GET /policies/{id} 按需触发——避 GET 阻塞 LLM 3~8s（对齐 §5 性能：详情页首屏
 * ≤2s）。
 *
 * <p>受开关控制（测试关）： {@code policy.tendency.enabled=false}（默认）时本 Bean 与 {@link
 * PolicyTendencySchedulingConfig}（{@code @EnableScheduling}）均不装配——@Scheduled 不在
 * {@code @SpringBootTest} 触发，逻辑改由单测直调 {@link PolicyTendencyJob#judgePending} 验证（对齐 04 测试规范 +
 * DailyRecommendationJob 模式）。
 *
 * <p>容错：单条判断异常不阻断其余（{@link PolicyTendencyService#judgeTendency} 内部已吞 LLM/业务异常返 UNJUDGED，
 * 这里再兜底未预期异常）；每轮扫描 {@code policy.tendency.batch-size}（默认 20）上限控成本。
 */
@Component
@ConditionalOnProperty(name = "policy.tendency.enabled", havingValue = "true")
public class PolicyTendencyJob {

    private static final Logger log = LoggerFactory.getLogger(PolicyTendencyJob.class);

    private final PolicyTendencyService service;
    private final PolicyRepository repository;
    private final int daysWindow;
    private final int batchSize;

    public PolicyTendencyJob(
            PolicyTendencyService service,
            PolicyRepository repository,
            @Value("${policy.tendency.days:7}") int days,
            @Value("${policy.tendency.batch-size:20}") int batchSize) {
        this.service = service;
        this.repository = repository;
        this.daysWindow = days;
        this.batchSize = batchSize <= 0 ? 20 : batchSize;
    }

    /** 批量判断入口（@Scheduled 默认 30min）。 */
    @Scheduled(fixedDelayString = "${policy.tendency.interval-millis:1800000}")
    public void judgePending() {
        List<PolicyItem> pending = repository.findRecentUnjudged(daysWindow, batchSize);
        if (pending.isEmpty()) {
            log.debug("政策倾向批量判断：无待判条目，跳过本轮");
            return;
        }
        int filled = 0;
        for (PolicyItem item : pending) {
            try {
                AiTendency tendency = service.judgeTendency(item);
                if (tendency != AiTendency.UNJUDGED) {
                    filled++;
                }
            } catch (Exception e) {
                // judgeTendency 已吞 LLM/业务异常；此处兜底未预期异常，单条不阻断其余
                log.warn("政策倾向判断未预期异常 policyId={}: {}", item.getId(), e.toString());
            }
        }
        log.info("政策倾向批量判断完成 扫描待判 {} 条，成功填倾向 {} 条", pending.size(), filled);
    }
}
