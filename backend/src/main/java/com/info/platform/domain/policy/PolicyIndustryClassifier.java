package com.info.platform.domain.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 政策行业关联分类器（领域层纯净组件，对齐 Spike-1 §6.6 + §4.6 行业热词字典）。
 *
 * <p>gov.cn 政策列表<b>无行业分类标签</b>（2026-09-21 curl 实测确认），故按标题命中行业热词字典标注 {@code related_industries}。与
 * T07 {@code PolicySourceAdapter#isIndustryRelevant} 同源字典（白酒/银行/互联网 V2 种子三行业）， 但 T07
 * 是<b>按单一标的行业</b>过滤（命中即取），本分类器是<b>多标签</b>标注（标题命中哪些行业就标哪些，供政策流关联行业标签 + 详情关联自选标的）。字典后续可外置为配置或与 T07
 * 统一抽取，当前隔离避免改动已就位的 T07。
 *
 * <p>热词选型保守（避免误命中）：白酒匹配「白酒/酒类/烟酒/食品安全/食品」； 银行匹配「银行/金融/货币/信贷/利率/存款/贷款/金融机构 /金融监管/回款/融资/存款准备金/降准」；
 * 互联网匹配「互联网/平台经济/数字经济/数据安全/数据要素/算法/人工智能/网络安全 /电子商务/算力/个人信息保护/数字化」。新行业/热词在此扩展。
 */
public final class PolicyIndustryClassifier {

    /** 行业 → 政策热词字典（与 T07 PolicySourceAdapter.INDUSTRY_KEYWORDS 同源，LinkedHashMap 保稳定顺序）。 */
    private static final Map<String, Set<String>> INDUSTRY_KEYWORDS;

    static {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        m.put("白酒", Set.of("白酒", "酒类", "烟酒", "食品安全", "食品"));
        m.put(
                "银行",
                Set.of(
                        "银行", "金融", "货币", "信贷", "利率", "存款", "贷款", "金融机构", "金融监管", "回款", "融资",
                        "存款准备金", "降准"));
        m.put(
                "互联网",
                Set.of(
                        "互联网", "平台经济", "数字经济", "数据安全", "数据要素", "算法", "人工智能", "网络安全", "电子商务", "算力",
                        "个人信息保护", "数字化"));
        INDUSTRY_KEYWORDS = Collections.unmodifiableMap(m);
    }

    private PolicyIndustryClassifier() {}

    /**
     * 按标题标注关联行业（多标签）：标题命中哪些行业的热词，就返回哪些行业（按字典序，去重）。 无命中返回空列表（{@code related_industries}
     * 落空，详情页关联自选标的按 industry 匹配自然空集）。
     */
    public static List<String> classify(String title) {
        if (title == null || title.isBlank()) {
            return List.of();
        }
        List<String> hits = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : INDUSTRY_KEYWORDS.entrySet()) {
            for (String kw : entry.getValue()) {
                if (title.contains(kw)) {
                    hits.add(entry.getKey());
                    break; // 该行业任一热词命中即标该行业，跳出内层
                }
            }
        }
        return List.copyOf(hits);
    }
}
