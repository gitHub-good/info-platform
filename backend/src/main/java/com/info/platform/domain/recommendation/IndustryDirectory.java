package com.info.platform.domain.recommendation;

import com.info.platform.domain.analysis.IndustryCategory;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 行业别名目录（领域代码常量，M16 ADR-0051 裁决 4 / 方案 §4.9）：申万 31 行业 → 别名/子行业词目录（~31×4 词）。
 *
 * <p>P2 行业关注集<b>通道 A 主力</b>：活跃 TOPIC/POLICY_THEME 订阅 subKey contains 命中某行业的行业名或别名词 → 该行业入集
 * （如订阅「半导体」→ 电子；订阅「货币政策」→ 无映射 → 不入集，该订阅走 P3 主题命中）。目录随申万枚举演进维护；热改诉求留 M18——目录错误影响面是 P2 命中偏窄/偏宽（偏窄 =
 * 少推不误推，安全侧失败），可观测可迭代。
 *
 * <p>别名收录口径：常见子行业/产业链词 + 通俗同义词，避免与行业名跨集歧义（如「新能源汽车」归汽车、「锂电池」归电力设备—— 按申万分类惯例整车与电池分属）。
 */
public final class IndustryDirectory {

    /** 申万 31 行业 → 别名词表（LinkedHashMap 保序稳定输出；行业名本身也是匹配词）。 */
    private static final Map<String, List<String>> ALIASES = buildAliases();

    /** 行业名 + 别名全词表索引（词 → 行业；一词只归一行业，跨行业歧义词不收录）。 */
    private static final Map<String, String> WORD_TO_INDUSTRY = buildWordIndex();

    /**
     * 东财行业板块名 → 申万一级行业静态映射（M21 T180，ADR-0059 裁决 1③/§4.1.3）。值域为 datacenter {@code
     * RPT_WEB_RESPREDICT.INDUSTRY_BOARD} 全量实测 127 值（2026-09-27 30 页 2933 只零缺失拉取；方案样本估 ~104，
     * 全量值域已超样收录）；push2 clist f100 为同口径板块名（方案 §1.2 实测样本「通用设备/军工电子Ⅱ/IT服务Ⅱ」均在此表）。 未收录返
     * null（安全侧失败：少关联不误关联）——IndustryAssociator 路 C 只读本映射输出，不读 subject_master.industry 原文（ADR-0059 对
     * ADR-0058 裁决 2 的替代锚点）。
     */
    private static final Map<String, String> SW_PRIMARY_OF = buildSwPrimaryOf();

    private IndustryDirectory() {}

    /**
     * 通道 A 映射：subKey contains 命中某行业的行业名或别名词 → 该行业入集。
     *
     * @param subKey 订阅主题词（null/空白返回空集——该订阅走 P3 主题命中）
     * @return 命中行业集（申万枚举名；保序稳定）
     */
    public static Set<String> industriesOf(String subKey) {
        if (subKey == null || subKey.isBlank()) {
            return Set.of();
        }
        Set<String> hits = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : WORD_TO_INDUSTRY.entrySet()) {
            if (subKey.contains(entry.getKey())) {
                hits.add(entry.getValue());
            }
        }
        return Collections.unmodifiableSet(hits);
    }

    /**
     * 反查：词是否为本目录收录的行业词（行业名或别名）。
     *
     * @param word 待判词（null 返回 false）
     */
    public static boolean isDirectoryWord(String word) {
        return word != null && WORD_TO_INDUSTRY.containsKey(word);
    }

    /** 反查：词所属行业（行业名或别名命中返回申万枚举名；未收录返回 null）。 */
    public static String industryOfWord(String word) {
        return word == null ? null : WORD_TO_INDUSTRY.get(word);
    }

    /**
     * 东财行业板块名 → 申万一级行业（M21 T180 路 C 成员边映射入口；§4.1.3）。
     *
     * @param board 东财板块名（如 {@code 银行Ⅱ}/{@code 消费电子}/{@code IT服务Ⅱ}；null/空白返 null）
     * @return 申万一级行业名；未收录返回 {@code null}（安全侧失败——不强行映射，缺漏由 WARN 计数观察、首跑后补表）
     */
    public static String swPrimaryOf(String board) {
        return board == null ? null : SW_PRIMARY_OF.get(board);
    }

    /** 东财板块全量值域（恰 127 实测板块；单测覆盖率与值域漂移对账面）。 */
    public static Set<String> allEastmoneyBoards() {
        return Collections.unmodifiableSet(SW_PRIMARY_OF.keySet());
    }

    /** 全词表（含行业名与别名；单测覆盖率与白名单校验器消费面）。 */
    public static Set<String> allWords() {
        return Collections.unmodifiableSet(WORD_TO_INDUSTRY.keySet());
    }

    /** 目录行业数（自检 = 申万 31 全覆盖）。 */
    public static int industryCount() {
        return ALIASES.size();
    }

    private static Map<String, List<String>> buildAliases() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("农林牧渔", List.of("种植业", "养殖业", "生猪", "种业", "农产品"));
        map.put("基础化工", List.of("化工", "氟化工", "磷化工", "化肥", "聚氨酯"));
        map.put("钢铁", List.of("钢材", "粗钢", "特钢", "铁矿石", "冶金"));
        map.put("有色金属", List.of("铜", "铝", "锂矿", "稀土", "黄金"));
        map.put("电子", List.of("半导体", "芯片", "集成电路", "消费电子", "光电子"));
        map.put("家用电器", List.of("家电", "白电", "黑电", "空调", "厨电"));
        map.put("食品饮料", List.of("白酒", "乳制品", "调味品", "饮料", "啤酒"));
        map.put("纺织服饰", List.of("纺织", "服装", "面料", "品牌服饰", "鞋帽"));
        map.put("轻工制造", List.of("造纸", "家居", "定制家具", "包装", "文具"));
        map.put("医药生物", List.of("创新药", "医疗器械", "CXO", "中药", "疫苗"));
        map.put("公用事业", List.of("水务", "燃气", "水电", "火电", "核电"));
        map.put("交通运输", List.of("航运", "港口", "快递", "物流", "航空运输"));
        map.put("房地产", List.of("地产", "楼市", "房价", "物业", "保障房"));
        map.put("商贸零售", List.of("零售", "免税", "超市", "电商", "百货"));
        map.put("社会服务", List.of("旅游", "酒店", "餐饮", "职业教育", "景区"));
        map.put("银行", List.of("商业银行", "信贷", "存款利率", "贷款", "大行"));
        map.put("非银金融", List.of("券商", "保险", "基金", "期货", "信托"));
        map.put("综合", List.of("多元业务"));
        map.put("建筑材料", List.of("水泥", "玻璃", "玻纤", "石膏板", "耐火材料"));
        map.put("建筑装饰", List.of("建筑施工", "基建", "装修", "装饰工程", "施工总承包"));
        map.put("电力设备", List.of("锂电池", "光伏", "风电", "储能", "电网设备"));
        map.put("机械设备", List.of("工程机械", "机床", "工业母机", "自动化设备", "检测设备"));
        map.put("国防军工", List.of("军工", "航天", "兵器", "国防工业", "军贸"));
        map.put("计算机", List.of("软件", "信创", "云计算", "数据要素", "网络安全"));
        map.put("传媒", List.of("影视", "游戏", "广告", "出版", "院线"));
        map.put("通信", List.of("运营商", "5G", "光通信", "卫星通信", "通信设备"));
        map.put("煤炭", List.of("动力煤", "焦煤", "煤价", "采煤", "煤炭开采"));
        map.put("石油石化", List.of("原油", "天然气", "油价", "炼化", "石化"));
        map.put("环保", List.of("环境治理", "污水处理", "垃圾分类", "环卫", "环保设备"));
        map.put("美容护理", List.of("化妆品", "医美", "护肤", "个护", "美容"));
        map.put("汽车", List.of("新能源汽车", "整车", "汽车零部件", "智能驾驶", "车企"));
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, String> buildWordIndex() {
        Map<String, String> index = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : ALIASES.entrySet()) {
            index.put(entry.getKey(), entry.getKey()); // 行业名本身可命中
            for (String alias : entry.getValue()) {
                String previous = index.putIfAbsent(alias, entry.getKey());
                if (previous != null && !previous.equals(entry.getKey())) {
                    // 一词归一行业（跨行业歧义词收录即冲突——目录维护红线，启动期暴露）
                    throw new IllegalStateException(
                            "IndustryDirectory 别名跨行业冲突: "
                                    + alias
                                    + " → "
                                    + previous
                                    + " 与 "
                                    + entry.getKey());
                }
            }
        }
        return Collections.unmodifiableMap(index);
    }

    /** 目录与申万枚举一致性自检（行业集 = {@link IndustryCategory#SW_INDUSTRIES}；测试断言面）。 */
    public static boolean coversAllSwIndustries() {
        return ALIASES.keySet().equals(IndustryCategory.SW_INDUSTRIES);
    }

    /**
     * 东财板块 → 申万 31 映射表（127 实测值，按申万目标分组维护；一词只归一行业）。 映射口径：东财板块即申万 2021 二/三级行业名的板块化呈现——归属按申万 2021
     * 一级行业分类树。
     */
    private static Map<String, String> buildSwPrimaryOf() {
        Map<String, String> map = new LinkedHashMap<>();
        // 农林牧渔（7 板块）
        putAll(map, "农林牧渔", "养殖业", "农业综合Ⅱ", "农产品加工", "动物保健Ⅱ", "渔业", "种植业", "饲料");
        // 基础化工（6）
        putAll(map, "基础化工", "农化制品", "化学制品", "化学原料", "化学纤维", "塑料", "橡胶");
        // 钢铁（3）
        putAll(map, "钢铁", "冶钢原料", "普钢", "特钢Ⅱ");
        // 有色金属（5）
        putAll(map, "有色金属", "小金属", "工业金属", "贵金属", "能源金属", "金属新材料");
        // 电子（6）
        putAll(map, "电子", "半导体", "元件", "光学光电子", "消费电子", "其他电子Ⅱ", "电子化学品Ⅱ");
        // 家用电器（7）
        putAll(map, "家用电器", "白色家电", "黑色家电", "小家电", "厨卫电器", "照明设备Ⅱ", "家电零部件Ⅱ", "其他家电Ⅱ");
        // 食品饮料（6）
        putAll(map, "食品饮料", "白酒Ⅱ", "非白酒", "休闲食品", "调味发酵品Ⅱ", "食品加工", "饮料乳品");
        // 纺织服饰（2）
        putAll(map, "纺织服饰", "服装家纺", "纺织制造");
        // 轻工制造（5）
        putAll(map, "轻工制造", "包装印刷", "家居用品", "文娱用品", "造纸", "饰品");
        // 医药生物（6）
        putAll(map, "医药生物", "中药Ⅱ", "化学制药", "生物制品", "医疗器械", "医疗服务", "医药商业");
        // 公用事业（2）
        putAll(map, "公用事业", "电力", "燃气Ⅱ");
        // 交通运输（5）
        putAll(map, "交通运输", "物流", "航空机场", "航运港口", "铁路公路");
        // 房地产（2）
        putAll(map, "房地产", "房地产开发", "房地产服务");
        // 商贸零售（5）
        putAll(map, "商贸零售", "一般零售", "专业连锁Ⅱ", "互联网电商", "旅游零售Ⅱ", "贸易Ⅱ");
        // 社会服务（5）
        putAll(map, "社会服务", "专业服务", "体育Ⅱ", "教育", "旅游及景区", "酒店餐饮");
        // 银行（1）
        putAll(map, "银行", "银行Ⅱ");
        // 非银金融（3）
        putAll(map, "非银金融", "证券Ⅱ", "保险Ⅱ", "多元金融");
        // 综合（1）
        putAll(map, "综合", "综合Ⅱ");
        // 建筑材料（4）
        putAll(map, "建筑材料", "水泥", "玻璃玻纤", "装修建材", "非金属材料Ⅱ");
        // 建筑装饰（5）
        putAll(map, "建筑装饰", "基础建设", "专业工程", "工程咨询服务Ⅱ", "房屋建设Ⅱ", "装修装饰Ⅱ");
        // 电力设备（6）
        putAll(map, "电力设备", "电池", "光伏设备", "风电设备", "电网设备", "电机Ⅱ", "其他电源设备Ⅱ");
        // 机械设备（5）
        putAll(map, "机械设备", "通用设备", "专用设备", "工程机械", "自动化设备", "轨交设备Ⅱ");
        // 国防军工（5）
        putAll(map, "国防军工", "军工电子Ⅱ", "航空装备Ⅱ", "航天装备Ⅱ", "航海装备Ⅱ", "地面兵装Ⅱ");
        // 计算机（3）
        putAll(map, "计算机", "计算机设备", "软件开发", "IT服务Ⅱ");
        // 传媒（6）
        putAll(map, "传媒", "出版", "广告营销", "影视院线", "数字媒体", "游戏Ⅱ", "电视广播Ⅱ");
        // 通信（2）
        putAll(map, "通信", "通信设备", "通信服务");
        // 煤炭（2）
        putAll(map, "煤炭", "煤炭开采", "焦炭Ⅱ");
        // 石油石化（3）
        putAll(map, "石油石化", "油气开采Ⅱ", "油服工程", "炼化及贸易");
        // 环保（2）
        putAll(map, "环保", "环保设备Ⅱ", "环境治理");
        // 美容护理（3）
        putAll(map, "美容护理", "个护用品", "化妆品", "医疗美容");
        // 汽车（5）
        putAll(map, "汽车", "乘用车", "商用车", "汽车零部件", "汽车服务", "摩托车及其他");
        return Collections.unmodifiableMap(map);
    }

    private static void putAll(Map<String, String> map, String industry, String... boards) {
        for (String board : boards) {
            map.put(board, industry);
        }
    }
}
