package com.info.platform.application.mainline;

import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.application.common.RuntimeConfigSeeder;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 主线/龙头配置种子（M27 T243，方案 §4.3.3——ADR-0032 系列代码内置缺省播种）：{@code industry.mainline} 13 字段 + {@code
 * industry.leader} 7 字段（§3.4/§3.5 冻结值）。seed-if-absent（DB 为权威，已存在不覆盖）；回滚随 U35 清键。
 */
@Component
public class IndustryMainlineDefaults implements RuntimeConfigSeeder {

    @Override
    public List<RuntimeConfigSeed> seeds() {
        List<RuntimeConfigSeed> seeds = new ArrayList<>();
        seeds.add(
                new RuntimeConfigSeed(
                        IndustryMainlineSettings.MAINLINE_KEY,
                        "{\"wp\":0.40,\"wh\":0.35,\"we\":0.25,\"priceWinDay\":0.5,"
                                + "\"priceWinD5\":0.5,\"heatH24\":0.5,\"heatD7\":0.3,\"heatDelta\":0.2,"
                                + "\"topN\":5,\"persistMinDays\":2,\"persistWindowDays\":5,"
                                + "\"topThirdRank\":10,\"divergenceHeatRank\":13}",
                        "主线计算配置 mainline-v1（三维权重 wp/wh/we + 价格/热度子权重 + topN 3~5 + 持续性门槛"
                                + " persistMinDays/persistWindowDays/topThirdRank + 背离标注阈值，M27 方案 §4.3.3；"
                                + "权重和须 = 1±0.001，页面保存即热生效）"));
        seeds.add(
                new RuntimeConfigSeed(
                        IndustryMainlineSettings.LEADER_KEY,
                        "{\"wa\":0.50,\"wv\":0.35,\"wq\":0.15,\"mentionDays\":7,\"topN\":3,"
                                + "\"qDay\":0.5,\"qD5\":0.5}",
                        "龙头识别配置 leader-v1（三维权重 wa 资讯关注度/wv 价值评分/wq 价格动量 + 提及窗 mentionDays"
                                + " + 龙头数 topN 1~5 + 价格子权重 qDay/qD5，M27 方案 §3.5；权重和须 = 1±0.001）"));
        return seeds;
    }
}
