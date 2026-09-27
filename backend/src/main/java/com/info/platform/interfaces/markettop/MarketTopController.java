package com.info.platform.interfaces.markettop;

import com.info.platform.application.markettop.MarketTopConfigFacade;
import com.info.platform.application.markettop.MarketTopConfigFacade.ConfigUpdate;
import com.info.platform.application.markettop.MarketTopConfigFacade.ConfigView;
import com.info.platform.application.markettop.MarketTopQueryService;
import com.info.platform.application.markettop.MarketTopQueryService.RankView;
import com.info.platform.domain.markettop.MarketTopRepository.VersionSummary;
import com.info.platform.interfaces.common.Result;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全市场榜单接口（M21 T181 配置面 + T183 读取面，方案 §4.7，Bearer JWT）：
 *
 * <ul>
 *   <li>{@code GET /api/v1/market-top?date=&version=} —— 榜单详情（缺省最新有榜单日最大版本；30089 无榜单 / 30090
 *       参数非法或版本不存在）
 *   <li>{@code GET /api/v1/market-top/versions?date=} —— 历史版本列表（日期降序、版本降序）
 *   <li>{@code GET /api/v1/market-top/config} —— 漏斗配置（任务中心 MARKET_TOP_JOB 编辑 Dialog 数据源；键缺失 = 代码缺省）
 *   <li>{@code PATCH /api/v1/market-top/config} —— 5 字段全量替换（非法字段级 30091 原值保留；expectedUpdatedAt 不符
 *       30065/409； deepDiveLimit 30~50 硬校验——「全量 LLM 逐股永不发生」的配置面防线）
 * </ul>
 *
 * <p>方法论端点（GET /market-top/methodology）随 T185 增补。
 */
@RestController
@RequestMapping("/api/v1")
public class MarketTopController {

    private final MarketTopConfigFacade configFacade;

    private final MarketTopQueryService queryService;

    public MarketTopController(
            MarketTopConfigFacade configFacade, MarketTopQueryService queryService) {
        this.configFacade = configFacade;
        this.queryService = queryService;
    }

    /** 榜单详情（date 缺省最新有榜单日；version 缺省该日最大；30089/30090 语义见类注释）。 */
    @GetMapping("/market-top")
    public Result<RankView> rank(
            @RequestParam(name = "date", required = false) String date,
            @RequestParam(name = "version", required = false) String version) {
        return Result.ok(queryService.rank(date, version));
    }

    /** 历史版本列表（date 可选过滤；日期降序、版本降序）。 */
    @GetMapping("/market-top/versions")
    public Result<List<VersionSummary>> versions(
            @RequestParam(name = "date", required = false) String date) {
        return Result.ok(queryService.versions(date));
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
