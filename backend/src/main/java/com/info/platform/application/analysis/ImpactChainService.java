package com.info.platform.application.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.analysis.EventItem;
import com.info.platform.domain.analysis.EventItemRepository;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.ImpactCacheState;
import com.info.platform.domain.analysis.ImpactChainRepository;
import com.info.platform.domain.analysis.ImpactChainTemplates;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.analysis.IndustryImpactChain;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 影响链生成服务（应用层，M17 T144，REQ 拍板五-5）：{@link ImpactChainTemplates} 纯规则渲染（无 LLM，护栏降级态与常态同一路径——链路恒活） →
 * 依据回溯 basis JSON → 整事件替换落链（幂等）。生成策略：HIGH 落库自动（{@link EventItemRepository#upsert} 后由 L2 persist
 * 段挂勾，查询侧缺位自愈）；MEDIUM 首次展开按需生成并缓存；LOW 不生成（空态）。
 */
@Service
public class ImpactChainService {

    private static final Logger log = LoggerFactory.getLogger(ImpactChainService.class);

    /** 免责口径（REQ 拍板五-6：影响面描述非操作建议，AI/规则产物统一标注）。 */
    static final String DISCLAIMER = "AI 分析仅供参考";

    private final ImpactChainRepository chainRepository;
    private final EventItemRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ImpactChainService(
            ImpactChainRepository chainRepository,
            EventItemRepository eventRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.chainRepository = chainRepository;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 生成并整事件替换落链（HIGH 自动挂勾与 MEDIUM 按需共用）。
     *
     * @param event 已落库事件（id 必填）
     * @param cacheState AUTO（落库自动）/ ON_DEMAND（按需缓存）
     * @return 生成结果（链行数）
     */
    public GenerationOutcome generateFor(EventItem event, ImpactCacheState cacheState) {
        Objects.requireNonNull(event.getId(), "event.id 必填（落库后回填）");
        List<IndustryImpactChain> chains =
                ImpactChainTemplates.render(event).stream()
                        .map(
                                output ->
                                        IndustryImpactChain.create(
                                                event.getId(),
                                                output.industry(),
                                                output.direction(),
                                                output.logicChain(),
                                                basisJson(event, output),
                                                output.templateKey(),
                                                cacheState,
                                                clock.instant()))
                        .toList();
        chainRepository.replaceForEvent(event.getId(), chains);
        log.info(
                "影响链生成落链: eventId={} type={} state={} chains={}",
                event.getId(),
                event.getEventType(),
                cacheState,
                chains.size());
        return new GenerationOutcome(chains);
    }

    /**
     * 事件影响链查询（事件详情扩展区块数据面）：LOW 空态；有缓存直返；缺缓存按重要度生成（HIGH=AUTO 自愈 / MEDIUM=ON_DEMAND）。
     *
     * @param eventId event_item.id
     */
    public ImpactChainView chainsForEvent(long eventId) {
        EventItem event =
                eventRepository.findByIds(List.of(eventId)).stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.EVENT_NOT_FOUND, "事件不存在: " + eventId));
        if (event.getImportance() == Importance.LOW) {
            return new ImpactChainView(eventId, "LOW", "LOW_SKIPPED", List.of(), DISCLAIMER);
        }
        List<IndustryImpactChain> cached = chainRepository.findByEventId(eventId);
        if (!cached.isEmpty()) {
            return new ImpactChainView(
                    eventId,
                    event.getImportance().name(),
                    "CACHED",
                    toItemViews(cached),
                    DISCLAIMER);
        }
        ImpactCacheState state =
                event.getImportance() == Importance.HIGH
                        ? ImpactCacheState.AUTO
                        : ImpactCacheState.ON_DEMAND;
        GenerationOutcome outcome = generateFor(event, state);
        return new ImpactChainView(
                eventId,
                event.getImportance().name(),
                state.name(),
                toItemViews(outcome.chains()),
                DISCLAIMER);
    }

    /** basis 依据回溯 JSON（信号来源条目 id 集 = 事件原文 news 唯一来源 v1 + 事件结构化字段 + 引用）。 */
    private String basisJson(EventItem event, ImpactChainTemplates.ChainOutput output) {
        var node = objectMapper.createObjectNode();
        node.put("newsId", event.getNewsId());
        node.set("signalNewsIds", objectMapper.valueToTree(List.of(event.getNewsId())));
        node.put("eventType", event.getEventType().name());
        if (event.getEventType() == EventType.POLICY_RELEASE) {
            node.put(
                    "macroKind",
                    ImpactChainTemplates.macroKindOf(event.getSummary(), event.getQuote()).name());
        }
        node.put("importance", event.getImportance().name());
        node.put("direction", event.getDirection().name());
        node.put("quote", event.getQuote());
        node.put("summary", event.getSummary());
        node.put("templateKey", output.templateKey());
        node.set("industries", objectMapper.valueToTree(event.getAffectedIndustries()));
        return node.toString();
    }

    private List<ImpactChainView.ChainItemView> toItemViews(List<IndustryImpactChain> chains) {
        return chains.stream().map(this::toItemView).toList();
    }

    private ImpactChainView.ChainItemView toItemView(IndustryImpactChain chain) {
        return new ImpactChainView.ChainItemView(
                chain.getId() == null ? 0L : chain.getId(),
                chain.getIndustry(),
                chain.getDirection().name(),
                chain.getLogicChain(),
                parseBasis(chain.getBasis()),
                chain.getTemplateKey(),
                chain.getCacheState().name(),
                chain.getGenMethod());
    }

    /** basis 解析容错（损坏行透出空对象——渲染层容错，不阻断区块）。 */
    private JsonNode parseBasis(String basis) {
        try {
            return objectMapper.readTree(basis);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    /** 生成结果（链行清单——视图组装复用，免二次回读）。 */
    public record GenerationOutcome(List<IndustryImpactChain> chains) {}
}
