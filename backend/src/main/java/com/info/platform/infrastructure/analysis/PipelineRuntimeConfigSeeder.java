package com.info.platform.infrastructure.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.application.common.RuntimeConfigSeeder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 管道域运行时配置种子（{@code pipeline.*}，M15 T120~T125，方案 §4.8 键表 / ADR-0032 seed-if-absent）。
 *
 * <p>已播种 3 键（有消费方的键先落，防后续批次消费方定形前抢注 DB 权威值）：{@code pipeline.global}（L1 批量参数——T121 消费）、 {@code
 * pipeline.l0}（预筛参数——T120 消费）、{@code pipeline.l2}（事件提取参数——T122 消费）。 {@code pipeline.heat/budget} 随
 * T123/T125 消费方落地。{@code simhashDistanceMax} 缺省 18 = ADR-0047 实测勘定（方案原文 3 在 20~60 字 CJK 标题上召回失效）。
 */
@Component
public class PipelineRuntimeConfigSeeder implements RuntimeConfigSeeder {

    private final ObjectMapper objectMapper;

    public PipelineRuntimeConfigSeeder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RuntimeConfigSeed> seeds() {
        List<RuntimeConfigSeed> seeds = new ArrayList<>();
        Map<String, Object> global = new LinkedHashMap<>();
        global.put("batchWindowMinutes", 10);
        global.put("l1BatchSize", 20);
        global.put("confidenceFloor", 0.45);
        global.put("maxRetriesPerDay", 3);
        global.put("l1BackfillHours", 24);
        global.put("l0BufferMinutes", 2);
        seeds.add(
                new RuntimeConfigSeed(
                        "pipeline.global",
                        write(global),
                        "管道全局参数（L1 批大小 10~30/置信度兜底阈值/当日重试上限/待处理回看窗口/L0 摄取缓冲，M15 方案 §4.8）"));
        Map<String, Object> l0 = new LinkedHashMap<>();
        l0.put("noiseKeywords", NoiseSeed.KEYWORDS);
        l0.put("noisePatterns", NoiseSeed.PATTERNS);
        l0.put("simhashDistanceMax", 18);
        l0.put("editDistanceMax", 0.25);
        l0.put("nearDupWindowHours", 24);
        l0.put("minTitleLength", 8);
        seeds.add(
                new RuntimeConfigSeed(
                        "pipeline.l0",
                        write(l0),
                        "L0 预筛参数（noise 关键词与正则/近重复海明预筛[ADR-0047 勘定 18]与编辑距离阈值/24h 比较窗/超短豁免，M15 方案 §4.8）"));
        Map<String, Object> l2 = new LinkedHashMap<>();
        l2.put("l2BatchSize", 10);
        l2.put("threshold", 2.5);
        l2.put("quotaRatio", 0.2);
        l2.put("subjectBonus", 1.5);
        Map<String, Object> sourceWeights = new LinkedHashMap<>();
        sourceWeights.put("政策", 2.0);
        sourceWeights.put("宏观", 2.0);
        sourceWeights.put("快讯", 1.5);
        sourceWeights.put("媒体", 1.0);
        sourceWeights.put("国际", 1.0);
        sourceWeights.put("自建", 1.0);
        l2.put("sourceWeights", sourceWeights);
        l2.put(
                "strongTriggers",
                List.of(
                        "业绩预告", "预增", "预亏", "并购", "重组", "收购", "回购", "增持", "减持", "重大合同", "中标", "处罚",
                        "立案", "降准", "降息", "关税", "管制", "突破", "获批"));
        l2.put(
                "mediumTriggers",
                List.of(
                        "签约", "合作", "投产", "上调", "下调", "涨价", "降价", "新高", "新低", "停牌", "复牌", "辞职",
                        "聘任", "上线", "发布"));
        seeds.add(
                new RuntimeConfigSeed(
                        "pipeline.l2",
                        write(l2),
                        "L2 事件提取参数（重要性打分源权重/强弱触发词/标的加成/阈值 2.5/日配额比例 0.2/批大小，M15 方案 §4.4/§4.8）"));
        return seeds;
    }

    private String write(Map<String, Object> doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("管道配置种子序列化失败: " + e.getOriginalMessage(), e);
        }
    }

    /** 种子常量（与 {@code NoiseRuleEngine} 缺省同源维护）。 */
    private static final class NoiseSeed {

        private static final List<String> KEYWORDS =
                List.of(
                        "广告", "推广", "开户", "开户礼", "佣金万", "礼包", "课程", "直播间的优惠", "赞助", "征文", "订报",
                        "读者福利", "招聘启事", "有偿征稿", "会员专享");

        private static final List<String> PATTERNS =
                List.of("^https?://\\S+$", "(免费|限时).*(领取|报名|听课)", "关注(本台|我们).*(公众号|频道)");
    }
}
