package com.info.platform.application.aggregation;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.SubjectType;
import java.util.Map;

/**
 * 标的池同步的市场桶定义（T50，对齐技术方案增补 §4.2 桶表）。
 *
 * <p>每桶一个 fs 选择器（东财 clist 的市场+类型过滤串），并绑定落库时的 {@link Market} / {@link SubjectType}——diff 集合按「市场 +
 * 类型」双条件圈定，指数桶与股票桶互不触碰，板块/基金/债券（若有手工行）天然不在同步范围内（范围外永不触碰）。
 *
 * <p>fs 权威定义提取自东财行情中心页面 JS（2026-09-24 实测勘定，ADR-0027）：
 *
 * <ul>
 *   <li>A 股：沪深主板+创业板+科创板（需求口径，不含北交所）
 *   <li>港股：主板 t:3 + 创业板 GEM t:4（任务建议的 t:1 实测为人民币柜台/房托桶，不采用）
 *   <li>指数：上证 t:1 + 深证 t:5（Should，T54 接入，本批仅预留桶定义）
 * </ul>
 */
public enum MarketSyncSpec {

    /** A 股股票桶（实测 total=5561 / 56 页）。 */
    A_SHARE_STOCK("m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23", Market.A_SHARE, SubjectType.STOCK),

    /** 港股股票桶：主板+创业板（实测 total=2927 / 30 页）。 */
    HK_STOCK("m:116+t:3,m:116+t:4", Market.HK, SubjectType.STOCK),

    /** 沪深指数桶（Should，T54 接入：开关 subject.sync.index-enabled + V17 种子对齐验证）。 */
    CN_INDEX("m:1+t:1,m:0+t:5", Market.INDEX, SubjectType.INDEX);

    /**
     * 东财 f13 市场码 → subject_code 前缀（技术方案增补 §4.2 字段映射表）：1=沪 / 0=深 / 116=港。
     *
     * <p>f13 恰为 secid 市场前缀本身（实测），故本映射同时服务于 secid 派生（{@code secid = f13 + "." + f12}）与 tushare
     * 后缀（{@code 代码.市场后缀}），无需第二张映射表。
     */
    private static final Map<Integer, String> CODE_PREFIX_BY_MARKET_FLAG =
            Map.of(1, "SH", 0, "SZ", 116, "HK");

    private final String fs;
    private final Market market;
    private final SubjectType subjectType;

    MarketSyncSpec(String fs, Market market, SubjectType subjectType) {
        this.fs = fs;
        this.market = market;
        this.subjectType = subjectType;
    }

    /** 东财 clist 的 fs 市场桶选择器（请求参数，透传 {@code EastMoneyListClient}）。 */
    public String fs() {
        return fs;
    }

    /** 落库 market 列值。 */
    public Market market() {
        return market;
    }

    /** 落库 subject_type 值。 */
    public SubjectType subjectType() {
        return subjectType;
    }

    /** f13 市场码 → subject_code 前缀（SH/SZ/HK）；未知码抛异常（防御：源口径漂移时放弃该市场而非错配前缀）。 */
    public static String codePrefixOf(int marketFlag) {
        String prefix = CODE_PREFIX_BY_MARKET_FLAG.get(marketFlag);
        if (prefix == null) {
            throw new IllegalArgumentException("未知东财市场码 f13: " + marketFlag);
        }
        return prefix;
    }
}
