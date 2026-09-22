package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.infrastructure.common.CircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.ResilienceSpec;
import com.info.platform.infrastructure.common.SourceCache;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 政策源真实 adapter（T07）：gov.cn/zhengce 政策库 HTML 抓取。
 *
 * <p>真实接入国务院 {@code gov.cn/zhengce} 政策库（6 源中<b>唯一无统一 API</b> 的源）： {@link GovPolicyClient} 抓列表页 HTML
 * + Jsoup 解析为政策条目（标题/日期/URL），本 adapter 在 {@link #doFetch} 内按 {@code subject.industry} 关键词命中过滤、
 * 切出发文单位、逐条字段映射后以 {@code data.items} 列表承载落 {@code SourceResult.data}。 与 {@link
 * MockPolicySourceAdapter} 经 {@link RoutingSourceAdapter} 运行时路由共存（T36 热切换）。
 *
 * <p><b>行业关联策略</b>（核心，Spike-1 §6.6 + §4.6）：gov.cn 政策列表<b>无行业分类标签</b>（2026-09-21 curl 实测确认），
 * 政策为宏观流、不绑个股。 故 {@link #doFetch} 拉最近约 9 条政策后，按 {@code subject.industry} 在标题中命中行业热词过滤：
 * 标题含该行业任一热词（如「白酒」行业匹配「白酒/酒类/烟酒」等）→ 相关，命中条目标 {@code relatedIndustries}=该行业。 未命中 → {@link
 * Optional#empty()} → MISSING（不阻断详情页政策分区）。
 *
 * <p><b>可替换性</b>（参考 T06 {@code isRelevant} 模式）：取数（{@link GovPolicyClient#fetchPolicies}）与行业关联过滤（
 * {@link #isIndustryRelevant}）职责分离——后续接央行/证监会发布页或 sina 政策分类（Spike-1 §6.6 降级补充）时， 仅换 client +
 * 取数路径；{@code isIndustryRelevant} 关键词字典可保留或扩展。匹配策略与字典均为 package-private 静态，便于独立单测与未来替换。
 *
 * <p><b>发文单位切分</b>（Spike-1 §4.6「正则切标题」）：gov.cn 政策标题文本含发文单位前缀（如「国务院办公厅关于...」/ 「中共中央办公厅
 * 国务院办公厅印发《...》」）。 {@link #extractDepartment} 取标题<b>首个公文动词</b>（关于/印发/转发/公布/发布/颁布）之前的前缀作 department，
 * 标题（title）保留原文含单位。无动词前缀（如「市场监督管理所条例」）→ department=""。属 best-effort 提取（字段非关键，偏差不阻断），不改标题原文。
 *
 * <p><b>列表型分区映射策略</b>（同 {@link NewsSourceAdapter}/{@link AnnounceSourceAdapter}）： {@link
 * FieldMapper#map} 仅单条 flat Map 映射。 故 {@code doFetch} 内：① 对每条命中政策预处理（切 department、补 {@code
 * relatedIndustries}）； ② 用 {@link #itemMapping}（{@code field-mapping/gov-policy.json}）逐条映射（{@code
 * pubDate}→{@code to_iso_date} 等）； ③ 收集为 {@code items} 列表包装进 {@link RawFetch#data}（{@code
 * Map.of("items", ...)}）。 {@link #mappingConfig} 返回 {@code items→items} passthrough：模板层 {@code
 * fieldMapper.map(data, mappingConfig)} 作用在整张 data map（单层）， 对已规范化的 {@code items} 列表原样透传进 {@code
 * SourceResult.data["items"]}。 应用层聚合服务从 {@code data.items} 键提取（同 {@link MockPolicySourceAdapter}
 * 契约）。
 *
 * <p><b>pubDate 预处理</b>：gov.cn {@code <span>} 日期为 {@code yyyy-MM-dd}（实测干净）， {@code to_iso_date}
 * 直接解析为 {@code LocalDate}。空/缺 {@code span} 时 {@code pubDate} 不入 flat（FieldMapper 白名单语义不产出
 * publishedAt）。 异常日期让映射抛 {@link FieldMappingException}（罕见，gov.cn 日期格式稳定；按「不吞异常」抛出，整源降级 MISSING
 * 非阻断）。
 *
 * <p>弹性：超时 2s、重试 0（{@link ResilienceSpec#noRetry(Duration) noRetry(2s)}，对齐技术方案 §4.3 流程 1 政策 「超时
 * 2s」、 Spike-1 §6.6 {@code noRetry(2s)}，同 ADR-0011 取舍精神）。HTML 抓取较重，失败不重试。降级默认 MISSING——
 * 政策源挂或无关联均不阻断详情页其他分区。
 *
 * <p>字段语义依据 Spike-1 §4.6 + 2026-09-21 curl 实测：title（a 文本含发文单位/文号）/publishedAt（span yyyy-MM-dd）/
 * url（a@href 绝对链接）/department（正则切标题前缀）/relatedIndustries（关键词字典命中）为政策条目目标字段。
 */
public class PolicySourceAdapter extends AbstractSourceAdapter {


    /**
     * 模板层 map 步骤用：整张 data map（{@code {"items":[...]}}）的 items 列表原样透传。 逐条字段映射在 {@link #doFetch} 内用
     * {@link #itemMapping} 完成。
     */
    private static final List<FieldMapping> ITEMS_PASSTHROUGH =
            List.of(new FieldMapping("items", "items", Transform.NONE));

    /**
     * 行业 → 政策热词字典（2026-09-21 初始化）。覆盖 V2 种子三行业（白酒/银行/互联网）。 政策页无行业分类标签，靠标题 contains 任一热词命中。
     *
     * <p>热词选型保守（避免误命中）：白酒行业匹配「白酒/酒类/烟酒/食品」等专项词，不匹配泛「消费」； 银行业匹配「银行/金融/货币/信贷/利率/存款/贷款/回款/融资/降准」等；
     * 互联网行业匹配 「互联网/平台经济/数字经济/数据安全/数据要素/算法/人工智能/算力」等。 新行业/新热词在此扩展或后续外置为配置（参考 T06 {@code isRelevant}
     * 可替换策略）。
     */
    private static final Map<String, Set<String>> INDUSTRY_KEYWORDS =
            Map.of(
                    "白酒", Set.of("白酒", "酒类", "烟酒", "食品安全", "食品"),
                    "银行",
                            Set.of(
                                    "银行", "金融", "货币", "信贷", "利率", "存款", "贷款", "金融机构", "金融监管", "回款",
                                    "融资", "存款准备金", "降准"),
                    "互联网",
                            Set.of(
                                    "互联网", "平台经济", "数字经济", "数据安全", "数据要素", "算法", "人工智能", "网络安全",
                                    "电子商务", "算力", "个人信息保护", "数字化"));

    /**
     * 公文动词（发文单位前缀切分依据）。取标题首个出现动词之前的前缀作 department。
     * 「关于/印发/转发/公布/发布/颁布」为政策标题常见承接动词（如「国务院办公厅关于...」「中共中央办公厅 国务院办公厅印发《...》」）。
     * 「条例/办法/规定」等是政策名后缀而非承接动词，<b>不</b>作为切分动词（避免把「电力安全事故...条例」误切出 department）。
     */
    private static final List<String> TITLE_VERBS = List.of("关于", "印发", "转发", "公布", "发布", "颁布");

    private final GovPolicyClient client;
    private final FieldMapper fieldMapper;
    private final List<FieldMapping> itemMapping;

    public PolicySourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            GovPolicyClient client) {
        super(cache, fieldMapper, runner, breaker);
        this.client = client;
        // 模板层 fieldMapper 为 private，子类需另持引用以在 doFetch 内逐条映射（同 Announce/News adapter）。
        this.fieldMapper = fieldMapper;
        this.itemMapping = fieldMapper.loadMapping("field-mapping/gov-policy.json");
    }

    @Override
    public SourceCode sourceCode() {
        return SourceCode.POLICY;
    }

    @Override
    protected String sourceLabel() {
        return "国务院政策";
    }

    @Override
    protected List<FieldMapping> mappingConfig() {
        return ITEMS_PASSTHROUGH;
    }


    @Override
    protected Optional<RawFetch> doFetch(Subject subject) throws Exception {
        Optional<List<Map<String, Object>>> rawList = client.fetchPolicies();
        if (rawList.isEmpty()) {
            // 列表页无政策/HTML 为空/选择器无命中（gov.cn 改版）→ MISSING（成功调用，非异常）
            return Optional.empty();
        }
        String industry = subject.getIndustry();
        Set<String> keywords = industryKeywords(industry);
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Map<String, Object> raw : rawList.get()) {
            String title = stringOf(raw.get("title"));
            if (!isIndustryRelevant(title, keywords)) {
                continue;
            }
            Map<String, Object> flat = preprocess(raw, industry);
            Map<String, Object> mapped = fieldMapper.map(flat, itemMapping);
            hits.add(Collections.unmodifiableMap(new LinkedHashMap<>(mapped)));
        }
        if (hits.isEmpty()) {
            // 最近政策中无与该标的行业相关条目 → MISSING（不阻断，政策为宏观流命中率本就稀疏）
            return Optional.empty();
        }
        Map<String, Object> data = Map.of("items", List.copyOf(hits));
        return Optional.of(new RawFetch(data, sourceLabel(), Instant.now()));
    }

    /**
     * 行业关联关键词命中（可替换策略）：标题含该行业任一热词 → 相关。
     *
     * <p>简单 {@code contains} 字面匹配（Spike-1 §6.6）；null 安全（标题 null/blank 或关键词集空 → 不命中）。
     * 行业不在字典（如未来「医药」行业）→ 关键词集空 → 永不命中 → MISSING（字典未覆盖行业，可后续扩展）。
     */
    static boolean isIndustryRelevant(String title, Set<String> keywords) {
        if (title == null || title.isBlank() || keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String kw : keywords) {
            if (title.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取该行业的政策热词集。{@code industry} 为 null/不在字典 → {@code Set.of()}（空集，永不命中 → MISSING）。
     *
     * <p>{@link Map#getOrDefault(Object, Object)} 对 null key 返回 default（{@code Map.of} 不含 null 键），
     * 无 NPE。
     */
    private static Set<String> industryKeywords(String industry) {
        return INDUSTRY_KEYWORDS.getOrDefault(industry, Set.of());
    }

    /**
     * 预处理单条政策为 flat 原始 map 供 {@link FieldMapper} 单层映射： 切 department（best-effort）、补
     * relatedIndustries=命中的行业、 pubDate 空则不入 flat（白名单语义不产出 publishedAt）。
     */
    private static Map<String, Object> preprocess(Map<String, Object> raw, String industry) {
        String title = stringOf(raw.get("title"));
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("title", title);
        String pubDate = stringOf(raw.get("pubDate"));
        if (!pubDate.isBlank()) {
            flat.put("pubDate", pubDate);
        }
        putIfPresent(flat, "url", raw.get("url"));
        String department = extractDepartment(title);
        if (!department.isEmpty()) {
            flat.put("department", department);
        }
        if (industry != null && !industry.isBlank()) {
            flat.put("relatedIndustries", industry);
        }
        return flat;
    }

    /**
     * 切标题开头发文单位（至首个公文动词之前）。best-effort：无动词/动词在首位 → ""（department 不产出）。
     * 「关于/印发/转发/公布/发布/颁布」为承接动词；选最早出现者切分。
     */
    static String extractDepartment(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        int earliest = -1;
        for (String verb : TITLE_VERBS) {
            int idx = title.indexOf(verb);
            if (idx >= 0 && (earliest == -1 || idx < earliest)) {
                earliest = idx;
            }
        }
        if (earliest <= 0) {
            // 无动词（earliest=-1）或动词在首位（earliest=0，无单位前缀）→ 不产出 department
            return "";
        }
        return title.substring(0, earliest).trim();
    }

    private static String stringOf(Object value) {
        return value == null ? "" : value.toString();
    }

    private static void putIfPresent(Map<String, Object> flat, String key, Object value) {
        if (value != null && !"".equals(value)) {
            flat.put(key, value);
        }
    }
}
