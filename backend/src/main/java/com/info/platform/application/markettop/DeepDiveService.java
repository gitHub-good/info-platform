package com.info.platform.application.markettop;

import com.info.platform.application.ai.PlaceholderProvider;
import com.info.platform.application.ai.PromptTemplateService;
import com.info.platform.application.analysis.PipelineGuardService;
import com.info.platform.application.analysis.PipelineSettings;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.LlmException;
import com.info.platform.domain.ai.LlmGateway;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.domain.ai.PlaceholderDescriptor;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.markettop.CitationReconciler;
import com.info.platform.domain.markettop.DeepDiveInput;
import com.info.platform.domain.markettop.DeepDiveInput.EventFact;
import com.info.platform.domain.markettop.DeepDiveInput.FactorDim;
import com.info.platform.domain.markettop.DeepDiveOutcome;
import com.info.platform.domain.markettop.DeepDiveOutputParser;
import com.info.platform.domain.markettop.DeepDiveOutputParser.Parsed;
import com.info.platform.domain.markettop.DeepDivePromptComposer;
import com.info.platform.domain.markettop.DeepDiveTemplateComposer;
import com.info.platform.domain.markettop.DeepDiveTemplateComposer.TemplateFacts;
import com.info.platform.domain.markettop.ProhibitedPhraseScanner;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * LLM 深析服务（应用层，M21 T182，方案 §4.4 + ADR-0059 裁决 3）：单标的单次调用（briefType=10，scene "10"，{@code
 * cacheable=false} 沿管道工厂）→ 五步校验链（解析 → 结构 → 引用对账 → 违禁扫描 → 通过/兜底，首违即止不重试）→ diveScore 之外的全部深析产物 （终态结构
 * + dive_summary + genMethod 留痕）。调用走 Job 线程无认证上下文——llm_call_log userId=0 系统调用（scene 5~8 管道惯例）。
 *
 * <p><b>成本护栏预检</b>（§4.4.7 裁决 4）：每次调用前 {@link #costCapReached()}——scene-10 当日已用 + 保守预估 &gt; capRatio
 * × 管道日预算即拒调（返回 costCapped，调用方停剩余走 factor_only，降级三档之③）。
 *
 * <p>实现 {@link PlaceholderProvider} 自述 9 键（与 {@link DeepDivePromptComposer#placeholders}
 * 同源同序维护，ADR-0022 惯例）。
 */
@Service
public class DeepDiveService implements PlaceholderProvider {

    private static final Logger log = LoggerFactory.getLogger(DeepDiveService.class);

    /** 深析 scene 键（= briefType 10 的 key；llm_call_log 留痕与子预算口径键）。 */
    static final String SCENE_KEY = BriefType.DEEP_DIVE.key();

    /** 深析采样温度（语言组织低发散，沿管道 0.1）。 */
    static final double DIVE_TEMPERATURE = 0.1;

    /**
     * 深析输出上限（BUG-M21-01：1024 实测 35/40 撞顶截断致模板兜底率 90%，上调 4096——thesis+条目 JSON+模型 reasoning token 均计入
     * completion）。
     */
    static final int DIVE_MAX_TOKENS = 4096;

    /** 事件重要度序（模板兜底「最高重要度」口径：HIGH &gt; MEDIUM &gt; LOW &gt; 未知）。 */
    private static final List<String> IMPORTANCE_ORDER = List.of("HIGH", "MEDIUM", "LOW");

    /** 本服务实际注入的占位符描述符（与 DeepDivePromptComposer 同源同序维护，同源单测守护）。 */
    private static final List<PlaceholderDescriptor> DIVE_PLACEHOLDERS =
            List.of(
                    new PlaceholderDescriptor("subject", "标的三元组（代码/名称/申万一级行业）"),
                    new PlaceholderDescriptor("factors", "五维分解数组（key/name/score/weight）"),
                    new PlaceholderDescriptor("totalScore", "因子总分（快照原值）"),
                    new PlaceholderDescriptor("percentile", "全市场百分位（0~100）"),
                    new PlaceholderDescriptor("breakthrough", "突破候选标记（true/false）"),
                    new PlaceholderDescriptor("topEvents", "Top 依据事件（cap 6，含 eventId）"),
                    new PlaceholderDescriptor("relatedNews", "关联资讯（cap 8，含 newsId）"),
                    new PlaceholderDescriptor("industryNews", "行业资讯（cap 3，含 newsId）"),
                    new PlaceholderDescriptor("marketSnapshot", "当日行情快照（缺数键省略）"));

    private final LlmGateway llmGateway;

    private final PromptTemplateService promptTemplateService;

    private final DeepDiveOutputParser outputParser;

    private final PipelineGuardService guardService;

    private final PipelineSettings pipelineSettings;

    private final MarketTopConfigSettings configSettings;

    public DeepDiveService(
            LlmGateway llmGateway,
            PromptTemplateService promptTemplateService,
            DeepDiveOutputParser outputParser,
            PipelineGuardService guardService,
            PipelineSettings pipelineSettings,
            MarketTopConfigSettings configSettings) {
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
        this.outputParser = outputParser;
        this.guardService = guardService;
        this.pipelineSettings = pipelineSettings;
        this.configSettings = configSettings;
    }

    /**
     * 成本护栏预检（§4.4.7 裁决 4）：scene-10 当日已用（llm_call_log SUCCESS 和）+ 单次保守预估 &gt; capRatio × 管道日预算。
     *
     * <p>观测与执行分离（ADR-0015）：严格口径以留痕表为准，预估不写库。
     */
    public boolean costCapReached() {
        MarketTopConfig config = configSettings.current();
        long capMicros =
                Math.round(config.deepDiveCostCapRatio() * pipelineSettings.dailyBudgetMicros());
        long projected =
                guardService.todaySceneCostMicros(SCENE_KEY) + config.diveCostEstimateMicros();
        boolean capped = projected > capMicros;
        if (capped) {
            log.warn(
                    "深析成本护栏触顶（停剩余走 factor_only）: scene10Today={}micros estimate={}micros"
                            + " cap={}micros（capRatio={} budget={}micros）",
                    guardService.todaySceneCostMicros(SCENE_KEY),
                    config.diveCostEstimateMicros(),
                    capMicros,
                    config.deepDiveCostCapRatio(),
                    pipelineSettings.dailyBudgetMicros());
        }
        return capped;
    }

    /**
     * 单标的深析（五步校验链首违即止，任一步失败模板兜底不重试——时效与成本优先）。
     *
     * @param input 深析输入（引用白名单单源）
     * @return 深析结果：outcome 恒产出（触顶除外——costCapped=true 时 outcome=null，调用方按 factor_only）
     */
    public DiveResult analyze(DeepDiveInput input) {
        Objects.requireNonNull(input, "input 必填");
        if (costCapReached()) {
            return DiveResult.capped();
        }
        PromptTemplate template = loadTemplate();
        if (template == null) {
            return templateResult(input, null, 0);
        }
        return callAndValidate(input, template);
    }

    /** LLM 调用 + 校验链（步 1~5）。 */
    private DiveResult callAndValidate(DeepDiveInput input, PromptTemplate template) {
        LlmResponse response;
        try {
            LlmRequest request =
                    LlmRequest.pipeline(
                            promptTemplateService.render(
                                    template, DeepDivePromptComposer.placeholders(input)),
                            SCENE_KEY,
                            DIVE_TEMPERATURE,
                            DIVE_MAX_TOKENS);
            response = llmGateway.chat(request); // 软超时 30s = 网关既有 Future 兜底
        } catch (LlmException | BusinessException e) {
            log.warn(
                    "深析 LLM 调用失败（切模板兜底，不重试）: subject={} reason={}",
                    input.subject().code(),
                    e.getMessage());
            return new DiveResult(templateOutcome(input), true, false, 0, template.getVersion());
        }

        // 步 1+2：解析 + 结构（缺字段/超长/条目数越界 → 兜底）
        Optional<Parsed> parsedOpt = outputParser.parse(response.content());
        if (parsedOpt.isEmpty() || !DeepDiveOutputParser.structurallyValid(parsedOpt.get())) {
            log.warn("深析输出解析或结构校验失败（切模板兜底）: subject={}", input.subject().code());
            return templateResult(input, template.getVersion(), 0);
        }
        Parsed parsed = parsedOpt.get();

        // 步 3：引用对账（零幻觉防线——citations ⊆ 输入白名单）
        CitationReconciler.Result reconciled =
                CitationReconciler.reconcile(parsed, input.citationWhitelist());
        if (reconciled.invalidCitations() > 0) {
            log.warn(
                    "深析引用对账剔除虚构引用: subject={} invalid={} droppedEntries={}",
                    input.subject().code(),
                    reconciled.invalidCitations(),
                    reconciled.droppedEntries());
        }
        if (!reconciled.valid()) {
            return templateResult(input, template.getVersion(), reconciled.invalidCitations());
        }

        // 步 4：违禁扫描（thesis 命中整体兜底；条目命中剔除再判下限）
        ProhibitedPhraseScanner.Result scanned =
                ProhibitedPhraseScanner.scan(reconciled.reconciled());
        if (!scanned.valid()) {
            log.warn("深析违禁扫描被拒（切模板兜底）: subject={} hits={}", input.subject().code(), scanned.hits());
            return templateResult(input, template.getVersion(), reconciled.invalidCitations());
        }

        // 步 5：通过——终态构造（diveScore 在 TopComposer 合成时从本结构派生）
        return new DiveResult(
                DeepDiveOutcome.llm(scanned.cleaned()),
                false,
                false,
                reconciled.invalidCitations(),
                template.getVersion());
    }

    /** 模板加载（缺失 WARN 走模板兜底——V32 已播种，治理页废用是运营态）。 */
    private PromptTemplate loadTemplate() {
        try {
            return promptTemplateService.loadActiveTemplate(BriefType.DEEP_DIVE);
        } catch (BusinessException e) {
            log.warn("深析模板缺失（直接模板兜底）: {}", e.getMessage());
            return null;
        }
    }

    private DiveResult templateResult(
            DeepDiveInput input, String promptVersion, int citationDrops) {
        return new DiveResult(templateOutcome(input), false, false, citationDrops, promptVersion);
    }

    /** 兜底终态（确定性中性文案——数字全部来自结构化输入）。 */
    private static DeepDiveOutcome templateOutcome(DeepDiveInput input) {
        return DeepDiveOutcome.template(DeepDiveTemplateComposer.compose(factsOf(input)));
    }

    /** 输入 → 模板事实（latest/maxImportance 派生自 topEvents——依据事件面，不引入输入外数据）。 */
    private static TemplateFacts factsOf(DeepDiveInput input) {
        return new TemplateFacts(
                input.eventWindowDays(),
                input.totalEventCount(),
                maxImportance(input.topEvents()),
                latestEventDate(input.topEvents()),
                input.subject().industry(),
                input.industryHeatRank(),
                scoreOf(input, "catalyst"),
                scoreOf(input, "conduction"),
                scoreOf(input, "fundamental"),
                scoreOf(input, "risk"),
                weightOf(input, "catalyst"),
                weightOf(input, "conduction"),
                weightOf(input, "fundamental"),
                weightOf(input, "risk"));
    }

    private static String maxImportance(List<EventFact> events) {
        return events.stream()
                .map(EventFact::importance)
                .filter(Objects::nonNull)
                .min(Comparator.comparingInt(DeepDiveService::importanceRank))
                .orElse(null);
    }

    /** 重要度序值（未知词排最后——防御：factor_detail 外来源不抬高级别）。 */
    private static int importanceRank(String importance) {
        int index = IMPORTANCE_ORDER.indexOf(importance);
        return index >= 0 ? index : IMPORTANCE_ORDER.size();
    }

    private static String latestEventDate(List<EventFact> events) {
        return events.stream()
                .map(EventFact::eventDate)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private static double scoreOf(DeepDiveInput input, String key) {
        return input.factors().stream()
                .filter(factor -> factor.key().equals(key))
                .findFirst()
                .map(FactorDim::score)
                .orElse(0.0);
    }

    private static double weightOf(DeepDiveInput input, String key) {
        return input.factors().stream()
                .filter(factor -> factor.key().equals(key))
                .findFirst()
                .map(FactorDim::weight)
                .orElse(0.0);
    }

    /** T182：本服务仅服务场景 10（全市场深析）。 */
    @Override
    public Set<BriefType> briefTypes() {
        return Set.of(BriefType.DEEP_DIVE);
    }

    /** 注册表读取实际注入清单（与 DeepDivePromptComposer 同源，防漂移闸门见同源单测）。 */
    @Override
    public List<PlaceholderDescriptor> provided() {
        return DIVE_PLACEHOLDERS;
    }

    /**
     * 深析结果（genMethod 留痕 + 触顶/调用失败信号）。
     *
     * @param outcome 深析终态（costCapped=true 时为 null——该标的不深析，合成按 factor_only）
     * @param llmCallThrew LLM 调用异常（当前标的已模板兜底；连续 ≥5 次由调用方中止剩余，降级三档之②）
     * @param costCapped 成本护栏触顶（降级三档之③——调用方应停止剩余深析）
     * @param citationDrops 对账剔除的虚构引用计数（JobRunStats detail 留痕）
     * @param promptVersion 深析模板版本（M5 治理留痕；模板缺失/未调用为 null）
     */
    public record DiveResult(
            DeepDiveOutcome outcome,
            boolean llmCallThrew,
            boolean costCapped,
            int citationDrops,
            String promptVersion) {

        /** 触顶拒调结果（outcome=null——合成按 factor_only）。 */
        static DiveResult capped() {
            return new DiveResult(null, false, true, 0, null);
        }
    }
}
