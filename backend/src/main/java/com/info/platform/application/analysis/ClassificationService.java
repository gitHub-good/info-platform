package com.info.platform.application.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.ai.PlaceholderProvider;
import com.info.platform.application.ai.PromptTemplateService;
import com.info.platform.domain.aggregation.Market;
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
import java.text.Collator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * L1 批量归类服务（应用层核心，M15 T121，方案 §4.3 / ADR-0046 裁决 2；M29 T253 升 v2.0 按市场注入枚举集）。
 *
 * <p>批 ≤20 条一次调用：模板渲染（briefType=5 v2.0，治理页热生效）→ <b>按条目市场分组</b>（{@code l1_market} 由 matched_subjects
 * 主市场派生，无标的 → A_SHARE 容器面，方案 §4 C9）→ 逐组渲染（prompt 注入该市场枚举集 {@code {{industryEnums}}}，调用次数与 prompt
 * 长度近零增量——A 股为主的批通常单组）→ LlmGateway 批量调用（{@code cacheable=false} 绕缓存、temperature 0.1、scene=5 留痕）→
 * 解析对齐 → 低置信/港美枚举外值兜底（「市场·其他」+ raw_main + low_confidence 留痕）→ 条件 UPDATE 落库（含 {@code l1_market}，幂等）。
 *
 * <p><b>市场口径校验（M29）</b>：A 股批 = 既有 35 枚举白名单（非法枚举进重试批，行为零回归）；港/美股批 = 各自枚举集 ∪ UNKNOWN ∪ 容器
 * 4（容器跨市场共用恒注入）——<b>枚举外值不重试直接兜底</b>「市场·其他」+ low_confidence 留痕（40 枚举邻近词误配面 大于 A 股，重试收益低，方案 T253
 * 任务注记）。
 *
 * <p><b>失败处理</b>：整体解析失败 → 对半拆批递归至单条（单条终败记 FAILED）；部分行无效 → 有效行先落库、余量重试； 网络类失败 （LlmException/预算拒绝）→
 * attempts++ 本 tick 放弃该批（下 tick 24h 窗口自然重试——弹性四件套「重试交给下一周期」）。
 *
 * <p>T46（ADR-0022）：实现 {@link PlaceholderProvider} 自述 4
 * 键（batchSize/items/marketLabel/industryEnums）——治理页对照区 与保存校验的单一事实源。
 */
@Service
public class ClassificationService implements PlaceholderProvider {

    private static final Logger log = LoggerFactory.getLogger(ClassificationService.class);

    /** 提示词条目行字段上限（title ≤60 / summary ≤80，方案 §4.3 items 契约）。 */
    static final int TITLE_MAX_LENGTH = 60;

    static final int SUMMARY_MAX_LENGTH = 80;

    /**
     * A 股申万 31 枚举注入序（V23 模板 v1.0 同序冻结——「A 股条目枚举集零变化」，方案 §4 C9；与 {@code
     * IndustryCategory.SW_INDUSTRIES} 集合一致性由单测守护防漂移）。package-private 供同包 L2 注入复用（T254）。
     */
    static final List<String> SW_ENUM_ORDER =
            List.of(
                    "农林牧渔", "基础化工", "钢铁", "有色金属", "电子", "家用电器", "食品饮料", "纺织服饰", "轻工制造", "医药生物",
                    "公用事业", "交通运输", "房地产", "商贸零售", "社会服务", "银行", "非银金融", "综合", "建筑材料", "建筑装饰",
                    "电力设备", "机械设备", "国防军工", "计算机", "传媒", "通信", "煤炭", "石油石化", "环保", "美容护理", "汽车");

    /** 港美股枚举中文排序器（注入序确定性——Set 无序，Collator CHINA 固定输出序；package-private 供同包 L2 复用）。 */
    static final CollatorZH COLLATOR_ZH = new CollatorZH();

    /** 本服务实际注入的占位符描述符（与 {@code buildContext} 的 ctx.put 同源同序维护，同源单测守护）。 */
    private static final List<PlaceholderDescriptor> CLASSIFY_PLACEHOLDERS =
            List.of(
                    new PlaceholderDescriptor("batchSize", "本组待归类条目数"),
                    new PlaceholderDescriptor(
                            "items", "待归类条目 JSON 行（每条 {id,title,summary,source,companies}）"),
                    new PlaceholderDescriptor(
                            "marketLabel", "本组条目市场口径（A股/港股/美股——按关联标的 主市场派生，无标的=A股容器面）"),
                    new PlaceholderDescriptor(
                            "industryEnums",
                            "主分类枚举清单（按市场注入：A股=申万31 / 港股31+UNKNOWN / 美股40+UNKNOWN；容器4项模板正文恒注入）"));

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
     * 归类一批（入口；先按市场分组再逐组调用——组内解析失败与部分无效拆批递归，深度 ≤log2(30)+1）。
     *
     * @param items 待归类条目（≤ l1BatchSize）
     * @return 本批结果计数（含分组与递归子批聚合）
     */
    public BatchOutcome classifyBatch(List<NewsAnalysisRepository.ClassificationCandidate> items) {
        if (items == null || items.isEmpty()) {
            return new BatchOutcome(0, 0);
        }
        Map<Long, List<SubjectMatcher.MatchedSubject>> matches = new HashMap<>();
        for (NewsAnalysisRepository.ClassificationCandidate item : items) {
            matches.put(item.newsId(), subjectMatcher.match(item.title(), item.summary()));
        }
        Map<Market, List<NewsAnalysisRepository.ClassificationCandidate>> groups =
                new EnumMap<>(Market.class);
        for (NewsAnalysisRepository.ClassificationCandidate item : items) {
            groups.computeIfAbsent(
                            deriveMarket(matches.get(item.newsId())), key -> new ArrayList<>())
                    .add(item);
        }
        BatchOutcome total = new BatchOutcome(0, 0);
        for (Map.Entry<Market, List<NewsAnalysisRepository.ClassificationCandidate>> group :
                groups.entrySet()) {
            total = total.plus(classifyMarketBatch(group.getKey(), group.getValue(), matches));
        }
        return total;
    }

    /** 单市场组调用（同 v1.0 单批语义 + 市场枚举注入与市场口径校验）。 */
    private BatchOutcome classifyMarketBatch(
            Market market,
            List<NewsAnalysisRepository.ClassificationCandidate> items,
            Map<Long, List<SubjectMatcher.MatchedSubject>> matches) {
        Map<Long, NewsAnalysisRepository.ClassificationCandidate> byId = new LinkedHashMap<>();
        for (NewsAnalysisRepository.ClassificationCandidate item : items) {
            byId.put(item.newsId(), item);
        }
        LlmResponse response;
        String promptVersion;
        try {
            PromptTemplate template =
                    promptTemplateService.loadActiveTemplate(BriefType.L1_CLASSIFY);
            promptVersion = template.getVersion();
            List<ChatMessage> messages =
                    promptTemplateService.render(template, buildContext(market, items, matches));
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
                    "L1 批量调用失败（本 tick 放弃 {} 条）market={}: {}",
                    newsIds.size(),
                    market,
                    String.valueOf(e.getMessage()));
            return new BatchOutcome(0, newsIds.size());
        }
        return applyResponse(market, new ArrayList<>(items), matches, response, promptVersion);
    }

    /** 解析对齐 + 落库 + 无效余量处理（整体无进展 → 对半拆批递归；单条终败 → FAILED）。 */
    private BatchOutcome applyResponse(
            Market market,
            List<NewsAnalysisRepository.ClassificationCandidate> items,
            Map<Long, List<SubjectMatcher.MatchedSubject>> matches,
            LlmResponse response,
            String promptVersion) {
        List<Row> rows = parseRows(response.content());
        Map<Long, List<Row>> rowsById = groupById(rows);
        int done = 0;
        List<NewsAnalysisRepository.ClassificationCandidate> broken = new ArrayList<>();
        for (NewsAnalysisRepository.ClassificationCandidate item : items) {
            Row valid = singleValidRow(market, rowsById.get(item.newsId()));
            if (valid == null) {
                broken.add(item); // 缺 id / 重复 id / 置信度越界 / A股非法枚举
                continue;
            }
            done += persist(market, item, valid, matches.get(item.newsId()), promptVersion);
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

    /**
     * 低置信/枚举外兜底 + 条件落库（0 行命中 = 已 DONE，幂等不计数）。
     *
     * <p>兜底两路同款（方案 §4.3 + T253 注记）：① confidence &lt; floor；② 港美股批模型输出枚举外值（A股批非法枚举在 {@link
     * #singleValidRow} 已拦进重试，保持既有行为）——均改写「市场·其他」+ raw_main 留痕 + low_confidence=1。
     */
    private int persist(
            Market market,
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
        } else if (!isValidMain(market, row.main())) {
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
                        market.name(),
                        clock.instant()));
    }

    /** 渲染上下文（batchSize + items JSON 行 + 市场口径标注 + 按市场枚举集；与 {@link #provided()} 同源同序）。 */
    private Map<String, String> buildContext(
            Market market,
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
        ctx.put("marketLabel", marketLabelOf(market));
        ctx.put("industryEnums", industryEnumsOf(market));
        return ctx;
    }

    /**
     * 单条 items 行：{"id":..,"title":≤60,"summary":≤80,"source":..,"companies":["名(行业)"]}（行业 UNKNOWN
     * 不附注）。
     */
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
                String industry = subject.industry();
                companies.add(
                        industry == null
                                        || industry.isBlank()
                                        || IndustryCategory.UNKNOWN_INDUSTRY.equals(industry)
                                ? subject.name()
                                : subject.name() + "(" + industry + ")");
            }
        }
        line.put("companies", companies);
        return toJson(line);
    }

    // ---- M29 T253：市场派生 / 枚举注入 / 市场口径校验（package-private 供单测直证） ----

    /**
     * 条目市场派生（方案 §4 C9）：matched_subjects 主市场（多数票；平票按 EnumMap 自然序先到先得 = A_SHARE &gt; HK &gt; US
     * 固定优先序，无标的 → A_SHARE 容器面）——确定性纯函数。
     */
    static Market deriveMarket(List<SubjectMatcher.MatchedSubject> matched) {
        if (matched == null || matched.isEmpty()) {
            return Market.A_SHARE;
        }
        Map<Market, Integer> counts = new EnumMap<>(Market.class);
        for (SubjectMatcher.MatchedSubject subject : matched) {
            if (subject.market() != null) {
                counts.merge(subject.market(), 1, Integer::sum);
            }
        }
        Market best = Market.A_SHARE;
        int bestCount = 0;
        for (Map.Entry<Market, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    /** 市场口径人读标注（用户段 {@code {{marketLabel}}} 注入值；package-private 供同包 L2 注入复用）。 */
    static String marketLabelOf(Market market) {
        return switch (market) {
            case HK -> "港股";
            case US -> "美股";
            default -> "A股";
        };
    }

    /**
     * 主分类枚举清单注入（V38 模板 v2.0 {@code {{industryEnums}}} 值）：A 股 = v1.0 同序申万 31（枚举集零变化）；港/美股 = 各自
     * 枚举集（Collator 中文序，确定性）+ UNKNOWN 兜底位；容器 4 项在模板正文恒注入（方案 §3.2）。
     */
    static String industryEnumsOf(Market market) {
        if (market == Market.HK) {
            return "港股行业枚举（"
                    + IndustryCategory.hkSize()
                    + "+1 个，东财 F10 口径）："
                    + String.join("、", COLLATOR_ZH.sorted(IndustryCategory.HK_INDUSTRIES))
                    + "、UNKNOWN（行业未知兜底）";
        }
        if (market == Market.US) {
            return "美股行业枚举（"
                    + IndustryCategory.usSize()
                    + "+1 个，东财 F10 归并大类）："
                    + String.join("、", COLLATOR_ZH.sorted(IndustryCategory.US_INDUSTRIES))
                    + "、UNKNOWN（行业未知兜底）";
        }
        return "申万一级行业 31 个：" + String.join("、", SW_ENUM_ORDER);
    }

    /** L1 主分类白名单（市场口径）：A 股 = 35 枚举既有集；港/美股 = 各自枚举 ∪ UNKNOWN ∪ 容器 4（跨市场共用恒注入）。 */
    static boolean isValidMain(Market market, String name) {
        if (market == Market.HK || market == Market.US) {
            return name != null
                    && (IndustryCategory.isValid(market, name)
                            || IndustryCategory.CONTAINERS.contains(name));
        }
        return IndustryCategory.isValid(name);
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

    /**
     * 恰一行且结构合法才有效（重复 id = 矛盾输出不可信，进重试批）。
     *
     * <p>市场口径（M29）：A 股批保留 v1.0 非法枚举进重试语义（零回归）；港/美股批<b>不在本处拦枚举外值</b>——由 {@link #persist} 兜底「市场·其他」+
     * low_confidence 留痕（不消耗重试预算）。
     */
    private static Row singleValidRow(Market market, List<Row> rows) {
        if (rows == null || rows.size() != 1) {
            return null;
        }
        Row row = rows.get(0);
        boolean confidenceInRange = row.confidence() >= 0 && row.confidence() <= 1;
        if (!confidenceInRange) {
            return null;
        }
        if (market != Market.HK && market != Market.US && !IndustryCategory.isValid(row.main())) {
            return null;
        }
        return row;
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

    /** 回联留痕 JSON（持久化形态保持 {"code","name","industry"} 三键——market 为派生中间量不落列）。 */
    private String toJson(List<SubjectMatcher.MatchedSubject> matched) {
        if (matched == null) {
            return null;
        }
        List<Map<String, String>> view = new ArrayList<>(matched.size());
        for (SubjectMatcher.MatchedSubject subject : matched) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("code", subject.code());
            entry.put("name", subject.name());
            entry.put("industry", subject.industry());
            view.add(entry);
        }
        return toJson((Object) view);
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

    /** 港美股枚举中文排序（Collator CHINA 固定序——Set 无序转确定性注入序；package-private 供同包 L2 复用）。 */
    static final class CollatorZH {

        private final Collator collator = Collator.getInstance(Locale.CHINA);

        List<String> sorted(Set<String> values) {
            List<String> sorted = new ArrayList<>(values);
            sorted.sort(collator);
            return sorted;
        }
    }
}
