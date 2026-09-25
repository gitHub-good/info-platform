package com.info.platform.application.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.ai.PlaceholderProvider;
import com.info.platform.application.ai.PromptTemplateService;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.LlmException;
import com.info.platform.domain.ai.LlmGateway;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.domain.ai.PlaceholderDescriptor;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.analysis.IndustryCategory;
import com.info.platform.domain.analysis.NewsAnalysisRepository;
import com.info.platform.domain.common.BusinessException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * L1 批量归类服务（应用层核心，M15 T121，方案 §4.3 / ADR-0046 裁决 2）。
 *
 * <p>批 ≤20 条一次调用：模板渲染（briefType=5，治理页热生效）→ LlmGateway 批量调用（{@code cacheable=false} 绕缓存、temperature
 * 0.1、scene=5 留痕）→ 解析对齐（id 不齐/非法枚举/重复 id 进重试批）→ 低置信兜底（confidence &lt; floor → 市场·其他 + raw_main 留痕）→
 * 条件 UPDATE 落库（幂等）。
 *
 * <p><b>失败处理</b>：整体解析失败 → 对半拆批递归至单条（单条终败记 FAILED）；部分行无效 → 有效行先落库、余量重试； 网络类失败 （LlmException/预算拒绝）→
 * attempts++ 本 tick 放弃该批（下 tick 24h 窗口自然重试——弹性四件套「重试交给下一周期」）。
 *
 * <p>T46（ADR-0022）：实现 {@link PlaceholderProvider} 自述 2 键（batchSize/items）——治理页对照区与保存校验的单一事实源。
 */
@Service
public class ClassificationService implements PlaceholderProvider {

    private static final Logger log = LoggerFactory.getLogger(ClassificationService.class);

    /** 提示词条目行字段上限（title ≤60 / summary ≤80，方案 §4.3 items 契约）。 */
    static final int TITLE_MAX_LENGTH = 60;

    static final int SUMMARY_MAX_LENGTH = 80;

    /** 本服务实际注入的占位符描述符（与 {@code buildContext} 的 ctx.put 同源同序维护，同源单测守护）。 */
    private static final List<PlaceholderDescriptor> CLASSIFY_PLACEHOLDERS =
            List.of(
                    new PlaceholderDescriptor("batchSize", "本批待归类条目数"),
                    new PlaceholderDescriptor(
                            "items", "待归类条目 JSON 行（每条 {id,title,summary,source,companies}）"));

    private final NewsAnalysisRepository repository;
    private final LlmGateway llmGateway;
    private final PromptTemplateService promptTemplateService;
    private final SubjectMatcher subjectMatcher;
    private final PipelineSettings settings;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public ClassificationService(
            NewsAnalysisRepository repository,
            LlmGateway llmGateway,
            PromptTemplateService promptTemplateService,
            SubjectMatcher subjectMatcher,
            PipelineSettings settings,
            Clock clock,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
        this.subjectMatcher = subjectMatcher;
        this.settings = settings;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * 归类一批（入口；解析失败与部分无效内部拆批递归，深度 ≤log2(30)+1）。
     *
     * @param items 待归类条目（≤ l1BatchSize）
     * @return 本批结果计数（含递归子批聚合）
     */
    public BatchOutcome classifyBatch(List<NewsAnalysisRepository.ClassificationCandidate> items) {
        if (items == null || items.isEmpty()) {
            return new BatchOutcome(0, 0);
        }
        Map<Long, NewsAnalysisRepository.ClassificationCandidate> byId = new LinkedHashMap<>();
        for (NewsAnalysisRepository.ClassificationCandidate item : items) {
            byId.put(item.newsId(), item);
        }
        LlmResponse response;
        String promptVersion;
        Map<Long, List<SubjectMatcher.MatchedSubject>> matches = new HashMap<>();
        try {
            PromptTemplate template =
                    promptTemplateService.loadActiveTemplate(BriefType.L1_CLASSIFY);
            promptVersion = template.getVersion();
            for (NewsAnalysisRepository.ClassificationCandidate item : items) {
                matches.put(item.newsId(), subjectMatcher.match(item.title(), item.summary()));
            }
            List<ChatMessage> messages =
                    promptTemplateService.render(template, buildContext(items, matches));
            LlmRequest request =
                    LlmRequest.pipeline(
                            messages,
                            BriefType.L1_CLASSIFY.key(),
                            PipelineSettings.L1_TEMPERATURE,
                            PipelineSettings.L1_MAX_TOKENS);
            response = llmGateway.chat(request);
        } catch (LlmException | BusinessException e) {
            // 网络类失败：attempts++ 本 tick 放弃（下 tick 24h 窗口自然重试，不在同 tick 连环重试）
            List<Long> newsIds = new ArrayList<>(byId.keySet());
            repository.markL1Failed(newsIds);
            log.warn(
                    "L1 批量调用失败（本 tick 放弃 {} 条）: {}",
                    newsIds.size(),
                    String.valueOf(e.getMessage()));
            return new BatchOutcome(0, newsIds.size());
        }
        return applyResponse(new ArrayList<>(items), matches, response, promptVersion);
    }

    /** 解析对齐 + 落库 + 无效余量处理（整体无进展 → 对半拆批递归；单条终败 → FAILED）。 */
    private BatchOutcome applyResponse(
            List<NewsAnalysisRepository.ClassificationCandidate> items,
            Map<Long, List<SubjectMatcher.MatchedSubject>> matches,
            LlmResponse response,
            String promptVersion) {
        List<Row> rows = parseRows(response.content());
        Map<Long, List<Row>> rowsById = groupById(rows);
        int done = 0;
        List<NewsAnalysisRepository.ClassificationCandidate> broken = new ArrayList<>();
        for (NewsAnalysisRepository.ClassificationCandidate item : items) {
            Row valid = singleValidRow(rowsById.get(item.newsId()));
            if (valid == null) {
                broken.add(item); // 缺 id / 重复 id / 非法枚举 / 置信度越界
                continue;
            }
            done += persist(item, valid, matches.get(item.newsId()), promptVersion);
        }
        if (broken.isEmpty()) {
            return new BatchOutcome(done, 0);
        }
        if (broken.size() == items.size()) {
            // 无进展：单条终败记 FAILED；多条对半拆批递归（ADR-0046 裁决 2——拆批即二分定位）
            if (items.size() == 1) {
                repository.markL1Failed(List.of(items.get(0).newsId()));
                log.warn("L1 单条归类终败（attempts+1）: newsId={}", items.get(0).newsId());
                return new BatchOutcome(done, 1);
            }
            int mid = broken.size() / 2;
            return classifyBatch(broken.subList(0, mid))
                    .plus(classifyBatch(broken.subList(mid, broken.size())));
        }
        // 部分进展：余量重试（有效行已落库）
        log.info("L1 批量部分无效（{} 条进重试批）", broken.size());
        return new BatchOutcome(done, 0).plus(classifyBatch(broken));
    }

    /** 低置信兜底 + 条件落库（0 行命中 = 已 DONE，幂等不计数）。 */
    private int persist(
            NewsAnalysisRepository.ClassificationCandidate item,
            Row row,
            List<SubjectMatcher.MatchedSubject> matched,
            String promptVersion) {
        String main = row.main();
        String rawMain = null;
        boolean lowConfidence = false;
        if (row.confidence() < settings.confidenceFloor()) {
            main = IndustryCategory.MARKET_OTHER;
            rawMain = row.main();
            lowConfidence = true;
        }
        String sub = IndustryCategory.isSwIndustry(row.sub()) ? row.sub() : null;
        return repository.applyL1Result(
                new NewsAnalysisRepository.L1Write(
                        item.newsId(),
                        main,
                        rawMain,
                        sub,
                        row.confidence(),
                        lowConfidence,
                        toJson(matched),
                        promptVersion,
                        clock.instant()));
    }

    /** 渲染上下文（batchSize + items JSON 行；与 {@link #provided()} 同源同序）。 */
    private Map<String, String> buildContext(
            List<NewsAnalysisRepository.ClassificationCandidate> items,
            Map<Long, List<SubjectMatcher.MatchedSubject>> matches) {
        StringBuilder lines = new StringBuilder();
        for (NewsAnalysisRepository.ClassificationCandidate item : items) {
            if (!lines.isEmpty()) {
                lines.append('\n');
            }
            lines.append(renderItemLine(item, matches.get(item.newsId())));
        }
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("batchSize", String.valueOf(items.size()));
        ctx.put("items", lines.toString());
        return ctx;
    }

    /** 单条 items 行：{"id":..,"title":≤60,"summary":≤80,"source":..,"companies":["名(行业)"]}。 */
    private String renderItemLine(
            NewsAnalysisRepository.ClassificationCandidate item,
            List<SubjectMatcher.MatchedSubject> matched) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("id", item.newsId());
        line.put("title", truncate(item.title(), TITLE_MAX_LENGTH));
        line.put("summary", truncate(item.summary(), SUMMARY_MAX_LENGTH));
        line.put("source", item.sourceName());
        List<String> companies = new ArrayList<>();
        if (matched != null) {
            for (SubjectMatcher.MatchedSubject subject : matched) {
                companies.add(
                        subject.industry() == null || subject.industry().isBlank()
                                ? subject.name()
                                : subject.name() + "(" + subject.industry() + ")");
            }
        }
        line.put("companies", companies);
        return toJson(line);
    }

    /** 解析模型输出：对象包裹数组 {@code {"results":[..]}} 优先，裸数组与 markdown 围栏容错；不可解析返回空表（整体拆批路径）。 */
    private List<Row> parseRows(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String stripped = stripCodeFence(content).trim();
        JsonNode root;
        try {
            root = objectMapper.readTree(stripped);
        } catch (Exception e) {
            return List.of();
        }
        JsonNode results = root.isArray() ? root : root.get("results");
        if (results == null || !results.isArray()) {
            return List.of();
        }
        List<Row> rows = new ArrayList<>();
        for (JsonNode element : results) {
            if (element == null || !element.isObject()) {
                continue;
            }
            JsonNode id = element.get("id");
            JsonNode main = element.get("main");
            JsonNode sub = element.get("sub");
            JsonNode confidence = element.get("confidence");
            if (id == null || !id.canConvertToLong() || main == null || !main.isTextual()) {
                continue;
            }
            double conf = confidence != null && confidence.isNumber() ? confidence.asDouble() : -1;
            rows.add(
                    new Row(
                            id.asLong(),
                            main.asText(),
                            sub == null ? null : sub.asText(null),
                            conf));
        }
        return rows;
    }

    private static Map<Long, List<Row>> groupById(List<Row> rows) {
        Map<Long, List<Row>> grouped = new HashMap<>();
        for (Row row : rows) {
            grouped.computeIfAbsent(row.id(), key -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    /** 恰一行且字段全合法才有效（重复 id = 矛盾输出不可信，进重试批）。 */
    private static Row singleValidRow(List<Row> rows) {
        if (rows == null || rows.size() != 1) {
            return null;
        }
        Row row = rows.get(0);
        boolean confidenceInRange = row.confidence() >= 0 && row.confidence() <= 1;
        return IndustryCategory.isValid(row.main()) && confidenceInRange ? row : null;
    }

    private static String stripCodeFence(String content) {
        String text = content.trim();
        if (text.startsWith("```")) {
            int firstLineBreak = text.indexOf('\n');
            if (firstLineBreak > 0) {
                text = text.substring(firstLineBreak + 1);
            }
            int closing = text.lastIndexOf("```");
            if (closing >= 0) {
                text = text.substring(0, closing);
            }
        }
        return text;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("L1 JSON 序列化失败（回联留痕置空）: {}", e.getMessage());
            return null;
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** 模型输出行（解析中间态；reason 不落库）。 */
    private record Row(long id, String main, String sub, double confidence) {}

    /** 批结果计数（JobRunStats 段式明细数据面）。 */
    public record BatchOutcome(int done, int failed) {

        public BatchOutcome plus(BatchOutcome other) {
            return new BatchOutcome(done + other.done(), failed + other.failed());
        }
    }

    /** T46：本服务仅服务场景 5（行业归类）。 */
    @Override
    public Set<BriefType> briefTypes() {
        return Set.of(BriefType.L1_CLASSIFY);
    }

    /** T46：注册表读取实际注入清单（与 {@code buildContext} 的 ctx.put 同源，防漂移闸门见同源单测）。 */
    @Override
    public List<PlaceholderDescriptor> provided() {
        return CLASSIFY_PLACEHOLDERS;
    }
}
