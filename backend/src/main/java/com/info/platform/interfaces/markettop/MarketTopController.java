package com.info.platform.interfaces.markettop;

import com.info.platform.application.markettop.MarketTopConfigFacade;
import com.info.platform.application.markettop.MarketTopConfigFacade.ConfigUpdate;
import com.info.platform.application.markettop.MarketTopConfigFacade.ConfigView;
import com.info.platform.interfaces.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全市场榜单接口（M21 T181 配置面，方案 §4.7.3，Bearer JWT）：
 *
 * <ul>
 *   <li>{@code GET /api/v1/market-top/config} —— 漏斗配置（任务中心 MARKET_TOP_JOB 编辑 Dialog
 *       数据源，FACTOR_SNAPSHOT 权重 Dialog 同款先例；键缺失 = 代码缺省 + null updatedAt）
 *   <li>{@code PATCH /api/v1/market-top/config} —— 5 字段全量替换（非法字段级 30091 原值保留；expectedUpdatedAt 不符
 *       30065/409；deepDiveLimit 30~50 硬校验——「全量 LLM 逐股永不发生」的配置面防线）
 * </ul>
 *
 * <p>榜单读取（GET /market-top）与方法论（GET /market-top/methodology）端点随 T183/T185 增补。
 */
@RestController
@RequestMapping("/api/v1")
public class MarketTopController {

    private final MarketTopConfigFacade configFacade;

    public MarketTopController(MarketTopConfigFacade configFacade) {
        this.configFacade = configFacade;
    }

    /** 当前漏斗配置视图（5 参数 + updatedAt 下次防呆比对）。 */
    @GetMapping("/market-top/config")
    public Result<ConfigView> config() {
        return Result.ok(configFacade.view());
    }

    /** 全量替换漏斗配置（非法 30091 字段级、原值保留；expectedUpdatedAt 不符 30065/409）。 */
    @PatchMapping("/market-top/config")
    public Result<ConfigView> updateConfig(@RequestBody ConfigUpdate update) {
        return Result.ok(configFacade.update(update));
    }
}
