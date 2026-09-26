package com.info.platform.infrastructure.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 格隆汇快讯 Nuxt payload 预置适配器（M18 T152 竞争席〔第 8 席〕，REQ-20260926-15 拍板二四验全过，bean 名 {@code
 * gelonghuiLiveAdapter} 与目录种子一致）。
 *
 * <p>解析口径（2026-09-26 预检实测留档，ADR-0055）：端点 {@code /live/} SSR 页（372KB）内嵌 Nuxt 2 payload—— {@code
 * window.__NUXT__=(function(a,b,...){return {...}}(实参表));}：<b>IIFE 参数绑定</b>（快讯时间戳被去重为尾参表变量引用），
 * 解析三步： ①取形参表与尾参表建立 变量→字面量 绑定（实测 25/25 可解）；②条目 {@code
 * {id,title:"...",createTimestamp:引用,...,route:"直链"}} 逐条抽取（route 含 {@code \u002F}
 * 转义还原）；③createTimestamp 引用经绑定表解为 epoch <b>秒</b>（{@code new Date(...)} 形态容错按毫秒）。
 *
 * <p>增量口径：externalId = 快讯 id（数值递减 newest-first）；cursorType=NONE——payload 编辑序含轮换块，重复轮由 (source_id,
 * external_id) 唯一索引幂等吸收（裁量沿 ADR-0044）。robots：gelonghui robots 404 → 按 RFC 9309「无 robots 文件 = 无限制」。
 * 频控 15min（REQ 竞争席频段下限，快讯密度较高）。结构稳定性跨时段复核由 7 天观察期承载（预检为同窗三次采样实证）。
 */
@Component(GelonghuiLiveAdapter.BEAN_NAME)
public class GelonghuiLiveAdapter extends AbstractHtmlListFeedAdapter {

    /** 目录 {@code adapter_ref} 引用的 bean 名（InfoSourceCatalog GELONGHUI_LIVE）。 */
    public static final String BEAN_NAME = "gelonghuiLiveAdapter";

    /** payload 起始标记（Nuxt 2 SSR 内嵌）。 */
    private static final String NUXT_MARKER = "window.__NUXT__=";

    /** IIFE 形参表：window.__NUXT__=(function(a,b,...){return 。 */
    private static final Pattern IIFE_PARAMS =
            Pattern.compile(Pattern.quote(NUXT_MARKER) + "\\(function\\(([^)]*)\\)\\{return");

    /** 快讯条目起始：{id:数值,title:"字面量",createTimestamp:变量引用或 new Date(毫秒)。 */
    private static final Pattern ITEM_START =
            Pattern.compile(
                    "\\{id:(\\d+),title:\"((?:[^\"\\\\]|\\\\.)*)\",createTimestamp:"
                            + "([A-Za-z]+|new Date\\([^)]*\\))");

    /** 条目直链字段：route:"字面量"（\u002F 转义经 Jackson 还原）。 */
    private static final Pattern ROUTE_FIELD = Pattern.compile("route:\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static final ObjectMapper JSON_STRING_READER = new ObjectMapper();

    /** 生产装配（5s 超时 + 浏览器 UA/Referer，礼貌抓取）。 */
    @Autowired
    public GelonghuiLiveAdapter(RestClient.Builder builder, Clock clock) {
        super(builder.requestFactory(requestFactory()).build(), clock);
    }

    /** 全参构造（单测注入受控 RestClient 与时钟）。 */
    GelonghuiLiveAdapter(RestClient restClient, Clock clock) {
        super(restClient, clock);
    }

    @Override
    String referer() {
        return "https://www.gelonghui.com/";
    }

    /** 解析 /live/ 页 Nuxt payload（包内可见，fixture 单测直调零外呼）。 */
    @Override
    List<RawFeedItem> parseList(String body, InfoSource source) {
        int payloadAt = body.indexOf(NUXT_MARKER);
        if (payloadAt < 0) {
            throw new FeedFetchException(
                    "格隆汇结构漂移（window.__NUXT__ payload 缺失）: " + source.getSourceCode());
        }
        String payload = body.substring(payloadAt);
        Map<String, String> bindings = iifeBindings(payload);
        Matcher start = ITEM_START.matcher(payload);
        List<String[]> found = new ArrayList<>();
        List<Integer> startsAt = new ArrayList<>();
        while (start.find()) {
            found.add(new String[] {start.group(1), start.group(2), start.group(3)});
            startsAt.add(start.start());
        }
        List<RawFeedItem> items = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            // 条目私有区间 = 本条起始之后到下一 {id,title 起始（末条到文尾），route 首现即本条直链
            int regionFrom = startsAt.get(i);
            int regionTo = i + 1 < startsAt.size() ? startsAt.get(i + 1) : payload.length();
            RawFeedItem item = toItem(found.get(i), payload, regionFrom, regionTo, bindings);
            if (item != null) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            throw new FeedFetchException(
                    "格隆汇结构漂移（快讯条目 {id,title,createTimestamp} 缺失）: " + source.getSourceCode());
        }
        return dedupeByExternalId(items);
    }

    /** 形参表 × 尾参表（最后一个 {@code }(...)} 调用闭合）→ 变量绑定（顶层逗号切分，双引号内逗号不切）。 */
    private static Map<String, String> iifeBindings(String payload) {
        Map<String, String> bindings = new HashMap<>();
        Matcher params = IIFE_PARAMS.matcher(payload);
        if (!params.find()) {
            return bindings;
        }
        String callOpen = payload.substring(params.end());
        int argsAt = callOpen.lastIndexOf("}(");
        if (argsAt < 0) {
            return bindings;
        }
        String argsRaw = callOpen.substring(argsAt + 2, callOpen.lastIndexOf(')'));
        String[] names = params.group(1).split(",");
        List<String> values = splitTopLevel(argsRaw);
        if (names.length != values.size()) {
            return bindings;
        }
        for (int i = 0; i < names.length; i++) {
            bindings.put(names[i].trim(), values.get(i).trim());
        }
        return bindings;
    }

    /** 顶层逗号切分（双引号内逗号不切；payload 字面量不含转义引号包裹的逗号歧义——绑定值均为标量）。 */
    private static List<String> splitTopLevel(String raw) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        for (char ch : raw.toCharArray()) {
            if (ch == '"') {
                inQuote = !inQuote;
            }
            if (ch == ',' && !inQuote) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    /** 单条转换：id/标题字面量还原 + createTimestamp 绑定解析（epoch 秒，new Date 容错毫秒）+ route 直链还原。 */
    private RawFeedItem toItem(
            String[] fields,
            String payload,
            int regionFrom,
            int regionTo,
            Map<String, String> bindings) {
        String title = cleanTitle(unescape(fields[1]));
        if (title == null) {
            return null;
        }
        Instant publishedAt = resolveTimestamp(fields[2], bindings);
        String url = null;
        Matcher route = ROUTE_FIELD.matcher(payload).region(regionFrom, regionTo);
        if (route.find()) {
            url = unescape(route.group(1));
        }
        return new RawFeedItem(fields[0], title, null, url, "格隆汇", publishedAt, null);
    }

    /** 时间戳解析：变量引用 → 绑定值；epoch 秒数值直转，new Date(毫秒) 容错；不可解返回 null（回落摄取时刻）。 */
    private static Instant resolveTimestamp(String tsRef, Map<String, String> bindings) {
        String literal =
                tsRef.startsWith("new Date(") ? tsRef : bindings.getOrDefault(tsRef, tsRef);
        Matcher digits = Pattern.compile("\\d+").matcher(literal);
        if (!digits.find()) {
            return null;
        }
        long value = Long.parseLong(digits.group());
        return literal.startsWith("new Date(")
                ? Instant.ofEpochMilli(value)
                : Instant.ofEpochSecond(value);
    }

    /** JS 字符串字面量 → Java 文本（Jackson 处理 unicode 码点/斜杠等转义；非标转义回落原文，单条降级不废整轮）。 */
    private static String unescape(String raw) {
        try {
            return JSON_STRING_READER.readValue("\"" + raw + "\"", String.class);
        } catch (Exception e) {
            return raw;
        }
    }
}
