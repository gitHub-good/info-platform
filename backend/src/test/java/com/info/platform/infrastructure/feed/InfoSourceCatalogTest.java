package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.application.feed.SourceConfigValidator;
import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.InfoSource;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * InfoSourceCatalog 单测（T100，方案 §3.4/§4.6）：M13 试点三源齐备（覆盖 rss/json_api/preset 三通道）、source_code 唯一、
 * 预置参数合法 （种子行能过实体构造与 config 校验——预置目录即白名单合规闸）。
 */
class InfoSourceCatalogTest {

    private final SourceConfigCodec codec = new SourceConfigCodec();
    private final SourceConfigValidator validator = new SourceConfigValidator();

    @Test
    void presets_containsThreePilotChannels_coveringAllAdapterTypes() {
        var codes =
                InfoSourceCatalog.presets().stream()
                        .map(InfoSourceCatalog.PresetEntry::sourceCode)
                        .toList();

        // M13 试点三源 + M14 批次一十源 + M17 T140~T142 批次二九源 + M18 T150~T152 批次三九源与示例包 ×2
        // + V2.3-M23 T200 gov_policy（第 34 预置源，种子顺序即展示顺序）
        assertThat(codes)
                .containsExactly(
                        "mw_topstories",
                        "jin10_flash",
                        "sina_zhibo_7x24",
                        "em_fastnews_7x24",
                        "ths_push_stock",
                        "thepaper_hotnews",
                        "em_macro_indicators",
                        "ndrc_policy",
                        "csrc_news",
                        "stats_release",
                        "stcn_news",
                        "yicai_news",
                        "jingji21_finance",
                        "miit_policy",
                        "mof_policy",
                        "em_headlines",
                        "jiemian_finance",
                        "cnstock_news",
                        "cs_news",
                        "people_finance",
                        "nasdaq_markets",
                        "wsj_markets",
                        "netease_money",
                        "ifeng_finance",
                        "jrj_home",
                        "nbd_news",
                        "cnfin_flash",
                        "cctv_economy",
                        "em_finance_column",
                        "gelonghui_live",
                        "ce_news",
                        "gov_policy",
                        "example_wsj_world",
                        "example_ithome");
        assertThat(
                        InfoSourceCatalog.presets().stream()
                                .map(InfoSourceCatalog.PresetEntry::adapterType))
                .contains(AdapterType.RSS, AdapterType.JSON_API, AdapterType.PRESET);
    }

    @Test
    void presets_containsM14BatchOneJsonSources_thirteenPresetsTotal() {
        // M14 T110 一级 JSON 四源 + T111/T112 官方报纸六源入目录（REQ 累计 13 源口径）；M17 批二 + M18 批三后目录共 33 行
        var codes =
                InfoSourceCatalog.presets().stream()
                        .map(InfoSourceCatalog.PresetEntry::sourceCode)
                        .toList();

        assertThat(codes)
                .contains(
                        "em_fastnews_7x24",
                        "ths_push_stock",
                        "thepaper_hotnews",
                        "em_macro_indicators",
                        "ndrc_policy",
                        "csrc_news",
                        "stats_release",
                        "stcn_news",
                        "yicai_news",
                        "jingji21_finance",
                        "miit_policy",
                        "mof_policy",
                        "em_headlines",
                        "jiemian_finance",
                        "cnstock_news",
                        "cs_news",
                        "people_finance",
                        "nasdaq_markets",
                        "wsj_markets");
        // 34 = 33 存量 + V2.3-M23 gov_policy（第 5 个政策源；示例包 ×2 默认停用不计口径）
        assertThat(codes).hasSize(34);
    }

    @Test
    void presets_containsGovPolicy_jsonApiChannelConfigPerDesign() {
        // V2.3-M23 T200（ADR-0062 裁决一）：gov.cn 最新政策静态 JSON 走 JSON_API 通道纯目录配置——
        // 零新 adapter；TITLE/URL/DOCRELPUBTIME 白名单映射、externalId=URL（source_url 去重语义迁移，
        // (source_id, external_id) 唯一索引吸收）、纯日期经 to_iso_datetime 归一、cursorType=NONE
        // （URL 非单调，ndrc_policy 同款先例）、maxItems=50、UA+Referer 礼貌头、60min 官方频段上限
        var govPolicy =
                InfoSourceCatalog.presets().stream()
                        .filter(p -> p.sourceCode().equals("gov_policy"))
                        .findFirst()
                        .orElseThrow();
        assertThat(govPolicy.name()).isEqualTo("中国政府网·政策");
        assertThat(govPolicy.category()).isEqualTo("政策");
        assertThat(govPolicy.adapterType()).isEqualTo(AdapterType.JSON_API);
        assertThat(govPolicy.endpoint())
                .isEqualTo("https://www.gov.cn/zhengce/zuixin/ZUIXINZHENGCE.json");
        assertThat(govPolicy.intervalMinutes()).isEqualTo(60);
        assertThat(govPolicy.defaultEnabled()).isTrue();

        var config = codec.parse(govPolicy.configJson());
        assertThat(config.effectiveCursorType().name()).isEqualTo("NONE");
        assertThat(config.maxItems()).isEqualTo(50);
        assertThat(config.headers()).containsKeys("User-Agent", "Referer");
        assertThat(config.headers().get("Referer")).isEqualTo("https://www.gov.cn/");
        // 映射四条：TITLE→title / URL→url+externalId（同源去重锚）/ DOCRELPUBTIME→publishedAt（纯日期分支）
        assertThat(config.mappings())
                .containsExactlyInAnyOrder(
                        new com.info.platform.domain.feed.SourceConfig.ItemMapping(
                                "TITLE", "title", "to_string"),
                        new com.info.platform.domain.feed.SourceConfig.ItemMapping(
                                "URL", "url", "to_string"),
                        new com.info.platform.domain.feed.SourceConfig.ItemMapping(
                                "URL", "externalId", "to_string"),
                        new com.info.platform.domain.feed.SourceConfig.ItemMapping(
                                "DOCRELPUBTIME", "publishedAt", "to_iso_datetime"));
    }

    @Test
    void presets_containsM18BatchThreeSources_channelsAndIntervalsPerReq() {
        // M18 批次三（REQ-20260926-15 拍板一 + T153 预检终局，ADR-0055）：门户 3 preset + 媒体 2 preset/1 json_api +
        // 纵深席 json_api（东财 column=352）+ 竞争席 preset（格隆汇 payload）+ 条件席 preset（中国经济网）；
        // 频控全部 15~30min（REQ 门户/媒体频段 + 间隔 ≥2min 红线）；金融界 www 根无参数路径合规注记
        var byCode =
                InfoSourceCatalog.presets().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        InfoSourceCatalog.PresetEntry::sourceCode, p -> p));
        assertThat(byCode)
                .containsKeys(
                        "netease_money",
                        "ifeng_finance",
                        "jrj_home",
                        "nbd_news",
                        "cnfin_flash",
                        "cctv_economy",
                        "em_finance_column",
                        "gelonghui_live",
                        "ce_news");
        for (String code :
                new String[] {
                    "netease_money",
                    "ifeng_finance",
                    "jrj_home",
                    "nbd_news",
                    "cnfin_flash",
                    "gelonghui_live",
                    "ce_news"
                }) {
            assertThat(byCode.get(code).adapterType())
                    .as("%s 通道", code)
                    .isEqualTo(AdapterType.PRESET);
            assertThat(byCode.get(code).adapterRef()).as("%s adapter_ref", code).isNotBlank();
        }
        // 央视走 jsonp 数据端点（首页客户端渲染，沿工信部先例）；纵深席东财 column=352 同宿主同构
        assertThat(byCode.get("cctv_economy").adapterType()).isEqualTo(AdapterType.JSON_API);
        assertThat(byCode.get("cctv_economy").endpoint())
                .contains("cmsdatainterface/page/economy_zixun_1.jsonp");
        assertThat(byCode.get("cctv_economy").intervalMinutes()).isEqualTo(15);
        assertThat(byCode.get("em_finance_column").adapterType()).isEqualTo(AdapterType.JSON_API);
        assertThat(byCode.get("em_finance_column").endpoint()).contains("column=352");
        assertThat(byCode.get("em_finance_column").intervalMinutes()).isEqualTo(15);
        // 东财纵深席沿 em_headlines 的 code 数值 ID 游标口径
        assertThat(
                        codec.parse(byCode.get("em_finance_column").configJson())
                                .effectiveCursorType()
                                .name())
                .isEqualTo("ID");
        // 九源频控落 REQ 门户/媒体频段（15~30min），全部 ≥2min 礼貌红线
        for (String code :
                new String[] {
                    "netease_money",
                    "ifeng_finance",
                    "jrj_home",
                    "nbd_news",
                    "cnfin_flash",
                    "cctv_economy",
                    "em_finance_column",
                    "gelonghui_live",
                    "ce_news"
                }) {
            assertThat(byCode.get(code).intervalMinutes()).as("%s 频控", code).isBetween(15, 30);
        }
        // 金融界端点为无参数 www 根（robots 合规注记：仅禁搜索/翻页参数路径）
        assertThat(byCode.get("jrj_home").endpoint()).isEqualTo("https://www.jrj.com.cn/");
        assertThat(byCode.get("jrj_home").endpoint()).doesNotContain("?");
    }

    @Test
    void presets_exampleRssPacks_defaultDisabledNotCounted() {
        // 通用 RSS 示例包 ×2（REQ 条目 6，默认停用播种不计 30 口径）：rss 通道 + TIME 游标 + defaultEnabled=false
        var byCode =
                InfoSourceCatalog.presets().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        InfoSourceCatalog.PresetEntry::sourceCode, p -> p));
        assertThat(byCode).containsKeys("example_wsj_world", "example_ithome");
        for (String code : new String[] {"example_wsj_world", "example_ithome"}) {
            var entry = byCode.get(code);
            assertThat(entry.adapterType()).as("%s 通道", code).isEqualTo(AdapterType.RSS);
            assertThat(entry.defaultEnabled()).as("%s 默认停用", code).isFalse();
            assertThat(codec.parse(entry.configJson()).effectiveCursorType().name())
                    .as("%s 游标", code)
                    .isEqualTo("TIME");
            assertThat(entry.intervalMinutes()).as("%s 频控", code).isBetween(15, 60);
        }
        // 其余 32 行（22 现役目录 + 批次三 9 席 + V2.3 gov_policy）默认启用
        long enabled =
                InfoSourceCatalog.presets().stream()
                        .filter(InfoSourceCatalog.PresetEntry::defaultEnabled)
                        .count();
        assertThat(enabled).isEqualTo(32);
    }

    @Test
    void presets_containsM17BatchTwoSources_channelsAndIntervalsPerReq() {
        // M17 批次二源（REQ-20260926-14 拍板一）通道与频控落位断言：preset（HTML/检索 API 适配在代码内）+ json_api + rss；
        // 频控官方 60min、东财要闻 15min
        var byCode =
                InfoSourceCatalog.presets().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        InfoSourceCatalog.PresetEntry::sourceCode, p -> p));
        assertThat(byCode)
                .containsKeys(
                        "miit_policy",
                        "mof_policy",
                        "em_headlines",
                        "jiemian_finance",
                        "cnstock_news",
                        "cs_news",
                        "people_finance",
                        "nasdaq_markets",
                        "wsj_markets");
        assertThat(byCode.get("em_headlines").adapterType()).isEqualTo(AdapterType.JSON_API);
        assertThat(byCode.get("nasdaq_markets").adapterType()).isEqualTo(AdapterType.RSS);
        assertThat(byCode.get("wsj_markets").adapterType()).isEqualTo(AdapterType.RSS);
        for (String code :
                new String[] {
                    "miit_policy",
                    "mof_policy",
                    "jiemian_finance",
                    "cnstock_news",
                    "cs_news",
                    "people_finance"
                }) {
            assertThat(byCode.get(code).adapterType())
                    .as("%s 通道", code)
                    .isEqualTo(AdapterType.PRESET);
            assertThat(byCode.get(code).adapterRef()).as("%s adapter_ref", code).isNotBlank();
            assertThat(codec.parse(byCode.get(code).configJson()).effectiveCursorType().name())
                    .as("%s cursorType", code)
                    .isEqualTo("NONE");
        }
        assertThat(byCode.get("miit_policy").intervalMinutes()).isEqualTo(60);
        assertThat(byCode.get("mof_policy").intervalMinutes()).isEqualTo(60);
        assertThat(byCode.get("em_headlines").intervalMinutes()).isEqualTo(15);
        assertThat(byCode.get("jiemian_finance").intervalMinutes()).isEqualTo(30);
        assertThat(byCode.get("cnstock_news").intervalMinutes()).isEqualTo(30);
        assertThat(byCode.get("cs_news").intervalMinutes()).isEqualTo(30);
        assertThat(byCode.get("people_finance").intervalMinutes())
                .as("人民网 Crawl-delay 120s 的 30 倍裕量")
                .isEqualTo(60);
        assertThat(byCode.get("nasdaq_markets").intervalMinutes()).isEqualTo(30);
        assertThat(byCode.get("wsj_markets").intervalMinutes()).isEqualTo(30);
        // 国际 RSS 两源沿用 MarketWatch TIME 游标先例（T106）
        assertThat(
                        codec.parse(byCode.get("nasdaq_markets").configJson())
                                .effectiveCursorType()
                                .name())
                .isEqualTo("TIME");
        assertThat(codec.parse(byCode.get("wsj_markets").configJson()).effectiveCursorType().name())
                .isEqualTo("TIME");
    }

    @Test
    void presets_containsM14BatchOneHtmlSources_presetChannelCursorNone() {
        // T111/T112 六源：全部 preset 通道（HTML 列表适配在代码内）+ cursorType=NONE（增量靠唯一索引，
        // 日期粒度过粗/列表乱序/相对时间的裁量见 ADR-0044）+ 频控落 REQ 频段（官方 60min、报纸 15~30min）；
        // M18 批次三 preset 七源（门户 3/媒体 2/格隆汇 payload/中国经济网）追加于后
        var htmlCodes =
                InfoSourceCatalog.presets().stream()
                        .filter(p -> p.adapterType() == AdapterType.PRESET)
                        .toList();
        assertThat(htmlCodes)
                .extracting(InfoSourceCatalog.PresetEntry::sourceCode)
                .containsExactly(
                        "sina_zhibo_7x24",
                        "em_macro_indicators",
                        "ndrc_policy",
                        "csrc_news",
                        "stats_release",
                        "stcn_news",
                        "yicai_news",
                        "jingji21_finance",
                        "miit_policy",
                        "mof_policy",
                        "jiemian_finance",
                        "cnstock_news",
                        "cs_news",
                        "people_finance",
                        "netease_money",
                        "ifeng_finance",
                        "jrj_home",
                        "nbd_news",
                        "cnfin_flash",
                        "gelonghui_live",
                        "ce_news");
        for (String code :
                new String[] {
                    "ndrc_policy",
                    "csrc_news",
                    "stats_release",
                    "stcn_news",
                    "yicai_news",
                    "jingji21_finance",
                    "miit_policy",
                    "mof_policy",
                    "jiemian_finance",
                    "cnstock_news",
                    "cs_news",
                    "people_finance"
                }) {
            var entry =
                    htmlCodes.stream()
                            .filter(p -> p.sourceCode().equals(code))
                            .findFirst()
                            .orElseThrow();
            var config = codec.parse(entry.configJson());
            assertThat(config.effectiveCursorType().name())
                    .as("%s cursorType", code)
                    .isEqualTo("NONE");
            assertThat(entry.adapterRef()).as("%s adapter_ref", code).isNotBlank();
            int interval = entry.intervalMinutes();
            if (code.endsWith("_policy")
                    || code.equals("csrc_news")
                    || code.equals("stats_release")) {
                assertThat(interval).as("%s 官方源频控 30~60", code).isBetween(30, 60);
            } else if (code.equals("people_finance")) {
                // 人民网 Crawl-delay 120s 合规裕量：60min（30 倍，REQ 场景 6）
                assertThat(interval).as("%s 频控（Crawl-delay 裕量）", code).isEqualTo(60);
            } else {
                assertThat(interval).as("%s 报纸源频控 15~30", code).isBetween(15, 30);
            }
        }
    }

    @Test
    void presets_sourceCodesUnique() {
        Set<String> seen = new HashSet<>();
        for (InfoSourceCatalog.PresetEntry entry : InfoSourceCatalog.presets()) {
            assertThat(seen.add(entry.sourceCode()))
                    .as("source_code 重复: %s", entry.sourceCode())
                    .isTrue();
        }
    }

    @Test
    void presets_entriesConstructValidEntities_andPassConfigValidation() {
        for (InfoSourceCatalog.PresetEntry entry : InfoSourceCatalog.presets()) {
            InfoSource source =
                    InfoSource.create(
                            entry.sourceCode(),
                            entry.name(),
                            entry.category(),
                            entry.adapterType(),
                            entry.adapterRef(),
                            entry.endpoint(),
                            codec.parse(entry.configJson()),
                            entry.intervalMinutes(),
                            true,
                            true);
            // 预置源走 common 校验（preset 类型仅 create 受限）；配置漂移在此红灯
            validator.validateCommon(source);
            assertThat(source.isPreset()).isTrue();
            assertThat(source.isEnabled()).isTrue();
        }
    }

    @Test
    void presets_jin10ConfigCarriesStripPrefixAndIdCursor() {
        var jin10 =
                InfoSourceCatalog.presets().stream()
                        .filter(p -> p.sourceCode().equals("jin10_flash"))
                        .findFirst()
                        .orElseThrow();
        var config = codec.parse(jin10.configJson());

        assertThat(config.stripPrefix()).isEqualTo("var newest=");
        assertThat(config.stripSuffix()).isEqualTo(";");
        assertThat(config.effectiveCursorType().name()).isEqualTo("ID");
        assertThat(config.mappings()).isNotEmpty();
        assertThat(config.headers()).containsKey("User-Agent");
    }
}
