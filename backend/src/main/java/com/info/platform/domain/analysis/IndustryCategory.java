package com.info.platform.domain.analysis;

import com.info.platform.domain.aggregation.Market;
import java.util.Set;

/**
 * 行业主分类目录（35 枚举 = 申万一级行业 31 + 跨行业容器 4，M15 T121，ADR-0046 裁决 3；M29 T251 扩港美股独立枚举集）。
 *
 * <p><b>校验权威在代码侧</b>：模板文本中的枚举清单可被治理页编辑，但 L1 落库前以本常量白名单校验——模板被改坏产生的非法枚举行按拆批
 * 规则处理并进失败统计（模板漂移可见不静默）。热度聚合（T123）只扫 {@link #SW_INDUSTRIES}（容器不进榜）。
 *
 * <p>名称清单为方案 §2/§4.3 冻结值（与 V23 播种模板 v1.0 同源）；新增/更名属契约变更，须回架构走 ADR。
 *
 * <p><b>M29 港美股枚举集（T251 首跑词频定稿，ADR-0064 裁决 2）</b>：港/美各自独立枚举（不映射回申万 31，跨市场重名由 market 消歧）——港股 = 东财
 * F10 {@code BELONG_INDUSTRY} 31 词直采（≤40 全量）；美股 = 156 词按大类归并 40 枚举；两集共用兜底枚举 {@link
 * #UNKNOWN_INDUSTRY}（各 ≤40 + UNKNOWN）。原词 → 枚举映射见 {@link IndustryEnumMapper} 与 V37 {@code
 * industry_enum_map} 种子（同源生成，单测断言一致防漂移）。
 */
public final class IndustryCategory {

    /** 兜底容器（低置信/非法枚举改写落点，方案 §4.3 兜底语义）。 */
    public static final String MARKET_OTHER = "市场·其他";

    /** 港美股未回填行业兜底枚举（F10 BELONG_INDUSTRY null / 未收录词；A 股不使用——A 股缺行业为 null 口径）。 */
    public static final String UNKNOWN_INDUSTRY = "UNKNOWN";

    /** 申万一级行业 31 个（热度榜聚合范围）。 */
    public static final Set<String> SW_INDUSTRIES =
            Set.of(
                    "农林牧渔", "基础化工", "钢铁", "有色金属", "电子", "家用电器", "食品饮料", "纺织服饰", "轻工制造", "医药生物",
                    "公用事业", "交通运输", "房地产", "商贸零售", "社会服务", "银行", "非银金融", "综合", "建筑材料", "建筑装饰",
                    "电力设备", "机械设备", "国防军工", "计算机", "传媒", "通信", "煤炭", "石油石化", "环保", "美容护理", "汽车");

    /** 跨行业容器 4 个（不进榜；宏观面/监管政策/国际/兜底）。 */
    public static final Set<String> CONTAINERS = Set.of("宏观", "监管·政策", "国际", MARKET_OTHER);

    /** 港股行业枚举 31 个（F10 直采，T251 首跑 2026-09-29 词频定稿；V37 industry_enum_map 同源种子）。 */
    public static final Set<String> HK_INDUSTRIES =
            Set.of(
                    "一般金属及矿石",
                    "专业零售",
                    "保险",
                    "公用事业",
                    "其他医疗保健",
                    "其他金融",
                    "农业产品",
                    "半导体",
                    "原材料",
                    "地产",
                    "媒体及娱乐",
                    "家庭电器及用品",
                    "工业工程",
                    "工用支援",
                    "工用运输",
                    "建筑",
                    "支援服务",
                    "旅游及消闲设施",
                    "汽车",
                    "消费者主要零售商",
                    "煤炭",
                    "电讯",
                    "石油及天然气",
                    "纺织及服饰",
                    "综合企业",
                    "药品及生物科技",
                    "资讯科技器材",
                    "软件服务",
                    "银行",
                    "食物饮品",
                    "黄金及贵金属");

    /** 美股行业枚举 40 个（F10 156 词大类归并，T251 首跑 2026-09-29 词频定稿；V37 industry_enum_map 同源种子）。 */
    public static final Set<String> US_INDUSTRIES =
            Set.of(
                    "专业服务",
                    "互联网与数字媒体",
                    "保险",
                    "制药",
                    "包装与建材",
                    "化学制品",
                    "医疗保健设备与服务",
                    "半导体",
                    "商业服务与用品",
                    "多元金融",
                    "媒体与娱乐",
                    "客运航空与交通设施",
                    "家居与个人用品",
                    "工业机械与集团",
                    "建筑与工程",
                    "房地产投资信托",
                    "房地产服务与开发",
                    "服装与奢侈品",
                    "汽车",
                    "消费者服务",
                    "烟草",
                    "煤炭与燃料",
                    "燃气与水务",
                    "生物科技",
                    "电力与新能源",
                    "电子设备与元件",
                    "石油与天然气",
                    "综合企业",
                    "能源设备与服务",
                    "航天航空与国防",
                    "货运与物流",
                    "贵金属与采矿",
                    "资本市场与投资服务",
                    "软件与信息服务",
                    "通信与电信",
                    "钢铁与铝",
                    "银行",
                    "零售与经销",
                    "食品饮料",
                    "餐饮住宿与休闲");

    private IndustryCategory() {}

    /** 全量 35 枚举白名单判定（主分类校验入口）。 */
    public static boolean isValid(String name) {
        return name != null && (SW_INDUSTRIES.contains(name) || CONTAINERS.contains(name));
    }

    /**
     * 分市场枚举白名单判定（M29 T251，方案 §4 C8）：A 股（含缺省/指数/板块）= 既有 35 枚举集；港股 = {@link #HK_INDUSTRIES} +
     * UNKNOWN；美股 = {@link #US_INDUSTRIES} + UNKNOWN。
     */
    public static boolean isValid(Market market, String name) {
        if (market == Market.HK) {
            return name != null && (HK_INDUSTRIES.contains(name) || UNKNOWN_INDUSTRY.equals(name));
        }
        if (market == Market.US) {
            return name != null && (US_INDUSTRIES.contains(name) || UNKNOWN_INDUSTRY.equals(name));
        }
        return isValid(name);
    }

    /** 是否申万行业（次行业 sub 仅允许申万枚举，方案 §4.3 规则 4；容器判定用）。 */
    public static boolean isSwIndustry(String name) {
        return name != null && SW_INDUSTRIES.contains(name);
    }

    /**
     * 分市场「进榜行业」判定（M29 T254/T255，方案 §4 C11 + §6.1）：榜单/热力图/事件 affected 白名单的统一口径——A 股 = 申万 31、 港股 =
     * {@link #HK_INDUSTRIES}、美股 = {@link #US_INDUSTRIES}；容器 4 与 UNKNOWN 兜底不进榜（跨市场重名由调用方 market
     * 参数消歧）。
     */
    public static boolean isBoardIndustry(Market market, String name) {
        if (name == null) {
            return false;
        }
        if (market == Market.HK) {
            return HK_INDUSTRIES.contains(name);
        }
        if (market == Market.US) {
            return US_INDUSTRIES.contains(name);
        }
        return SW_INDUSTRIES.contains(name);
    }

    /** 分市场行业体系口径标注（M29 T255，方案 §五 统一约定——响应顶层 {@code industrySystem} 值；拍板二口径不混排）。 */
    public static String industrySystemOf(Market market) {
        if (market == Market.HK) {
            return "港股：东财行业分类（31 直采，来源 F10 BELONG_INDUSTRY）";
        }
        if (market == Market.US) {
            return "美股：东财行业分类（归并 ≤40，来源 F10 BELONG_INDUSTRY）";
        }
        return "A股：申万一级 31";
    }

    /** 全量枚举数（自检：31 + 4 = 35，方案冻结值）。 */
    public static int totalSize() {
        return SW_INDUSTRIES.size() + CONTAINERS.size();
    }

    /** 港股枚举数自检（31 直采 + 不含 UNKNOWN——白名单集不含兜底，判定时另算）。 */
    public static int hkSize() {
        return HK_INDUSTRIES.size();
    }

    /** 美股枚举数自检（归并 40 大类 + 不含 UNKNOWN——白名单集不含兜底，判定时另算）。 */
    public static int usSize() {
        return US_INDUSTRIES.size();
    }
}
