package com.info.platform.domain.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 事件→行业影响评估模板（领域纯函数，M17 T144，REQ 拍板五-4 / 故事 3）：首批 ≥10 类模板——8 具名事件枚举全覆盖（9 值含 OTHER 兜底）+
 * 政策发布宏观三分（货币/财政/产业），模板 = 纯规则映射（事件类型 + 方向 → 受影响行业集 + 逻辑链模板文本），<b>无 LLM</b> （走向判断的 AI
 * 语言组织留在周报，任务简报裁量）。
 *
 * <p><b>零新增事实红线</b>（REQ 故事 3 场景 4）：行业集来源仅两处——模板冻结传导框架（拍板五-4 授权的默认框架）与 {@code event_item}
 * 结构化字段（affected_industries ∪ subjects.industry），全部经申万 31 白名单把守（实体 + 模板矩阵单测双防线）； per 行业方向 = 事件方向（
 * polarity 全 +1，不做反转发明）；NEUTRAL 事件全链中性。
 *
 * <p>公司类事件行业缺位（affected 与 subjects 双空）时回落类型默认行业集（中性观察文案——方向事实缺位不外推），保证每类至少一条产出（场景
 * 3）。政策发布按关键词判读宏观细分：货币（降准/降息/逆回购/LPR…）→ 财政（专项债/减税/预算…）→ 其余产业。
 */
public final class ImpactChainTemplates {

    /** 政策发布宏观细分模板键：货币政策。 */
    public static final String KEY_POLICY_MONETARY = "POLICY_MONETARY";

    /** 政策发布宏观细分模板键：财政政策。 */
    public static final String KEY_POLICY_FISCAL = "POLICY_FISCAL";

    /** 政策发布宏观细分模板键：产业政策（政策发布的缺省细分）。 */
    public static final String KEY_POLICY_INDUSTRIAL = "POLICY_INDUSTRIAL";

    /** 宏观政策细分（政策发布事件的子类判读结果）。 */
    public enum MacroKind {
        MONETARY("货币政策"),
        FISCAL("财政政策"),
        INDUSTRIAL("产业政策");

        private final String displayName;

        MacroKind(String displayName) {
            this.displayName = displayName;
        }

        /** 中文展示名。 */
        public String displayName() {
            return displayName;
        }
    }

    /** 模板渲染产物（单行业一行）。 */
    public record ChainOutput(
            String templateKey, String industry, Direction direction, String logicChain) {}

    /** 宏观模板传导条目（行业 + 三方向文案——利好/利空/中性）。 */
    private record MacroEntry(
            String industry, String bullishLogic, String bearishLogic, String neutralLogic) {

        String logicOf(Direction direction) {
            return switch (direction) {
                case BULLISH -> bullishLogic;
                case BEARISH -> bearishLogic;
                case NEUTRAL -> neutralLogic;
            };
        }
    }

    /** 货币政策传导框架（宽松/收紧双文案 + 中性观察）。 */
    private static final List<MacroEntry> MONETARY_ENTRIES =
            List.of(
                    new MacroEntry(
                            "银行",
                            "流动性宽松降低银行负债成本，信贷投放预期改善",
                            "流动性收紧抬升银行负债成本，净息差承压",
                            "货币政策信号中性，银行负债成本影响待观察"),
                    new MacroEntry(
                            "非银金融",
                            "市场利率下行提振非银机构交易与自营业务预期",
                            "利率中枢上行压制非银机构交易活跃度预期",
                            "货币政策信号中性，非银业务影响待观察"),
                    new MacroEntry(
                            "房地产",
                            "资金面宽松支撑按揭利率下行与销售预期",
                            "资金面趋紧抬升按揭利率，销售预期承压",
                            "货币政策信号中性，房地产资金面影响待观察"));

    /** 财政政策传导框架（基建链四行业）。 */
    private static final List<MacroEntry> FISCAL_ENTRIES =
            List.of(
                    new MacroEntry(
                            "建筑装饰", "财政扩张提振基建订单与施工预期", "财政收缩压制基建订单与施工预期", "财政信号中性，基建订单影响待观察"),
                    new MacroEntry("建筑材料", "基建投资加码拉动建材需求预期", "基建投资收缩压制建材需求预期", "财政信号中性，建材需求影响待观察"),
                    new MacroEntry(
                            "交通运输", "财政投入改善交通基建与运营环境预期", "财政投入收缩压制交通基建预期", "财政信号中性，交通基建影响待观察"),
                    new MacroEntry(
                            "公用事业", "财政支出扩张支撑公用事业投资预期", "财政支出收缩压制公用事业投资预期", "财政信号中性，公用事业影响待观察"));

    /** 产业政策缺省传导框架（事件无行业信息时的中性观察集——装备制造三类）。 */
    private static final List<String> INDUSTRIAL_DEFAULTS = List.of("电力设备", "机械设备", "电子");

    /** 公司类事件类型默认行业集（affected 与 subjects 双空时的中性观察兜底，每类非空）。 */
    private static final Map<EventType, List<String>> TYPE_DEFAULTS =
            buildTypeDefaults(
                    Map.of(
                            EventType.EARNINGS_FORECAST,
                            List.of("非银金融"),
                            EventType.MA_MERGER,
                            List.of("非银金融"),
                            EventType.BUYBACK_CHANGE,
                            List.of("非银金融"),
                            EventType.MAJOR_CONTRACT,
                            List.of("建筑装饰"),
                            EventType.REGULATORY_PENALTY,
                            List.of("非银金融"),
                            EventType.EXEC_CHANGE,
                            List.of("综合"),
                            EventType.TECH_BREAKTHROUGH,
                            List.of("电子"),
                            EventType.POLICY_RELEASE,
                            List.of("综合"),
                            EventType.OTHER,
                            List.of("综合")));

    /** 货币政策关键词（summary+quote contains 判读）。 */
    private static final Set<String> MONETARY_KEYWORDS =
            Set.of(
                    "降准", "降息", "加息", "升准", "逆回购", "MLF", "LPR", "准备金", "流动性", "货币政策", "央行", "公开市场",
                    "再贷款");

    /** 财政政策关键词。 */
    private static final Set<String> FISCAL_KEYWORDS =
            Set.of("财政", "国债", "专项债", "特别国债", "减税", "降费", "退税", "财政补贴", "预算");

    private ImpactChainTemplates() {}

    /** 全部模板键（9 事件类型键 + 3 宏观细分键 = 12 类 ≥10）。 */
    public static List<String> templateKeys() {
        List<String> keys = new ArrayList<>();
        for (EventType type : EventType.values()) {
            keys.add(type.name());
        }
        keys.add(KEY_POLICY_MONETARY);
        keys.add(KEY_POLICY_FISCAL);
        keys.add(KEY_POLICY_INDUSTRIAL);
        return List.copyOf(keys);
    }

    /** 模板冻结行业集（宏观细分 = 传导框架行业；事件类型键 = 默认行业兜底集）。 */
    public static List<String> templateIndustries(String key) {
        if (KEY_POLICY_MONETARY.equals(key)) {
            return industriesOf(MONETARY_ENTRIES);
        }
        if (KEY_POLICY_FISCAL.equals(key)) {
            return industriesOf(FISCAL_ENTRIES);
        }
        if (KEY_POLICY_INDUSTRIAL.equals(key)) {
            return INDUSTRIAL_DEFAULTS;
        }
        EventType type = EventType.fromName(key);
        if (type == null) {
            throw new IllegalArgumentException("未知模板键: " + key);
        }
        return TYPE_DEFAULTS.getOrDefault(type, List.of("综合"));
    }

    /** 政策发布宏观细分判读：货币关键词 → 财政关键词 → 其余产业。 */
    public static MacroKind macroKindOf(String summary, String quote) {
        String text = (summary == null ? "" : summary) + (quote == null ? "" : quote);
        if (containsAny(text, MONETARY_KEYWORDS)) {
            return MacroKind.MONETARY;
        }
        if (containsAny(text, FISCAL_KEYWORDS)) {
            return MacroKind.FISCAL;
        }
        return MacroKind.INDUSTRIAL;
    }

    /** 渲染单事件影响链（纯规则）：政策发布走宏观细分框架（事件行业并入），公司类事件以 {@code event_item} 行业为主传导集。 */
    public static List<ChainOutput> render(EventItem event) {
        if (event.getEventType() == EventType.POLICY_RELEASE) {
            MacroKind kind = macroKindOf(event.getSummary(), event.getQuote());
            return switch (kind) {
                case MONETARY -> renderMacro(KEY_POLICY_MONETARY, MONETARY_ENTRIES, event);
                case FISCAL -> renderMacro(KEY_POLICY_FISCAL, FISCAL_ENTRIES, event);
                case INDUSTRIAL -> renderIndustrial(event);
            };
        }
        return renderCompanyType(event);
    }

    // ---- 渲染分支 ----

    /** 宏观细分渲染：传导框架行业 + 事件提取行业并入（去重），方向 = 事件方向。 */
    private static List<ChainOutput> renderMacro(
            String templateKey, List<MacroEntry> entries, EventItem event) {
        LinkedHashSet<String> covered = new LinkedHashSet<>();
        List<ChainOutput> chains = new ArrayList<>();
        for (MacroEntry entry : entries) {
            chains.add(
                    new ChainOutput(
                            templateKey,
                            entry.industry(),
                            event.getDirection(),
                            entry.logicOf(event.getDirection())));
            covered.add(entry.industry());
        }
        String kindWord =
                templateKey.equals(KEY_POLICY_MONETARY)
                        ? "货币政策"
                        : templateKey.equals(KEY_POLICY_FISCAL) ? "财政政策" : "产业政策";
        for (String industry : primaryIndustries(event)) {
            if (covered.add(industry)) {
                chains.add(
                        new ChainOutput(
                                templateKey,
                                industry,
                                event.getDirection(),
                                kindWord
                                        + "转向（"
                                        + directionWord(event.getDirection())
                                        + "），"
                                        + industry
                                        + "行业景气与投资预期相应变化"));
            }
        }
        return chains;
    }

    /** 产业政策渲染：事件行业为主传导集（缺省回落装备制造三类的中性观察）。 */
    private static List<ChainOutput> renderIndustrial(EventItem event) {
        LinkedHashSet<String> industries = primaryIndustries(event);
        List<ChainOutput> chains = new ArrayList<>();
        if (industries.isEmpty()) {
            for (String industry : INDUSTRIAL_DEFAULTS) {
                chains.add(
                        new ChainOutput(
                                KEY_POLICY_INDUSTRIAL,
                                industry,
                                Direction.NEUTRAL,
                                "产业政策支持方向待明确，" + industry + "行业景气影响中性观察"));
            }
            return chains;
        }
        for (String industry : industries) {
            chains.add(
                    new ChainOutput(
                            KEY_POLICY_INDUSTRIAL,
                            industry,
                            event.getDirection(),
                            "产业政策转向（"
                                    + directionWord(event.getDirection())
                                    + "），带动"
                                    + industry
                                    + "行业景气与投资预期"));
        }
        return chains;
    }

    /** 公司类渲染：事件行业（affected ∪ subjects）主传导集；双空回落类型默认行业（中性观察）。 */
    private static List<ChainOutput> renderCompanyType(EventItem event) {
        String templateKey = event.getEventType().name();
        LinkedHashSet<String> industries = primaryIndustries(event);
        List<ChainOutput> chains = new ArrayList<>();
        if (industries.isEmpty()) {
            for (String industry :
                    TYPE_DEFAULTS.getOrDefault(event.getEventType(), List.of("综合"))) {
                chains.add(
                        new ChainOutput(
                                templateKey,
                                industry,
                                Direction.NEUTRAL,
                                event.getEventType().displayName()
                                        + "事件行业信息缺位，"
                                        + industry
                                        + "影响中性观察（类型默认传导框架）"));
            }
            return chains;
        }
        for (String industry : industries) {
            chains.add(
                    new ChainOutput(
                            templateKey,
                            industry,
                            event.getDirection(),
                            companyLogic(event.getEventType(), industry, event.getDirection())));
        }
        return chains;
    }

    /** 事件结构化行业集（affected_industries ∪ subjects.industry，申万白名单过滤 + 保序去重）。 */
    private static LinkedHashSet<String> primaryIndustries(EventItem event) {
        LinkedHashSet<String> industries = new LinkedHashSet<>();
        for (String industry : event.getAffectedIndustries()) {
            if (IndustryCategory.isSwIndustry(industry)) {
                industries.add(industry);
            }
        }
        for (EventItem.SubjectRef subject : event.getSubjects()) {
            if (subject.industry() != null && IndustryCategory.isSwIndustry(subject.industry())) {
                industries.add(subject.industry());
            }
        }
        return industries;
    }

    /** 公司类逻辑链模板文本（类型 × 行业 × 方向词）。 */
    private static String companyLogic(EventType type, String industry, Direction direction) {
        String word = directionWord(direction);
        return switch (type) {
            case EARNINGS_FORECAST -> "业绩指引" + word + "，" + industry + "板块盈利预期相应调整";
            case MA_MERGER -> "并购重组落地改变" + industry + "竞争格局，整合预期" + word;
            case BUYBACK_CHANGE -> "回购/增减持信号" + word + "，" + industry + "板块资金面与信心预期相应变化";
            case MAJOR_CONTRACT -> "重大合同中标增厚" + industry + "订单预期（" + word + "）";
            case REGULATORY_PENALTY -> "监管处罚约束" + industry + "相关主体经营预期（" + word + "）";
            case EXEC_CHANGE -> "高管变动带来" + industry + "治理与战略连续性观察（" + word + "）";
            case TECH_BREAKTHROUGH -> "技术突破/产品发布提升" + industry + "景气预期（" + word + "）";
            case POLICY_RELEASE, OTHER -> "事件对" + industry + "影响为" + word + "（默认传导框架）";
        };
    }

    private static String directionWord(Direction direction) {
        return switch (direction) {
            case BULLISH -> "利好";
            case BEARISH -> "利空";
            case NEUTRAL -> "中性";
        };
    }

    private static List<String> industriesOf(List<MacroEntry> entries) {
        return entries.stream().map(MacroEntry::industry).toList();
    }

    private static Map<EventType, List<String>> buildTypeDefaults(
            Map<EventType, List<String>> raw) {
        Map<EventType, List<String>> defaults = new LinkedHashMap<>();
        for (EventType type : EventType.values()) {
            defaults.put(type, raw.getOrDefault(type, List.of("综合")));
        }
        return Map.copyOf(defaults);
    }

    private static boolean containsAny(String text, Set<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
