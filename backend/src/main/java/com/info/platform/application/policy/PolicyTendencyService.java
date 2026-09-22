package com.info.platform.application.policy;

import com.info.platform.application.ai.PromptTemplateService;
import com.info.platform.domain.ai.BriefContent;
import com.info.platform.domain.ai.BriefContentCodec;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.LlmException;
import com.info.platform.domain.ai.LlmGateway;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.policy.AiTendency;
import com.info.platform.domain.policy.PolicyItem;
import com.info.platform.domain.policy.PolicyRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 政策倾向 AI 判断服务（应用层，T28，对齐技术方案 §4.1.5 + §4.3 流程 2 + Spike-2 §7.3/§11.6）。
 *
 * <p>对单条政策调 T19 {@link LlmGateway}（briefType=3 政策解读模板）→ 解析 {@link BriefContent#bias()} （利好/利空/中性）→
 * {@link AiTendency#fromBias} 转枚举 → {@link PolicyRepository#updateAiTendency} 填 {@code
 * policy_item.ai_tendency}。T24 GET /policies/{id} 据此返回倾向，无需新接口。
 *
 * <p><b>降级不阻断</b>（对齐 §5 降级预案）：LLM 失败（{@link LlmException}，所有 provider 超时/限频/错误）、成本上限 （{@link
 * BusinessException} 30030）、解析失败、bias 无法识别 → {@code ai_tendency} 保持 0 未判，记 WARN 不抛
 * （政策流仍可读，倾向缺省「未判」）。成本与缓存复用 T19：{@link LlmGateway#chat} 内部已含 LlmCostGuard 预检 + LlmCache 命中 （系统定时任务
 * userId=0，成本守卫跳过；同 prompt+context 命中缓存 0 调用）。
 *
 * <p>上下文装配（对齐 Spike-2 §7.3 模板占位符）：policyTitle/publishedAt/source/policySummary/policyUrl/
 * relatedIndustries（逗号连接）/watchlistSubjects（系统批量判断时无关联用户自选，置占位说明）。nullable 字段（summary/
 * sourceUrl）置占位而非 null——避免模板保留 {@code {{key}}} 误导模型。
 */
@Service
public class PolicyTendencyService {

    private static final Logger log = LoggerFactory.getLogger(PolicyTendencyService.class);

    /** 系统批量判断时 watchlistSubjects 占位（无关联用户自选池）。 */
    static final String NO_WATCHLIST_SUBJECTS = "（系统批量判断，暂无关联自选标的）";

    private final PolicyRepository policyRepository;
    private final LlmGateway llmGateway;
    private final PromptTemplateService promptTemplateService;
    private final BriefContentCodec contentCodec;

    public PolicyTendencyService(
            PolicyRepository policyRepository,
            LlmGateway llmGateway,
            PromptTemplateService promptTemplateService,
            BriefContentCodec contentCodec) {
        this.policyRepository = policyRepository;
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
        this.contentCodec = contentCodec;
    }

    /**
     * 判断单条政策倾向并落库。
     *
     * @param policyItem 政策条目（须有 id）
     * @return 判断结果（利好/利空/中性）；失败/跳过返回 {@link AiTendency#UNJUDGED}
     */
    public AiTendency judgeTendency(PolicyItem policyItem) {
        Objects.requireNonNull(policyItem, "policyItem 必填");
        if (policyItem.getId() == null) {
            log.warn("政策倾向判断：政策条目缺 id，跳过 title={}", policyItem.getTitle());
            return AiTendency.UNJUDGED;
        }
        try {
            return judgeAndPersist(policyItem);
        } catch (LlmException e) {
            log.warn(
                    "政策倾向判断 LLM 失败 policyId={} providers={}",
                    policyItem.getId(),
                    e.attemptedProviders());
            return AiTendency.UNJUDGED;
        } catch (BusinessException e) {
            log.warn(
                    "政策倾向判断业务异常 policyId={} code={}: {}",
                    policyItem.getId(),
                    e.getErrorCode().getCode(),
                    e.getMessage());
            return AiTendency.UNJUDGED;
        }
    }

    /** 生成编排：模板 → 上下文 → LlmGateway → 解析 → bias→AiTendency → 落库。失败上抛由 {@link #judgeTendency} 兜底。 */
    private AiTendency judgeAndPersist(PolicyItem policyItem) {
        PromptTemplate template = promptTemplateService.loadActiveTemplate(BriefType.POLICY);
        Map<String, String> context = buildContext(policyItem);
        List<ChatMessage> messages = promptTemplateService.render(template, context);
        LlmRequest req = LlmRequest.json(messages, BriefType.POLICY.key());
        LlmResponse resp = llmGateway.chat(req);
        Optional<BriefContent> parsed = contentCodec.parse(resp.content());
        if (parsed.isEmpty()) {
            log.warn("政策倾向判断解析失败 policyId={} provider={}", policyItem.getId(), resp.provider());
            return AiTendency.UNJUDGED;
        }
        AiTendency tendency = AiTendency.fromBias(parsed.get().bias());
        if (tendency == AiTendency.UNJUDGED) {
            log.warn(
                    "政策倾向判断无法识别 bias policyId={} bias={}", policyItem.getId(), parsed.get().bias());
            return AiTendency.UNJUDGED;
        }
        boolean updated = policyRepository.updateAiTendency(policyItem.getId(), tendency);
        if (!updated) {
            log.warn("政策倾向落库未命中 policyId={}（条目可能已删）", policyItem.getId());
        }
        log.info(
                "政策倾向判断完成 policyId={} tendency={} provider={}",
                policyItem.getId(),
                tendency,
                resp.provider());
        return tendency;
    }

    /** 装配政策解读模板上下文（占位符 {@code {{key}}}，对齐 Spike-2 §7.3）。 */
    private static Map<String, String> buildContext(PolicyItem item) {
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("policyTitle", orDefault(item.getTitle(), "（无标题）"));
        ctx.put(
                "publishedAt",
                item.getPublishedAt() == null ? "未知" : item.getPublishedAt().toString());
        ctx.put("source", orDefault(item.getSource(), "未知来源"));
        ctx.put("policySummary", orDefault(item.getSummary(), "（暂无摘要）"));
        ctx.put("policyUrl", orDefault(item.getSourceUrl(), "（无链接）"));
        List<String> industries = item.getRelatedIndustries();
        ctx.put(
                "relatedIndustries",
                industries == null || industries.isEmpty() ? "未标注" : String.join("、", industries));
        ctx.put("watchlistSubjects", NO_WATCHLIST_SUBJECTS);
        return ctx;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
