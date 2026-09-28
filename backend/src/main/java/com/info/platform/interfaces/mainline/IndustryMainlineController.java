package com.info.platform.interfaces.mainline;

import com.info.platform.application.mainline.IndustryMainlineConfigFacade;
import com.info.platform.application.mainline.IndustryMainlineConfigFacade.ConfigUpdate;
import com.info.platform.application.mainline.IndustryMainlineConfigFacade.ConfigView;
import com.info.platform.application.mainline.IndustryMainlineQueryService;
import com.info.platform.application.mainline.IndustryMainlineQueryService.DetailView;
import com.info.platform.application.mainline.IndustryMainlineQueryService.HeatMapView;
import com.info.platform.application.mainline.IndustryMainlineQueryService.MainlineView;
import com.info.platform.application.mainline.IndustryMainlineService;
import com.info.platform.interfaces.common.Result;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行业主线接口（M27 T244，方案 §4.5，Bearer JWT）：
 *
 * <ul>
 *   <li>{@code GET /api/v1/industry-heat-map?date=} —— 31 行业热力数据 +
 *       meta（source/quoteTime/stale/snapshotDate；30093 全空）
 *   <li>{@code GET /api/v1/industry-mainline?date=&version=} —— 主线榜单（30094 无榜单 / 30095 参数非法或版本不存在）
 *   <li>{@code GET /api/v1/industry-mainline/{industry}/detail} —— 下钻（30095 行业非申万枚举或无快照行）
 *   <li>{@code GET /api/v1/industry-mainline/config} —— 两键配置当前值（键缺失 = 代码缺省）
 *   <li>{@code PATCH /api/v1/industry-mainline/config} —— 两键全量替换（30096 字段级原值保留；expectedUpdatedAt 不符
 *       30065/409）
 *   <li>{@code POST /api/v1/industry-mainline/recompute} —— 手动重算入口（version+1；任务中心手动触发同路径，REQ 拍板二 4）
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class IndustryMainlineController {

    private final IndustryMainlineQueryService queryService;

    private final IndustryMainlineConfigFacade configFacade;

    private final IndustryMainlineService mainlineService;

    private final Clock clock;

    public IndustryMainlineController(
            IndustryMainlineQueryService queryService,
            IndustryMainlineConfigFacade configFacade,
            IndustryMainlineService mainlineService,
            Clock clock) {
        this.queryService = queryService;
        this.configFacade = configFacade;
        this.mainlineService = mainlineService;
        this.clock = clock;
    }

    /** 31 行业热力数据（date 缺省当日，无当日行回退最近有行日；仅全空才 30093——空态由前端呈现）。 */
    @GetMapping("/industry-heat-map")
    public Result<HeatMapView> heatMap(@RequestParam(name = "date", required = false) String date) {
        return Result.ok(queryService.heatMap(date));
    }

    /** 主线榜单（date/version 缺省最新有榜日最大版本；非交易日回退最近榜日；全库无榜 30094）。 */
    @GetMapping("/industry-mainline")
    public Result<MainlineView> mainline(
            @RequestParam(name = "date", required = false) String date,
            @RequestParam(name = "version", required = false) String version) {
        return Result.ok(queryService.mainline(date, version));
    }

    /** 行业下钻（当日行 source 分形态：通道 A 板块明细 / 通道 B 成分股涨跌 + 领涨股 + 龙头 + 成员统计）。 */
    @GetMapping("/industry-mainline/{industry}/detail")
    public Result<DetailView> detail(@PathVariable("industry") @NotBlank String industry) {
        return Result.ok(queryService.detail(industry));
    }

    /** 两键配置视图（mainline 13 字段 + leader 7 字段 + updatedAt 防呆比对）。 */
    @GetMapping("/industry-mainline/config")
    public Result<ConfigView> config() {
        return Result.ok(configFacade.view());
    }

    /** 两键全量替换（非法 30096 字段级、原值保留；expectedUpdatedAt 不符 30065/409）。 */
    @PatchMapping("/industry-mainline/config")
    public Result<ConfigView> updateConfig(@RequestBody ConfigUpdate update) {
        return Result.ok(configFacade.update(update));
    }

    /** 手动重算入口（同步执行；trigger_source=MANUAL 留痕 version+1——任务中心手动触发同路径）。 */
    @PostMapping("/industry-mainline/recompute")
    public Result<RecomputeView> recompute() {
        IndustryMainlineService.GenerationReport report =
                mainlineService.compute(
                        clock.instant().atZone(IndustryMainlineService.RANK_ZONE).toLocalDate(),
                        true);
        return Result.ok(
                new RecomputeView(
                        clock.instant()
                                .atZone(IndustryMainlineService.RANK_ZONE)
                                .toLocalDate()
                                .toString(),
                        report.topSize(),
                        report.detail()));
    }

    /** 重算结果摘要（行数 + detail——版本号等留痕细节见任务中心 lastRunDetail）。 */
    record RecomputeView(String rankDate, int topSize, String detail) {}
}
