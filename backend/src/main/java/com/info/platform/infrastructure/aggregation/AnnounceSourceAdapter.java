package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.infrastructure.common.CircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.ResilienceSpec;
import com.info.platform.infrastructure.common.SourceCache;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 东方财富公告源真实 adapter（T05）。
 *
 * <p>真实接入东财 {@code np-anotice-stock/api/security/ann}：按 {@code subject.external_codes} 派生的 6
 * 位证券代码取最新 N 条公告（标题/时间/分类/URL）， 经 {@link FieldMapper} 逐条字段映射后以 {@code data.items} 列表承载落 {@code
 * SourceResult.data}。 与 {@link MockAnnounceSourceAdapter} 互斥——{@code adapter.mock.enabled=false}
 * 时装配本类。
 *
 * <p>代码派生：公告 {@code stock_list} 参数用 6 位代码（如 {@code 600519}），非 secid。优先取 {@code eastmoney_code}
 * 键；缺省则从 {@code eastmoney} secid 按 {@code .} 切分派生（Spike-1 §5：secid 与纯代码可互相派生），兼容当前 V2 种子（仅存 secid）。
 * 两者皆缺 → MISSING（不发请求）。
 *
 * <p><b>列表型分区映射策略</b>（Spike-1 §4.4 末注）：{@link FieldMapper#map} 仅做单条 flat Map 映射，无法遍历列表或导航嵌套字段。 故本
 * adapter 在 {@link #doFetch} 内： ① 对每条原始公告，拍平嵌套字段（{@code codes[0].stock_code}/{@code
 * short_name}、{@code columns[0].column_name}）为 flat 原始 map； ② 用 {@link #itemMapping}（{@code
 * field-mapping/eastmoney-announce.json}）逐条映射（{@code notice_date}→{@code to_iso_date} 等）； ③ 由
 * {@code art_code} 拼装详情 URL（{@link EastMoneyAnnounceClient#detailUrlOf}，Spike-1 §4.4 url
 * 为「构造」项，非源字段映射）； ④ 收集为 {@code items} 列表包装进 {@link RawFetch#data}（{@code Map.of("items", ...)}）。
 *
 * <p>{@link #mappingConfig} 返回 {@code items→items} passthrough：模板层 {@link
 * AbstractSourceAdapter#fetch} 的 {@code fieldMapper.map(data, mappingConfig)} 作用在<b>整张</b> data
 * map（单层），对已规范化的 {@code items} 列表原样透传进 {@code SourceResult.data["items"]}。 应用层聚合服务从 {@code
 * data.items} 键提取列表（同 {@link MockAnnounceSourceAdapter} 契约）。
 *
 * <p>弹性：超时 2s、重试 0（{@code ResilienceSpec.noRetry(2s)}，见 ADR-0011）。 <b>取舍</b>：技术方案 §4.3 流程 1 原写公告「超时
 * 2s 重试 1」与 2s 页预算冲突； ResilienceRunner 不区分超时与连接失败，任一带重试方案（1s 重试 1 ≈ 2.2s / 2s 重试 1 ≈
 * 4s+）均突破预算。公告为幂等只读且非阻断分区，故以预算收敛优先，取 noRetry(2s)，最坏恰 2s、由聚合层 {@code orTimeout} 兜底。降级默认
 * MISSING——公告源挂不阻断详情页其他分区。
 *
 * <p>字段语义依据 Spike-1 §4.4 + 2026-09-21 curl 实测：{@code art_code}/{@code title}/{@code notice_date}
 * 为列表项顶层字段（实测确认）， {@code column_name}/{@code stock_code}/{@code short_name} 嵌在 {@code
 * columns[0]}/{@code codes[0]}（<b>与 §4.4 平铺假设不符</b>，本 adapter 按实测拍平）。
 */
@Component
@ConditionalOnProperty(name = "adapter.mock.enabled", havingValue = "false")
public class AnnounceSourceAdapter extends AbstractSourceAdapter {

    /** subject.external_codes 中东财 6 位代码的键名（优先取，Spike-1 §5 建议两者都存）。 */
    private static final String EASTMONEY_CODE_KEY = "eastmoney_code";

    /** subject.external_codes 中东财 secid 的键名（派生 6 位代码的回退来源，V2 种子用此键）。 */
    private static final String EASTMONEY_SECID_KEY = "eastmoney";

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    /**
     * 模板层 map 步骤用：整张 data map（{@code {"items":[...]}}）的 items 列表原样透传。 逐条字段映射在 {@link #doFetch} 内用
     * {@link #itemMapping} 完成。
     */
    private static final List<FieldMapping> ITEMS_PASSTHROUGH =
            List.of(new FieldMapping("items", "items", Transform.NONE));

    private final EastMoneyAnnounceClient client;
    private final FieldMapper fieldMapper;
    private final List<FieldMapping> itemMapping;

    public AnnounceSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            EastMoneyAnnounceClient client) {
        super(cache, fieldMapper, runner, breaker);
        this.client = client;
        // 模板层 fieldMapper 为 private，子类需另持引用以在 doFetch 内逐条映射（Spike-1 §4.4 列表场景）。
        this.fieldMapper = fieldMapper;
        this.itemMapping = fieldMapper.loadMapping("field-mapping/eastmoney-announce.json");
    }

    @Override
    public SourceCode sourceCode() {
        return SourceCode.ANNOUNCE;
    }

    @Override
    protected String sourceLabel() {
        return "东方财富公告";
    }

    @Override
    protected List<FieldMapping> mappingConfig() {
        return ITEMS_PASSTHROUGH;
    }

    @Override
    protected ResilienceSpec resilienceSpec() {
        return ResilienceSpec.noRetry(TIMEOUT);
    }

    @Override
    protected Optional<RawFetch> doFetch(Subject subject) throws Exception {
        String stockCode = resolveStockCode(subject);
        if (stockCode == null) {
            return Optional.empty();
        }
        Optional<List<Map<String, Object>>> rawList = client.fetchAnnouncements(stockCode);
        if (rawList.isEmpty()) {
            // 空列表（无公告/代码不存在）→ MISSING（成功调用，非异常）
            return Optional.empty();
        }
        List<Map<String, Object>> items = new ArrayList<>(rawList.get().size());
        for (Map<String, Object> raw : rawList.get()) {
            Map<String, Object> flat = flatten(raw);
            Map<String, Object> mapped = fieldMapper.map(flat, itemMapping);
            Map<String, Object> item = new LinkedHashMap<>(mapped);
            Object artCode = flat.get("art_code");
            if (artCode != null) {
                // url 为构造项（Spike-1 §4.4），非源字段映射，由 art_code 拼详情 PDF 直链。
                item.put("url", client.detailUrlOf(String.valueOf(artCode)));
            }
            items.add(Collections.unmodifiableMap(item));
        }
        Map<String, Object> data = Map.of("items", List.copyOf(items));
        return Optional.of(new RawFetch(data, sourceLabel(), Instant.now()));
    }

    /**
     * 拍平单条公告的嵌套字段为 flat 原始 map 供 {@link FieldMapper} 单层映射。
     *
     * <p>顶层取 {@code art_code}/{@code title}/{@code notice_date}（实测为列表项直接字段）； 嵌套取 {@code
     * codes[0].stock_code}/ {@code short_name}、{@code columns[0].column_name}（Spike-1 §4.4
     * 误列为顶层，实测嵌在数组）。 缺失/null 字段不入 flat（FieldMapper 白名单语义下不产出目标字段）。
     */
    private static Map<String, Object> flatten(Map<String, Object> raw) {
        Map<String, Object> flat = new LinkedHashMap<>();
        putIfPresent(flat, "art_code", raw.get("art_code"));
        putIfPresent(flat, "title", raw.get("title"));
        putIfPresent(flat, "notice_date", raw.get("notice_date"));
        Object codes = raw.get("codes");
        if (codes instanceof List<?> list
                && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> first) {
            putIfPresent(flat, "stock_code", first.get("stock_code"));
            putIfPresent(flat, "short_name", first.get("short_name"));
        }
        Object columns = raw.get("columns");
        if (columns instanceof List<?> list
                && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> first) {
            putIfPresent(flat, "column_name", first.get("column_name"));
        }
        return flat;
    }

    private static void putIfPresent(Map<String, Object> flat, String key, Object value) {
        if (value != null) {
            flat.put(key, value);
        }
    }

    /**
     * 取东财 6 位证券代码：优先 {@code eastmoney_code} 键；缺省从 {@code eastmoney} secid（如 {@code 1.600519}）按首个
     * {@code .} 切分派生为 {@code 600519}。 两者皆缺/空 → null（→ MISSING，不发 HTTP）。
     */
    private String resolveStockCode(Subject subject) {
        Map<String, String> externalCodes = subject.getExternalCodes();
        if (externalCodes == null) {
            return null;
        }
        String code = externalCodes.get(EASTMONEY_CODE_KEY);
        if (code != null && !code.isBlank()) {
            return code;
        }
        String secid = externalCodes.get(EASTMONEY_SECID_KEY);
        if (secid == null || secid.isBlank()) {
            return null;
        }
        int dot = secid.indexOf('.');
        return dot >= 0 ? secid.substring(dot + 1) : secid;
    }
}
