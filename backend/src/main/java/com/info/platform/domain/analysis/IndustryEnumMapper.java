package com.info.platform.domain.analysis;

import com.info.platform.domain.aggregation.Market;
import java.util.Map;

/**
 * F10 行业原词 → 港美股枚举映射器（M29 T251，ADR-0064 裁决 2「东财 F10 中文口径直接落地，&gt;40 按大类归并」的代码权威面）。
 *
 * <p>纯函数（无状态无 IO）：输入 F10 {@code BELONG_INDUSTRY} 原词，输出归并后枚举名——港股 31 词 ≤40 直采（原词即枚举，恒等映射）； 美股 156
 * 词按大类归并 40 枚举。词表 = 2026-09-29 F10 全量首跑词频定稿（港 6961 行 / 美 21561 行），与 V37 {@code industry_enum_map}
 * 种子同源生成（{@code V37MarketFoundationMigrationTest} 断言两侧一致防漂移）。
 *
 * <p>兜底口径（方案 R4 双兜底）：null/空白/未收录新词 → {@link IndustryCategory#UNKNOWN_INDUSTRY}——源词表演化出的新行业不
 * 失败整轮同步，落到 UNKNOWN 后由词频巡检（下次迁移）收编。A 股不经过本映射器（f100 直落 + 既有 {@link IndustryCategory#isValid(String)}
 * 白名单）。
 */
public final class IndustryEnumMapper {

    private IndustryEnumMapper() {}

    /** 美股 F10 原词 → 归并枚举（156 词，T251 首跑定稿；港股无表——31 词恒等直采）。 */
    private static final Map<String, String> US_RAW_TO_ENUM =
            Map.ofEntries(
                    Map.entry("个人护理用品", "家居与个人用品"),
                    Map.entry("互动媒体与服务", "互联网与数字媒体"),
                    Map.entry("互动家庭娱乐", "互联网与数字媒体"),
                    Map.entry("互助储蓄与抵押信贷金融服务", "银行"),
                    Map.entry("互联网服务与基础设施", "互联网与数字媒体"),
                    Map.entry("交易与支付处理服务", "多元金融"),
                    Map.entry("人力资源与就业服务", "专业服务"),
                    Map.entry("人寿与健康保险", "保险"),
                    Map.entry("住宅建筑", "房地产服务与开发"),
                    Map.entry("保健护理产品经销商", "医疗保健设备与服务"),
                    Map.entry("保健护理服务", "医疗保健设备与服务"),
                    Map.entry("保健护理机构", "医疗保健设备与服务"),
                    Map.entry("保险经纪商", "保险"),
                    Map.entry("信息科技咨询与其它服务", "软件与信息服务"),
                    Map.entry("公路与铁路", "客运航空与交通设施"),
                    Map.entry("其他专卖店", "零售与经销"),
                    Map.entry("其他专门REIT", "房地产投资信托"),
                    Map.entry("再保险", "保险"),
                    Map.entry("写字楼REIT", "房地产投资信托"),
                    Map.entry("农产品与服务", "食品饮料"),
                    Map.entry("农用农业机械", "工业机械与集团"),
                    Map.entry("出版", "媒体与娱乐"),
                    Map.entry("制药", "制药"),
                    Map.entry("办公服务与用品", "商业服务与用品"),
                    Map.entry("包装食品与肉类", "食品饮料"),
                    Map.entry("化肥与农用药剂", "化学制品"),
                    Map.entry("区域性银行", "银行"),
                    Map.entry("医疗保健REIT", "房地产投资信托"),
                    Map.entry("医疗保健技术", "医疗保健设备与服务"),
                    Map.entry("医疗保健用品", "医疗保健设备与服务"),
                    Map.entry("医疗保健设备", "医疗保健设备与服务"),
                    Map.entry("半导体产品", "半导体"),
                    Map.entry("半导体材料与设备", "半导体"),
                    Map.entry("商业与住宅抵押贷款金融", "多元金融"),
                    Map.entry("商业印刷", "商业服务与用品"),
                    Map.entry("商品化工", "化学制品"),
                    Map.entry("啤酒酿造商", "食品饮料"),
                    Map.entry("地面货运", "货运与物流"),
                    Map.entry("复合型公用事业", "燃气与水务"),
                    Map.entry("多元化保险", "保险"),
                    Map.entry("多元化房地产业务", "房地产服务与开发"),
                    Map.entry("多品类零售", "零售与经销"),
                    Map.entry("多户住宅REIT", "房地产投资信托"),
                    Map.entry("多样化房地产投资信托", "房地产投资信托"),
                    Map.entry("多种化学制品", "化学制品"),
                    Map.entry("多种金属与采矿", "贵金属与采矿"),
                    Map.entry("多领域控股", "综合企业"),
                    Map.entry("安全和报警服务", "专业服务"),
                    Map.entry("客运航空公司", "客运航空与交通设施"),
                    Map.entry("家庭装潢零售", "零售与经销"),
                    Map.entry("家庭装饰品", "家居与个人用品"),
                    Map.entry("家庭装饰零售", "零售与经销"),
                    Map.entry("家用器具与特殊消费品", "家居与个人用品"),
                    Map.entry("家用电器", "家居与个人用品"),
                    Map.entry("居家用品", "家居与个人用品"),
                    Map.entry("工业REIT", "房地产投资信托"),
                    Map.entry("工业机械、物料与部件", "工业机械与集团"),
                    Map.entry("工业气体", "化学制品"),
                    Map.entry("工业集团企业", "工业机械与集团"),
                    Map.entry("广告", "媒体与娱乐"),
                    Map.entry("广播", "媒体与娱乐"),
                    Map.entry("应用软件", "软件与信息服务"),
                    Map.entry("建筑与工程", "建筑与工程"),
                    Map.entry("建筑产品", "建筑与工程"),
                    Map.entry("建筑机械与重型运输设备", "工业机械与集团"),
                    Map.entry("建筑材料", "包装与建材"),
                    Map.entry("房地产开发", "房地产服务与开发"),
                    Map.entry("房地产服务", "房地产服务与开发"),
                    Map.entry("房地产经营公司", "房地产服务与开发"),
                    Map.entry("技术产品经销商", "电子设备与元件"),
                    Map.entry("投资银行业与经纪业", "资本市场与投资服务"),
                    Map.entry("抵押房地产投资信托", "房地产投资信托"),
                    Map.entry("摩托车制造商", "汽车"),
                    Map.entry("教育服务", "消费者服务"),
                    Map.entry("数据处理与外包服务", "软件与信息服务"),
                    Map.entry("新能源发电业者", "电力与新能源"),
                    Map.entry("无线电信业务", "通信与电信"),
                    Map.entry("日常消费品零售", "零售与经销"),
                    Map.entry("有线和卫星电视", "媒体与娱乐"),
                    Map.entry("服装、服饰与奢侈品", "服装与奢侈品"),
                    Map.entry("服装零售", "零售与经销"),
                    Map.entry("机场服务", "客运航空与交通设施"),
                    Map.entry("林业产品", "包装与建材"),
                    Map.entry("水公用事业", "燃气与水务"),
                    Map.entry("汽车制造商", "汽车"),
                    Map.entry("汽车零件与设备", "汽车"),
                    Map.entry("汽车零售", "零售与经销"),
                    Map.entry("海上运输", "货运与物流"),
                    Map.entry("海港与服务", "客运航空与交通设施"),
                    Map.entry("消费信贷", "多元金融"),
                    Map.entry("消费电子产品", "电子设备与元件"),
                    Map.entry("消闲用品", "餐饮住宿与休闲"),
                    Map.entry("消闲设施", "餐饮住宿与休闲"),
                    Map.entry("烟草", "烟草"),
                    Map.entry("煤与消费用燃料", "煤炭与燃料"),
                    Map.entry("燃气公用事业", "燃气与水务"),
                    Map.entry("特殊消费者服务", "消费者服务"),
                    Map.entry("特殊金融服务", "多元金融"),
                    Map.entry("特种化学制品", "化学制品"),
                    Map.entry("独立电力生产商与能源贸易商", "电力与新能源"),
                    Map.entry("环境与设施服务", "商业服务与用品"),
                    Map.entry("生命科学工具和服务", "医疗保健设备与服务"),
                    Map.entry("生物科技", "生物科技"),
                    Map.entry("电力公用事业", "电力与新能源"),
                    Map.entry("电子元件", "电子设备与元件"),
                    Map.entry("电子制造服务", "电子设备与元件"),
                    Map.entry("电子设备和仪器", "电子设备与元件"),
                    Map.entry("电影与娱乐", "媒体与娱乐"),
                    Map.entry("电气部件与设备", "工业机械与集团"),
                    Map.entry("电脑与电子产品零售", "零售与经销"),
                    Map.entry("电脑硬件、储存设备及电脑周边", "电子设备与元件"),
                    Map.entry("石油与天然气的储存和运输", "能源设备与服务"),
                    Map.entry("石油与天然气的勘探与生产", "石油与天然气"),
                    Map.entry("石油与天然气的炼制和营销", "能源设备与服务"),
                    Map.entry("石油与天然气钻井", "能源设备与服务"),
                    Map.entry("石油天然气设备与服务", "能源设备与服务"),
                    Map.entry("管理型保健护理", "医疗保健设备与服务"),
                    Map.entry("系统软件", "软件与信息服务"),
                    Map.entry("纸制品", "包装与建材"),
                    Map.entry("纸质和塑料包装产品及材料", "包装与建材"),
                    Map.entry("纺织品", "服装与奢侈品"),
                    Map.entry("经销商", "零售与经销"),
                    Map.entry("综合性石油与天然气企业", "石油与天然气"),
                    Map.entry("综合性资本市场", "资本市场与投资服务"),
                    Map.entry("综合性银行", "银行"),
                    Map.entry("综合支持服务", "专业服务"),
                    Map.entry("综合电信业务", "通信与电信"),
                    Map.entry("综合金融服务", "多元金融"),
                    Map.entry("航天航空与国防", "航天航空与国防"),
                    Map.entry("航空货运与物流", "货运与物流"),
                    Map.entry("药品零售", "零售与经销"),
                    Map.entry("调查和咨询服务", "专业服务"),
                    Map.entry("财产与意外伤害保险", "保险"),
                    Map.entry("贵重金属与矿石", "贵金属与采矿"),
                    Map.entry("贸易公司与经销商", "零售与经销"),
                    Map.entry("资产管理与托管银行", "资本市场与投资服务"),
                    Map.entry("赌场与赌博", "餐饮住宿与休闲"),
                    Map.entry("轮胎与橡胶", "汽车"),
                    Map.entry("软饮料与不含酒精饮料", "食品饮料"),
                    Map.entry("通信设备", "通信与电信"),
                    Map.entry("酒店、度假村与豪华游轮", "餐饮住宿与休闲"),
                    Map.entry("酒店及度假村REIT", "房地产投资信托"),
                    Map.entry("酿酒商与葡萄酒商", "食品饮料"),
                    Map.entry("重型电气设备", "工业机械与集团"),
                    Map.entry("金属、玻璃及塑料器皿", "包装与建材"),
                    Map.entry("金融交易所和数据", "资本市场与投资服务"),
                    Map.entry("钢铁", "钢铁与铝"),
                    Map.entry("铁路", "客运航空与交通设施"),
                    Map.entry("铝", "钢铁与铝"),
                    Map.entry("零售REIT", "房地产投资信托"),
                    Map.entry("非传统电信运营商", "通信与电信"),
                    Map.entry("鞋类", "家居与个人用品"),
                    Map.entry("食品分销商", "食品饮料"),
                    Map.entry("食品零售", "零售与经销"),
                    Map.entry("餐馆", "餐饮住宿与休闲"),
                    Map.entry("黄金", "贵金属与采矿"));

    /** 美股原词 → 枚举映射只读视图（V37 种子一致性断言/审计消费；生产取数只经 {@link #map}）。 */
    public static Map<String, String> usMapping() {
        return US_RAW_TO_ENUM;
    }

    /**
     * 映射 F10 行业原词 → 港美股枚举。
     *
     * @param market 标的市场（HK / US；其他市场原样返回——A 股行业不经本映射器，防御直通）
     * @param rawIndustry F10 {@code BELONG_INDUSTRY} 原词（可 null）
     * @return 归并枚举名（恒在对应市场白名单内）；null/空白/未收录词 → {@code UNKNOWN}
     */
    public static String map(Market market, String rawIndustry) {
        if (market == Market.HK) {
            if (rawIndustry == null || rawIndustry.isBlank()) {
                return IndustryCategory.UNKNOWN_INDUSTRY;
            }
            String trimmed = rawIndustry.trim();
            return IndustryCategory.HK_INDUSTRIES.contains(trimmed)
                    ? trimmed
                    : IndustryCategory.UNKNOWN_INDUSTRY;
        }
        if (market == Market.US) {
            if (rawIndustry == null || rawIndustry.isBlank()) {
                return IndustryCategory.UNKNOWN_INDUSTRY;
            }
            return US_RAW_TO_ENUM.getOrDefault(
                    rawIndustry.trim(), IndustryCategory.UNKNOWN_INDUSTRY);
        }
        return rawIndustry;
    }
}
