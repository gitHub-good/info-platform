package com.info.platform.application.mainline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.info.platform.application.mainline.AttentionSource.HolderChangeSummary;
import com.info.platform.application.mainline.AttentionSource.LhbSummary;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 主力关注度代理编排（M27 T244，方案 §4.4.4 + ADR-0063 裁决 5）：主线 Job 内对 Top3 龙头逐只取 datacenter 龙虎榜 30 日计数 +
 * 增减持净方向，结果内嵌 {@code leaders[].attention}（页面零外呼零缓存失效面——「当日缓存」等价形态）。
 *
 * <p><b>降级语义</b>：增强件非闭环件——单只失败 → 该卡徽章 {@code state=UNAVAILABLE}「暂无数据」不阻塞榜单（继续下一只）；两只报表独立 降级。礼貌间隔
 * 500ms 沿 datacenter 先例（≤15 只 × 2 = ≤30 请求/日）。
 */
@Service
public class AttentionProxyService {

    private static final Logger log = LoggerFactory.getLogger(AttentionProxyService.class);

    /** 徽章窗（30 日——方案 §4.4.4）。 */
    static final int ATTENTION_WINDOW_DAYS = 30;

    /** 相邻请求礼貌间隔（datacenter 先例 500ms）。 */
    private static final long DEFAULT_REQUEST_INTERVAL_MILLIS = 500L;

    private final AttentionSource attentionSource;

    private final ObjectMapper objectMapper;

    private final Clock clock;

    private final long requestIntervalMillis;

    /** Spring 装配构造（礼貌间隔 500ms——datacenter 先例；多构造显式指定装配入口）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public AttentionProxyService(
            @Qualifier("eastmoneyAttentionClient") AttentionSource attentionSource,
            ObjectMapper objectMapper,
            Clock clock) {
        this(attentionSource, objectMapper, clock, DEFAULT_REQUEST_INTERVAL_MILLIS);
    }

    /** 全参构造（纯构造单测指定间隔 0——免真实 sleep）。 */
    AttentionProxyService(
            AttentionSource attentionSource,
            ObjectMapper objectMapper,
            Clock clock,
            long requestIntervalMillis) {
        this.attentionSource = attentionSource;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.requestIntervalMillis = Math.max(0, requestIntervalMillis);
    }

    /**
     * 逐只补主力徽章（leaders JSON 元素的 {@code attention} 字段值；异常吞转为 UNAVAILABLE——增强件旁路不上抛）。
     *
     * @param codes 龙头代码集（Top3 × ≤5 行业 = ≤15 只）
     * @return 每只一个徽章 JSON 节点（与入参同序）
     */
    public List<ObjectNode> badgesFor(List<String> codes) {
        List<ObjectNode> badges = new ArrayList<>(codes.size());
        for (int i = 0; i < codes.size(); i++) {
            badges.add(badgeOf(codes.get(i)));
            if (i < codes.size() - 1) {
                sleepPolitely();
            }
        }
        return badges;
    }

    private ObjectNode badgeOf(String code) {
        ObjectNode badge = objectMapper.createObjectNode();
        String sinceDate =
                clock.instant()
                        .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .toLocalDate()
                        .minusDays(ATTENTION_WINDOW_DAYS)
                        .toString();
        String queryTime = clock.instant().toString();
        // 两报表独立尝试（一只失败不影响另一只的请求机会）；任一失败 → 整卡 UNAVAILABLE（增强件非闭环件，方案 §5 降级预案 ⑤）
        LhbSummary lhb = null;
        HolderChangeSummary holder = null;
        try {
            lhb = attentionSource.fetchLhbSummary(code, sinceDate);
        } catch (RuntimeException e) {
            log.warn("主力关注度代理·龙虎榜降级 code={}: {}", code, e.toString());
        }
        sleepPolitely();
        try {
            holder = attentionSource.fetchHolderChangeSummary(code, sinceDate);
        } catch (RuntimeException e) {
            log.warn("主力关注度代理·增减持降级 code={}: {}", code, e.toString());
        }
        badge.put("queryTime", queryTime);
        if (lhb == null || holder == null) {
            badge.put("state", "UNAVAILABLE");
            return badge;
        }
        badge.put("lhb30d", lhb.count());
        ObjectNode latest = badge.putObject("lhbLatest");
        if (lhb.latestDate() == null) {
            latest.putNull("date");
            latest.putNull("reason");
        } else {
            latest.put("date", lhb.latestDate());
            latest.put("reason", lhb.reason() == null ? "" : lhb.reason());
        }
        badge.put("chgDirection", holder.netDirection());
        badge.put("chgCount", holder.increaseCount() + holder.decreaseCount());
        badge.put("state", "OK");
        return badge;
    }

    private void sleepPolitely() {
        if (requestIntervalMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(requestIntervalMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("主力关注度代理礼貌间隔被中断", e);
        }
    }
}
