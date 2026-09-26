package com.info.platform.application.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.ai.PlaceholderProvider;
import com.info.platform.application.ai.PromptTemplateService;
import com.info.platform.application.analysis.PipelineGuardService;
import com.info.platform.application.recommendation.RecommendationAssociationService.AssociationResult;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.LlmException;
import com.info.platform.domain.ai.LlmGateway;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.domain.ai.PlaceholderDescriptor;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.analysis.EventItem;
import com.info.platform.domain.analysis.GuardLevel;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.recommendation.CardGenMethod;
import com.info.platform.domain.recommendation.FactWhitelistValidator;
import com.info.platform.domain.recommendation.FactWhitelistValidator.Result;
import com.info.platform.domain.recommendation.FactWhitelistValidator.Whitelist;
import com.info.platform.domain.recommendation.LogicChainTemplates;
import com.info.platform.domain.recommendation.LogicChainTemplates.TemplateInput;
import com.info.platform.domain.recommendation.RecLevel;
import com.info.platform.domain.recommendation.RecommendationCard;
import com.info.platform.domain.recommendation.RecommendationCardRepository;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 推荐卡片生成服务（应用层，M16 T132，方案 §4.5 / ADR-0051 裁决 3）：AssociationResult → logic_inputs 快照组装 →
 * LLM 语言组织（briefType=8，scene "8"，{@code cacheable=false} 绕缓存，temperature 0.1 / maxTokens 512，软超时 =
 * LlmGateway 既有 30s Future 兜底）→ {@link FactWhitelistValidator} 四类白名单校验（任一违规拒 →
 * {@link LogicChainTemplates} 模板拼接兜底，<b>不重试 LLM</b>——时效优先）→ gen_method 留痕（LLM/TEMPLATE）→
 * INSERT OR IGNORE 幂等落卡（pushStatus 起 PENDING，推送闸门 T133 迁移）。
 *
 * <p><b>降级联动</b>：护栏非 NORMAL（DEGRADED/FUSED）直接模板分支（不调 LLM，成本红线联动，REQ 故事 6 场景 4）。
 *
 * <p>实现 {@link PlaceholderProvider} 自述 7 键（level/eventTypeLabel/directionLabel/summary/industries/subjects/
 * watchSubjects，与 {@link #buildContext} ctx.put 同源同序维护，ADR-0022 惯例）。
 */
@Service
public class RecommendationCardService implements PlaceholderProvider {

    private static final Logger log = LoggerFactory.getLogger(RecommendationCardService.class);

    /** 卡片 LLM 采样温度（语言组织低发散，沿管道 0.1）。 */
    static final double CARD_TEMPERATURE = 0.1;

    /** 卡片 LLM 输出上限（一句话逻辑链 + JSON 包裹，512 充裕）。 */
    static final int CARD_MAX_TOKENS = 512;

    /** 本服务实际注入的占位符描述符（与 buildContext 的 ctx.put 同源同序维护，同源单测守护）。 */
    private static final List<PlaceholderDescriptor> CARD_PLACEHOLDERS =
            List.of(
                    new PlaceholderDescriptor("level", "主关联层级（P1=标的直接 / P2=行业 / P3=订阅）"),
                    new PlaceholderDescriptor("eventTypeLabel", "事件类型中文名（9 枚举）"),
                    new PlaceholderDescriptor("directionLabel", "事件方向词（利好/利空/中性）"),
                    new PlaceholderDescriptor("summary", "事件摘要（结构化事实）"),
                    new PlaceholderDescriptor("industries", "事件影响行业（申万枚举、顿号连接）"),
                    new PlaceholderDescriptor("subjects", "事件涉及标的（event.subjects 名单）"),
                    new PlaceholderDescriptor("watchSubjects", "用户关注且命中的标的（标的区名单）"));

    private final RecommendationCardRepository cardRepository;

    private final LlmGateway llmGateway;

    private final PromptTemplateService promptTemplateService;

    private final PipelineGuardService guardService;

    private final SubjectRepository subjectRepository;

    private final ObjectMapper objectMapper;

    public RecommendationCardService(
            RecommendationCardRepository cardRepository,
            LlmGateway llmGateway,
            PromptTemplateService promptTemplateService,
            PipelineGuardService guardService,
            SubjectRepository subjectRepository,
            ObjectMapper objectMapper) {
        this.cardRepository = cardRepository;
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
        this.guardService = guardService;
        this.subjectRepository = subjectRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成一张卡片（幂等：同用户同事件重复消费由 UNIQUE(user_id, event_id) 收敛，{@code inserted=false} 直返）。
     *
     * @param userId 归属用户（行级权限键）
     * @param event 结构化事件（L2 产物）
     * @param newsTitle 原文标题（数字白名单来源 + P3 主题命中面；可空）
     * @param association 三级关联结果
     * @return 生成结果（是否实插 + 生成方法留痕）
     */
    public GenerationOutcome generate(
            long userId, EventItem event, String newsTitle, AssociationResult association) {
        Objects.requireNonNull(event.getId(), "event.id 必填（FEED 消费的事件已落库回填 id）");
        Whitelist whitelist = buildWhitelist(event, newsTitle, association);
        LlmOutcome llm = callLlm(event, newsTitle, association, whitelist);
        String logicChain = llm.valid() ? llm.logicChain() : renderTemplate(event, association);
        CardGenMethod genMethod = llm.valid() ? CardGenMethod.LLM : CardGenMethod.TEMPLATE;
        RecommendationCard card =
                RecommendationCard.create(
                        userId,
                        event.getId(),
                        event.getNewsId(),
                        event.getEventType().name(),
                        event.getImportance().name(),
                        event.getDirection().name(),
                        association.level(),
                        association.industries(),
                        association.subjects(),
                        logicChain,
                        snapshotInputs(event, newsTitle, association, whitelist),
                        genMethod,
                        llm.valid() ? llm.promptVersion() : null,
                        association.recscore(),
                        association.basis(),
                        association.comboKey());
        int inserted = cardRepository.insertIgnore(card);
        if (inserted == 0) {
            log.debug("建卡幂等冲突（重复消费直返）: userId={} eventId={}", userId, event.getId());
        }
        return new GenerationOutcome(inserted == 1, genMethod);
    }

    /** LLM 分支：降级态直接跳过；模板缺失/调用失败/解析失败/白名单被拒 → 无效（走模板兜底）。 */
    private LlmOutcome callLlm(
            EventItem event,
            String newsTitle,
            AssociationResult association,
            Whitelist whitelist) {
        if (guardService.currentLevel() != GuardLevel.NORMAL) {
            return LlmOutcome.invalid(); // 降级态不调 LLM（成本红线联动）
        }
        PromptTemplate template;
        try {
            template = promptTemplateService.loadActiveTemplate(BriefType.RECOMMEND_CARD);
        } catch (BusinessException e) {
            log.warn("推荐卡片模板缺失（走模板拼接兜底）: {}", e.getMessage());
            return LlmOutcome.invalid();
        }
        LlmResponse response;
        try {
            LlmRequest request =
                    LlmRequest.pipeline(
                            promptTemplateService.render(
                                    template, buildContext(event, newsTitle, association)),
                            BriefType.RECOMMEND_CARD.key(),
                            CARD_TEMPERATURE,
                            CARD_MAX_TOKENS);
            response = llmGateway.chat(request); // 软超时 30s = 网关既有 Future 兜底（超时切 fallback，耗尽抛 LlmException）
        } catch (LlmException | BusinessException e) {
            log.warn("推荐卡片 LLM 调用失败（切模板兜底，不重试）: {}", e.getMessage());
            return LlmOutcome.invalid();
        }
        String logicChain = parseLogicChain(response.content());
        if (logicChain == null) {
            log.warn("推荐卡片 LLM 输出不可解析（切模板兜底）: eventId={}", event.getId());
            return LlmOutcome.invalid();
        }
        Result result =
                FactWhitelistValidator.validate(
                        logicChain, whitelist, subjectRepository.findAllNames());
        if (!result.valid()) {
            log.warn(
                    "推荐卡片白名单被拒（切模板兜底，不重试）: eventId={} reason={}",
                    event.getId(),
                    result.reason());
            return LlmOutcome.invalid();
        }
        return new LlmOutcome(true, logicChain, template.getVersion());
    }

    /** 四类允许集组装（① 标的名 = 标的区 ∪ event.subjects；② 行业 = event.affected ∪ 关联命中；③ 数字 = figures ∪
     * quote/title/summary；④ 方向 = event.direction）。 */
    private static Whitelist buildWhitelist(
            EventItem event, String newsTitle, AssociationResult association) {
        Set<String> subjectNames = new LinkedHashSet<>();
        for (RecommendationCard.CardSubject subject : association.subjects()) {
            if (subject.name() != null && !subject.name().isBlank()) {
                subjectNames.add(subject.name());
            }
        }
        for (EventItem.SubjectRef subject : event.getSubjects()) {
            if (subject.name() != null && !subject.name().isBlank()) {
                subjectNames.add(subject.name());
            }
        }
        Set<String> industries = new LinkedHashSet<>(event.getAffectedIndustries());
        industries.addAll(association.industries());
        Set<java.math.BigDecimal> numbers = new LinkedHashSet<>();
        for (EventItem.KeyFigure figure : event.getKeyFigures()) {
            numbers.addAll(FactWhitelistValidator.numbersIn(figure.value()));
        }
        numbers.addAll(
                FactWhitelistValidator.numbersIn(
                        event.getSummary(), newsTitle, event.getQuote()));
        return new Whitelist(subjectNames, industries, numbers, event.getDirection());
    }

    /** 模板拼接分支（REQ 拍板二三形态原文，≤1s 零 LLM）。 */
    private static String renderTemplate(EventItem event, AssociationResult association) {
        return LogicChainTemplates.render(
                new TemplateInput(
                        association.level(),
                        event.getSummary(),
                        event.getDirection(),
                        association.industries(),
                        association.subjects(),
                        association.matchedTheme(),
                        event.getEventType().displayName()));
    }

    /** 渲染上下文（7 占位符；与 {@link #provided()} 同源同序）。 */
    private static Map<String, String> buildContext(
            EventItem event, String newsTitle, AssociationResult association) {
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("level", levelLabel(association.level()));
        ctx.put("eventTypeLabel", event.getEventType().displayName());
        ctx.put("directionLabel", event.getDirection().displayName());
        ctx.put("summary", nullToEmpty(event.getSummary()));
        ctx.put("industries", String.join("、", event.getAffectedIndustries()));
        ctx.put("subjects", joinSubjectNames(event.getSubjects()));
        ctx.put("watchSubjects", joinCardSubjects(association.subjects()));
        return ctx;
    }

    /** logic_inputs 快照 JSON（结构化事实 + 四类允许集，抽检对账与复现面；序列化失败回落 null 不阻断）。 */
    private String snapshotInputs(
            EventItem event,
            String newsTitle,
            AssociationResult association,
            Whitelist whitelist) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("level", association.level().name());
        inputs.put("eventType", event.getEventType().name());
        inputs.put("importance", event.getImportance().name());
        inputs.put("direction", event.getDirection().name());
        inputs.put("summary", event.getSummary());
        inputs.put("title", newsTitle);
        inputs.put("quote", event.getQuote());
        inputs.put("industries", event.getAffectedIndustries());
        inputs.put("keyFigures", event.getKeyFigures());
        inputs.put("subjects", event.getSubjects());
        inputs.put("cardSubjects", association.subjects());
        inputs.put("allowedSubjects", whitelist.allowedSubjectNames());
        inputs.put("allowedIndustries", whitelist.allowedIndustries());
        inputs.put("allowedNumbers", whitelist.allowedNumbers());
        inputs.put("matchedTheme", association.matchedTheme());
        inputs.put("comboKey", association.comboKey());
        inputs.put("recscore", association.recscore());
        inputs.put("basis", association.basis());
        try {
            return objectMapper.writeValueAsString(inputs);
        } catch (Exception e) {
            log.warn("logic_inputs 快照序列化失败（回落 null，不阻断建卡）: {}", e.getMessage());
            return null;
        }
    }

    /** 解析 {@code {"logicChain":"..."}}（围栏容错；不可解析返回 null）。 */
    private String parseLogicChain(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String stripped = content.trim();
        if (stripped.startsWith("```")) {
            int firstLineBreak = stripped.indexOf('\n');
            if (firstLineBreak > 0) {
                stripped = stripped.substring(firstLineBreak + 1);
            }
            int closing = stripped.lastIndexOf("```");
            if (closing >= 0) {
                stripped = stripped.substring(0, closing);
            }
        }
        try {
            JsonNode root = objectMapper.readTree(stripped.trim());
            JsonNode chain = root.get("logicChain");
            return chain != null && chain.isTextual() && !chain.asText().isBlank()
                    ? chain.asText()
                    : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String levelLabel(RecLevel level) {
        return switch (level) {
            case P1 -> "P1（标的直接）";
            case P2 -> "P2（行业）";
            case P3 -> "P3（订阅）";
        };
    }

    private static String joinSubjectNames(List<EventItem.SubjectRef> subjects) {
        StringBuilder names = new StringBuilder();
        for (EventItem.SubjectRef subject : subjects) {
            if (subject.name() == null || subject.name().isBlank()) {
                continue;
            }
            if (!names.isEmpty()) {
                names.append('、');
            }
            names.append(subject.name());
        }
        return names.toString();
    }

    private static String joinCardSubjects(List<RecommendationCard.CardSubject> subjects) {
        StringBuilder names = new StringBuilder();
        for (RecommendationCard.CardSubject subject : subjects) {
            if (subject.name() == null || subject.name().isBlank()) {
                continue;
            }
            if (!names.isEmpty()) {
                names.append('、');
            }
            names.append(subject.name());
        }
        return names.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** T132：本服务仅服务场景 8（推荐卡片逻辑链）。 */
    @Override
    public Set<BriefType> briefTypes() {
        return Set.of(BriefType.RECOMMEND_CARD);
    }

    /** T132：注册表读取实际注入清单（与 buildContext 的 ctx.put 同源，防漂移闸门见同源单测）。 */
    @Override
    public List<PlaceholderDescriptor> provided() {
        return CARD_PLACEHOLDERS;
    }

    /** 生成结果（inserted=false = 幂等冲突重复消费；genMethod 留痕供 JobRunStats llm/tpl 计数）。 */
    public record GenerationOutcome(boolean inserted, CardGenMethod genMethod) {}

    /** LLM 分支内部结果（valid=false 一律走模板兜底）。 */
    private record LlmOutcome(boolean valid, String logicChain, String promptVersion) {

        static LlmOutcome invalid() {
            return new LlmOutcome(false, null, null);
        }
    }
}
