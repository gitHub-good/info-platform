package com.info.platform.application.aggregation;

import java.util.Map;
import java.util.Objects;

/**
 * 标的池同步的统一标的快照 DTO（T50，技术方案增补 §4.2/§4.8）。
 *
 * <p>由 {@code EastMoneyListClient} 按字段映射表从东财 clist 行构造：{@code subjectCode = 前缀 + f12}（沪 SH/深 SZ/港
 * HK， 前缀按 f13 市场码映射）、{@code secid = f13 + "." + f12}、{@code industry} 的 {@code "-"} 占位已置 null、name
 * 已 trim。 换源只动 Client 一层，本 DTO 不变（ADR-0027 可替换性）。
 *
 * @param subjectCode 内部统一代码（如 {@code SH600519} / {@code HK00700}，港股前导零保留）
 * @param name 标的名称（已 trim，非空）
 * @param industry 行业（东财分类中文；源占位 {@code "-"} / 空已归一为 null）
 * @param secid 东财取数键（如 {@code 1.600519} / {@code 116.00700}）
 * @param bucket 该快照所属的市场桶
 */
public record SubjectSnapshot(
        String subjectCode, String name, String industry, String secid, MarketSyncSpec bucket) {

    public SubjectSnapshot {
        Objects.requireNonNull(subjectCode, "subjectCode 必填");
        Objects.requireNonNull(name, "name 必填");
        Objects.requireNonNull(secid, "secid 必填");
        Objects.requireNonNull(bucket, "bucket 必填");
    }

    /**
     * external_codes 取数键集（INSERT 时一次性落，对齐 V2/V17 种子 JSON 形态）： {@code eastmoney} = secid、{@code
     * tushare} = 代码.市场后缀（如 {@code 600519.SH} / {@code 00700.HK}）。
     *
     * <p>行情/估值链路经既有 {@code QuoteSourceAdapter.EASTMONEY_SECID_KEY} 按键取数，同步新入库标的零改造即可取行情。
     */
    public Map<String, String> externalCodes() {
        int dot = secid.indexOf('.');
        if (dot <= 0 || dot == secid.length() - 1) {
            throw new IllegalStateException("secid 格式非法（应为 f13.f12）: " + secid);
        }
        String rawCode = secid.substring(dot + 1);
        String prefix = MarketSyncSpec.codePrefixOf(Integer.parseInt(secid.substring(0, dot)));
        return Map.of("eastmoney", secid, "tushare", rawCode + "." + prefix);
    }
}
