package com.info.platform.infrastructure.valuation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.application.common.RuntimeConfigSeeder;
import com.info.platform.application.valuation.ValuationConfigValidator;
import com.info.platform.domain.valuation.ValuationParams;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 评分权重种子（{@code score.weight}，M20 T172，方案 §4.3 冻结缺省）：13 参数取 {@link ValuationParams#defaults()}
 * 单一事实源（五维权重 0.40/0.20/0.20/0.20/0.00 + 双窗 10/30 + 半衰期 5.0 + 双饱和常数 3.0/1.5 + 三阈值 60/50/80）。
 * seed-if-absent 由配置中心统一执行（DB 已有键不动，DB 为权威）；basis 指纹串由读时代码派生不落文档。
 */
@Component
public class ScoreWeightRuntimeConfigSeeder implements RuntimeConfigSeeder {

    private final ObjectMapper objectMapper;

    public ScoreWeightRuntimeConfigSeeder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RuntimeConfigSeed> seeds() {
        ValuationParams defaults = ValuationParams.defaults();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("wCatalyst", defaults.wCatalyst());
        doc.put("wConduction", defaults.wConduction());
        doc.put("wFundamental", defaults.wFundamental());
        doc.put("wRisk", defaults.wRisk());
        doc.put("wValuation", defaults.wValuation());
        doc.put("catalystWindowDays", defaults.catalystWindowDays());
        doc.put("assocWindowDays", defaults.assocWindowDays());
        doc.put("halfLifeDays", defaults.halfLifeDays());
        doc.put("k1Saturation", defaults.k1Saturation());
        doc.put("k3Saturation", defaults.k3Saturation());
        doc.put("btCatalystMin", defaults.btCatalystMin());
        doc.put("btConductionMin", defaults.btConductionMin());
        doc.put("btRiskMin", defaults.btRiskMin());
        return List.of(
                new RuntimeConfigSeed(
                        ValuationConfigValidator.CONFIG_KEY,
                        write(doc),
                        "评分引擎权重与阈值（五维权重 事件催化/行业传导/基本面边际/风险安全/估值水平 + 事件/关联窗口 + 半衰期 +"
                                + " 饱和常数 + 「有突破」三阈值；保存即热生效——下一轮 17:30 快照按新参数计算，"
                                + "M20 方案 §4.3）"));
    }

    private String write(Map<String, Object> doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("评分权重种子序列化失败: " + e.getOriginalMessage(), e);
        }
    }
}
