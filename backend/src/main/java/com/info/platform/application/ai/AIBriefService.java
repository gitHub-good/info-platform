package com.info.platform.application.ai;

import com.info.platform.application.aggregation.AggregationService;
import com.info.platform.application.aggregation.SubjectDetail;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.ai.AiBrief;
import com.info.platform.domain.ai.AiBriefDoneEvent;
import com.info.platform.domain.ai.AiBriefRepository;
import com.info.platform.domain.ai.BriefContent;
import com.info.platform.domain.ai.BriefContentCodec;
import com.info.platform.domain.ai.BriefStatus;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.LlmCostBudget;
import com.info.platform.domain.ai.LlmException;
import com.info.platform.domain.ai.LlmGateway;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.UserContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * AI 简报应用服务（应用层，T21，对齐技术方案 §4.3 流程 2 + §4.1.4）。
 *
 * <p>三大用例：
 *
 * <ol>
 *   <li>{@link #createBrief}（POST 触发）：成本预检 → 幂等查重（已完成直返上次 taskId）→ INSERT 任务 status=0 → 202 +
 *       taskId → 发 {@link AiBriefGenerationRequestedEvent} 触发异步生成。
 *   <li>{@link #onGenerationRequested}（@Async @EventListener）：消费上述事件 → {@link #generateBrief}
 *       在虚拟线程异步执行 （经事件多播器调用代理，@Async 生效，避自调用坑；对齐 {@code PushService.onAnomalyDetected}）。
 *   <li>{@link #getBrief}（GET 查询）：返回 status/content/sourceLinks/disclaimer；懒查超时（PENDING &gt;
 *       30min）→ 强制 FAILED。
 * </ol>
 *
 * <p>异步生成编排（{@link #generateAndPersist}）：CAS 领取 → UserContext.set（计入成本）→ 取聚合上下文 → 加载模板渲染 →
 * LlmGateway.chat（JSON mode）→ BriefContentParser 解析 → 幻觉校验 → 写 source_links/cost_tokens/model →
 * status=1/3 → 发 {@link AiBriefDoneEvent}（PushService 推送）。失败兜底：LlmException/解析失败/quota→status=2。
 *
 * <p>成本预检同步在 POST（fail-fast 429）；LlmGateway 内部二次校验（防 POST 与异步间预算被并发吃掉）。
 */
@Service
public class AIBriefService {

    private static final Logger log = LoggerFactory.getLogger(AIBriefService.class);

    private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final int STALE_TIMEOUT_MINUTES = 30;

    private final AiBriefRepository repository;
    private final LlmGateway llmGateway;
    private final PromptTemplateService promptTemplateService;
    private final BriefContextBuilder contextBuilder;
    private final DailyRecommendationContextBuilder dailyContextBuilder;
    private final AggregationService aggregationService;
    private final SubjectRepository subjectRepository;
    private final HallucinationChecker hallucinationChecker;
    private final BriefContentCodec contentCodec;
    private final LlmCostBudget costBudget;
    private final ApplicationEventPublisher eventPublisher;

    public AIBriefService(
            AiBriefRepository repository,
            LlmGateway llmGateway,
            PromptTemplateService promptTemplateService,
            BriefContextBuilder contextBuilder,
            DailyRecommendationContextBuilder dailyContextBuilder,
            AggregationService aggregationService,
            SubjectRepository subjectRepository,
            HallucinationChecker hallucinationChecker,
            BriefContentCodec contentCodec,
            LlmCostBudget costBudget,
            ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
        this.contextBuilder = contextBuilder;
        this.dailyContextBuilder = dailyContextBuilder;
        this.aggregationService = aggregationService;
        this.subjectRepository = subjectRepository;
        this.hallucinationChecker = hallucinationChecker;
        this.contentCodec = contentCodec;
        this.costBudget = costBudget;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 受理生成请求（POST /ai-briefs）。
     *
     * @param subjectId 标的 id（每日推荐型可空）
     * @param briefType 简报类型
     * @return 任务 id（已存在则返回上次 taskId，幂等）
     * @throws BusinessException 30030 成本上限（429）；30001 标的不存在（404，个股/事件/政策型）
     */
    public Long createBrief(Long subjectId, BriefType briefType) {
        Objects.requireNonNull(briefType, "briefType 必填");
        long userId = currentUserId();
        // 成本预检：fail-fast，POST 同步返回 429（§4.1.4）
        costBudget.checkBudget(userId);
        String idempotencyKey = buildIdempotencyKey(subjectId, briefType);
        // 幂等查重：已存在（任意态）直返上次 taskId（§4.1.4 防重放；幂等短路先于标的校验，免重复请求重验标的）
        Optional<AiBrief> existing = repository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Long tid = existing.get().getId();
            log.info(
                    "AI 简报幂等命中: idempotencyKey={} taskId={} status={}",
                    idempotencyKey,
                    tid,
                    existing.get().getStatus());
            return tid;
        }
        // 个股/事件/政策型须校验标的（每日推荐型无单一标的，跳过）
        if (subjectId != null) {
            subjectRepository
                    .findById(subjectId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.SUBJECT_NOT_FOUND));
        }
        AiBrief brief;
        try {
            brief = repository.save(AiBrief.createNew(subjectId, briefType, idempotencyKey));
        } catch (DuplicateKeyException e) {
            // 并发双 POST 败者（体检 P2 后端条目）：应用层查重后、INSERT 前对手方提交同幂等键，
            // 仓储已译 DuplicateKeyException——重查幂等键按命中处理返回同一任务，不冒泡 500
            return findExistingByConflict(idempotencyKey, e);
        }
        log.info(
                "AI 简报受理: taskId={} subjectId={} briefType={} userId={}",
                brief.getId(),
                subjectId,
                briefType,
                userId);
        // 异步触发（经事件多播器→代理，@Async 生效）
        eventPublisher.publishEvent(new AiBriefGenerationRequestedEvent(brief.getId(), userId));
        return brief.getId();
    }

    /**
     * 只读查当日简报（P1-5a feed 只读口径）：按幂等键查当日任务并返回视图，<b>不受理、不触发生成、不等待</b>。
     *
     * <p>与 {@link #createBrief} 的差异：当日无任务返回空（由调用方决定是否降级），调用方据此「只读当日缓存」—— feed 路径不再因 {@code
     * generateDaily} 的 30s 轮询阻塞请求线程。懒查超时口径与 {@link #getBrief} 一致（PENDING 超 30min 强制 FAILED）。
     *
     * @param subjectId 标的 id（每日推荐型可空）
     * @param briefType 简报类型
     * @return 当日任务视图（含 status；PENDING=在途生成中）；当日无任务为空
     */
    public Optional<AIBriefView> findTodayBrief(Long subjectId, BriefType briefType) {
        Objects.requireNonNull(briefType, "briefType 必填");
        String idempotencyKey = buildIdempotencyKey(subjectId, briefType);
        return repository
                .findByIdempotencyKey(idempotencyKey)
                .map(brief -> buildView(applyStaleTimeout(brief)));
    }

    /**
     * 并发败者按幂等命中处理（体检 P2 后端条目）：INSERT 撞 {@code uq_ai_brief_idempotency} 后重查幂等键。
     *
     * <p>重查命中 → 返回既有 taskId（赢家已触发异步生成，不再重复触发）；重查仍空（UNIQUE 冲突但行不可见，理论不达）→ 原样上抛 {@link
     * DuplicateKeyException} 由全局处理器兜底，不吞异常。
     */
    private Long findExistingByConflict(String idempotencyKey, DuplicateKeyException cause) {
        Optional<AiBrief> existing = repository.findByIdempotencyKey(idempotencyKey);
        if (existing.isEmpty()) {
            log.error("AI 简报并发冲突后重查幂等键未命中: idempotencyKey={}", idempotencyKey);
            throw cause;
        }
        Long tid = existing.get().getId();
        log.info(
                "AI 简报并发幂等命中（败者返回既有任务）: idempotencyKey={} taskId={} status={}",
                idempotencyKey,
                tid,
                existing.get().getStatus());
        return tid;
    }

    /**
     * 异步消费生成请求（@Async 虚拟线程 + @EventListener 类型匹配）。
     *
     * <p>异常兜底：处理过程任何异常记 ERROR 不上抛（异步监听器异常会被 Spring 吞并记错，这里显式记上下文便于告警定位）。
     */
    @Async("aiBriefAsyncExecutor")
    @EventListener
    public void onGenerationRequested(AiBriefGenerationRequestedEvent event) {
        try {
            generateBrief(event.taskId(), event.userId());
        } catch (Exception e) {
            log.error("AI 简报异步生成异常 taskId={}: {}", event.taskId(), e.toString(), e);
        }
    }

    /**
     * 生成简报（异步 Worker 主体，包内可见便于单测直调绕过 @Async 代理）。
     *
     * <p>UserContext.set → 计入成本配额；finally clear 防线程池复用串味。
     */
    void generateBrief(Long taskId, long userId) {
        UserContext.set(new UserContext.Principal(userId, "ai-brief-worker"));
        try {
            generateInternal(taskId, userId);
        } finally {
            UserContext.clear();
        }
    }

    /** 生成编排内部：领取 → 生成持久化（异常兜底置失败）→ 发完成事件（任何终态都通知）。 */
    private void generateInternal(Long taskId, long userId) {
        Optional<AiBrief> opt = repository.findById(taskId);
        if (opt.isEmpty()) {
            log.warn("AI 简报生成：任务不存在 taskId={}", taskId);
            return;
        }
        AiBrief loaded = opt.get();
        if (loaded.getStatus().isTerminal()) {
            log.info("AI 简报任务已终态，跳过 taskId={} status={}", taskId, loaded.getStatus());
            return;
        }
        Optional<AiBrief> claimed = repository.claim(taskId, loaded.getVersion());
        if (claimed.isEmpty()) {
            log.info("AI 简报 CAS 领取未命中（被并发领走）taskId={}", taskId);
            return;
        }
        AiBrief brief = claimed.get();
        BriefStatus finalStatus;
        try {
            finalStatus = generateAndPersist(brief, userId);
        } catch (LlmException e) {
            log.warn("AI 简报 LLM 调用失败 taskId={} providers={}", taskId, e.attemptedProviders());
            finalStatus = failSafely(brief);
        } catch (BusinessException e) {
            log.warn(
                    "AI 简报生成业务异常 taskId={} code={}: {}",
                    taskId,
                    e.getErrorCode().getCode(),
                    e.getMessage());
            finalStatus = failSafely(brief);
        } catch (Exception e) {
            log.error("AI 简报生成未预期异常 taskId={}: {}", taskId, e.toString(), e);
            finalStatus = failSafely(brief);
        }
        eventPublisher.publishEvent(
                new AiBriefDoneEvent(taskId, userId, brief.getSubjectId(), finalStatus));
    }

    /**
     * 生成并持久化（CAS 领取后调用）。返回终态。
     *
     * @throws LlmException 所有 provider 失败
     * @throws BusinessException 模板缺失 / 配额用尽（chat 内二次校验）
     */
    private BriefStatus generateAndPersist(AiBrief brief, long userId) {
        Subject subject = loadSubject(brief);
        SubjectDetail detail = loadContext(brief, subject);
        PromptTemplate template = promptTemplateService.loadActiveTemplate(brief.getBriefType());
        Map<String, String> context = contextBuilder.build(detail, brief.getBriefType());
        // T23：每日推荐型（briefType=4）上下文由 DailyRecommendationContextBuilder 装配（poolMetrics/订阅主题/今日），
        // 合并进个股投影为空的上下文 Map（对齐 BriefContextBuilder 注释「每日推荐占位符由 T23 装配」）。userId 来自异步事件。
        // 防御性拷贝再 putAll——BriefContextBuilder 返回的 Map 可能不可变（单测 mock 为 Map.of()），避免
        // UnsupportedOperationException。
        if (brief.getBriefType() == BriefType.DAILY_RECOMMEND) {
            Map<String, String> merged = new LinkedHashMap<>(context);
            merged.putAll(dailyContextBuilder.buildContext(userId));
            context = merged;
        }
        LlmRequest req =
                LlmRequest.json(
                        promptTemplateService.render(template, context),
                        brief.getBriefType().key());
        LlmResponse resp = llmGateway.chat(req);
        // 解析失败 → status=2（不抛，直接置失败返回）
        Optional<BriefContent> parsed = contentCodec.parse(resp.content());
        if (parsed.isEmpty()) {
            log.warn("AI 简报解析失败 taskId={} provider={}", brief.getId(), resp.provider());
            brief.markFailed();
            repository.save(brief);
            return BriefStatus.FAILED;
        }
        BriefContent content = parsed.get();
        // 幻觉校验（subject 缺失则跳过，不降级）
        HallucinationResult result =
                subject == null
                        ? new HallucinationResult(false, List.of())
                        : hallucinationChecker.check(content.facts(), subject);
        log.info(
                "AI 简报幻觉校验 taskId={} needVerify={} checks={}",
                brief.getId(),
                result.needVerify(),
                result.checks().size());
        String contentJson = contentCodec.writeJson(content);
        String sourceLinks = contentCodec.writeSourceLinks(content);
        Integer cost = resp.usage() == null ? null : resp.usage().totalTokens();
        if (result.needVerify()) {
            brief.markNeedVerify(
                    contentJson, sourceLinks, cost, resp.model(), template.getVersion());
        } else {
            brief.complete(contentJson, sourceLinks, cost, resp.model(), template.getVersion());
        }
        repository.save(brief);
        log.info(
                "AI 简报生成完成 taskId={} status={} cost={} model={}",
                brief.getId(),
                brief.getStatus(),
                cost,
                brief.getModel());
        return brief.getStatus();
    }

    /** 置失败并持久化（best-effort，存库异常仅记 ERROR）。 */
    private BriefStatus failSafely(AiBrief brief) {
        try {
            brief.markFailed();
            repository.save(brief);
        } catch (Exception ex) {
            log.error("AI 简报置失败时存库异常 taskId={}", brief.getId(), ex);
        }
        return BriefStatus.FAILED;
    }

    /** 取标的实体（幻觉校验与 adapter 取数用）；每日推荐型 / 标的已删 → null。 */
    private Subject loadSubject(AiBrief brief) {
        if (brief.getSubjectId() == null) {
            return null;
        }
        return subjectRepository.findById(brief.getSubjectId()).orElse(null);
    }

    /** 取聚合上下文（个股/事件/政策型；subject 缺失或聚合全失败 → null，prompt 占位符保留）。 */
    private SubjectDetail loadContext(AiBrief brief, Subject subject) {
        if (subject == null) {
            return null;
        }
        try {
            return aggregationService.getDetail(subject.getId(), Set.of());
        } catch (BusinessException e) {
            log.warn("AI 简报取聚合上下文失败 taskId={}: {}", brief.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 查询简报（GET /ai-briefs/{taskId}）。
     *
     * @throws BusinessException 30032 任务不存在（404）
     */
    public AIBriefView getBrief(Long taskId) {
        AiBrief brief =
                repository
                        .findById(taskId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.AI_BRIEF_NOT_FOUND));
        brief = applyStaleTimeout(brief);
        return buildView(brief);
    }

    /** 懒查超时：PENDING 且创建超 30min → 强制 FAILED（§4.3 流程 2「超时 30min 自动置失败」兜底）。 */
    private AiBrief applyStaleTimeout(AiBrief brief) {
        if (brief.getStatus() != BriefStatus.PENDING || brief.getCreatedAt() == null) {
            return brief;
        }
        if (Duration.between(brief.getCreatedAt(), Instant.now()).toMinutes()
                < STALE_TIMEOUT_MINUTES) {
            return brief;
        }
        log.warn("AI 简报超时兜底置失败 taskId={} createdAt={}", brief.getId(), brief.getCreatedAt());
        brief.forceFailed();
        return repository.save(brief);
    }

    /** 组装响应视图：完成态附 content/sourceLinks，处理中/失败态为 null；恒附 disclaimer。 */
    private AIBriefView buildView(AiBrief brief) {
        if (!brief.getStatus().isCompleted()) {
            return new AIBriefView(
                    brief.getStatus().code(), null, null, BriefContent.DEFAULT_DISCLAIMER);
        }
        BriefContent content = contentCodec.parse(brief.getContent()).orElse(null);
        List<String> sourceLinks = contentCodec.readSourceLinks(brief.getSourceLinks());
        String disclaimer =
                content == null || content.disclaimer() == null || content.disclaimer().isBlank()
                        ? BriefContent.DEFAULT_DISCLAIMER
                        : content.disclaimer();
        return new AIBriefView(brief.getStatus().code(), content, sourceLinks, disclaimer);
    }

    private static long currentUserId() {
        UserContext.Principal p = UserContext.get();
        return p == null ? 0L : p.userId();
    }

    /** 幂等键 = subjectId + briefType + yyyyMMdd（§4.1.4）。 */
    private static String buildIdempotencyKey(Long subjectId, BriefType briefType) {
        String subject = subjectId == null ? "none" : subjectId.toString();
        return subject + ":" + briefType.code() + ":" + LocalDate.now().format(KEY_DATE);
    }
}
