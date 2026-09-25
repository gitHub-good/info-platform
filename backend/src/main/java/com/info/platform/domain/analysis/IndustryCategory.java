package com.info.platform.domain.analysis;

import java.util.Set;

/**
 * 行业主分类目录（35 枚举 = 申万一级行业 31 + 跨行业容器 4，M15 T121，ADR-0046 裁决 3）。
 *
 * <p><b>校验权威在代码侧</b>：模板文本中的枚举清单可被治理页编辑，但 L1 落库前以本常量白名单校验——模板被改坏产生的非法枚举行按拆批
 * 规则处理并进失败统计（模板漂移可见不静默）。热度聚合（T123）只扫 {@link #SW_INDUSTRIES}（容器不进榜）。
 *
 * <p>名称清单为方案 §2/§4.3 冻结值（与 V23 播种模板 v1.0 同源）；新增/更名属契约变更，须回架构走 ADR。
 */
public final class IndustryCategory {

    /** 兜底容器（低置信/非法枚举改写落点，方案 §4.3 兜底语义）。 */
    public static final String MARKET_OTHER = "市场·其他";

    /** 申万一级行业 31 个（热度榜聚合范围）。 */
    public static final Set<String> SW_INDUSTRIES =
            Set.of(
                    "农林牧渔", "基础化工", "钢铁", "有色金属", "电子", "家用电器", "食品饮料", "纺织服饰", "轻工制造", "医药生物",
                    "公用事业", "交通运输", "房地产", "商贸零售", "社会服务", "银行", "非银金融", "综合", "建筑材料", "建筑装饰",
                    "电力设备", "机械设备", "国防军工", "计算机", "传媒", "通信", "煤炭", "石油石化", "环保", "美容护理", "汽车");

    /** 跨行业容器 4 个（不进榜；宏观面/监管政策/国际/兜底）。 */
    public static final Set<String> CONTAINERS = Set.of("宏观", "监管·政策", "国际", MARKET_OTHER);

    private IndustryCategory() {}

    /** 全量 35 枚举白名单判定（主分类校验入口）。 */
    public static boolean isValid(String name) {
        return name != null && (SW_INDUSTRIES.contains(name) || CONTAINERS.contains(name));
    }

    /** 是否申万行业（次行业 sub 仅允许申万枚举，方案 §4.3 规则 4；容器判定用）。 */
    public static boolean isSwIndustry(String name) {
        return name != null && SW_INDUSTRIES.contains(name);
    }

    /** 全量枚举数（自检：31 + 4 = 35，方案冻结值）。 */
    public static int totalSize() {
        return SW_INDUSTRIES.size() + CONTAINERS.size();
    }
}
