package com.info.platform.infrastructure.feed;

import com.info.platform.domain.feed.AdapterType;
import java.util.List;

/**
 * 预置源目录（M13 T100，方案 §4.6/§3.4）：预置源<b>单一事实源</b>（对齐 SourceProviders/DataSourceDefaults 惯例）。
 *
 * <p>M13 种子三源覆盖全部三类适配通道（rss / json_api / preset）；M14+ 每批新增源 = 本目录加行， {@code InfoSourceSeeder}
 * seed-if-absent 补种（存量行不覆盖，DB 为权威）。目录即合规白名单：robots 禁抓/需签名/登录墙的源根本不入目录（普查 §6 红线案例集）。
 *
 * <p>合规预检留档（T106 复核）：MarketWatch robots 403 → RFC 9309 无 robots 即无限制（落地复核注记）；金十/新浪 7×24 无 robots。
 */
public final class InfoSourceCatalog {

    private InfoSourceCatalog() {}

    /** 预置源条目（configJson 与 {@code info_source.config} 线格式一致，结构见方案 §4.3）。 */
    public record PresetEntry(
            String sourceCode,
            String name,
            String category,
            AdapterType adapterType,
            String adapterRef,
            String endpoint,
            String configJson,
            int intervalMinutes) {}

    /** MarketWatch Top Stories：标准 RSS 2.0（普查 🟢 200/2576B 实测），TIME 游标，30min。 */
    private static final PresetEntry MARKETWATCH =
            new PresetEntry(
                    "mw_topstories",
                    "MarketWatch·头条",
                    "国际",
                    AdapterType.RSS,
                    null,
                    "https://feeds.content.dowjones.io/public/rss/mw_topstories",
                    """
                    {"cursorType":"TIME","cursorField":"publishedAt"}""",
                    30);

    /**
     * 金十数据快讯：JS 包装 JSON（{@code var newest=[...];}），ID 数值游标，5min。
     *
     * <p>字段口径经 2026-09-25 真实外呼复核（ADR-0042）：快讯正文嵌于 {@code data} 子对象（{@code data.title} 常空、{@code
     * data.content} 为正文——普查样本的顶层 {@code title/important_title} 不存在）；映射走点分导航 + 引擎标题回落（title 空以
     * summary 补位），中文快讯出题、英文快讯题文分立。
     */
    private static final PresetEntry JIN10_FLASH =
            new PresetEntry(
                    "jin10_flash",
                    "金十数据·快讯",
                    "快讯",
                    AdapterType.JSON_API,
                    null,
                    "https://www.jin10.com/flash_newest.js",
                    """
                    {"listPath":"","stripPrefix":"var newest=","stripSuffix":";",\
                    "itemMapping":[\
                    {"source":"id","target":"externalId","transform":"to_string"},\
                    {"source":"time","target":"publishedAt","transform":"to_iso_datetime"},\
                    {"source":"data.title","target":"title","transform":"to_string"},\
                    {"source":"data.content","target":"summary","transform":"strip_html"}],\
                    "headers":{"User-Agent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36","Referer":"https://www.jin10.com"},\
                    "cursorType":"ID","cursorField":"externalId"}""",
                    5);

    /**
     * 新浪财经 7×24：预置 adapter（richtext 清洗 + id 数值游标），5min；M14「升级新浪源」的前置通道。
     *
     * <p>字段口径经 2026-09-25 真实外呼复核（ADR-0042）：{@code rich_text}（普查记 richtext）/ {@code create_time}
     * 墙钟（普查记 ctime epoch）/{@code docurl}；解析全在 {@code SinaZhiboAdapter} 代码内。
     */
    private static final PresetEntry SINA_ZHIBO =
            new PresetEntry(
                    "sina_zhibo_7x24",
                    "新浪财经·7×24",
                    "快讯",
                    AdapterType.PRESET,
                    "sinaZhiboAdapter",
                    "https://zhibo.sina.com.cn/api/zhibo/feed?zhibo_id=152",
                    """
                    {"cursorType":"ID","cursorField":"externalId"}""",
                    5);

    /** 预置源清单（种子顺序即展示顺序；source_code 唯一由单测守护）。 */
    public static List<PresetEntry> presets() {
        return List.of(MARKETWATCH, JIN10_FLASH, SINA_ZHIBO);
    }
}
