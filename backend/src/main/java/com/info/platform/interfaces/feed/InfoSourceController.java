package com.info.platform.interfaces.feed;

import com.info.platform.application.feed.ConnectivityTestResultView;
import com.info.platform.application.feed.InfoSourceCardView;
import com.info.platform.application.feed.InfoSourcesListView;
import com.info.platform.application.feed.SourceRegistryService;
import com.info.platform.application.feed.SourceStatsView;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.SourceConfig;
import com.info.platform.interfaces.common.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资讯源管理接口（M13 T105，方案 §4.5 /info-sources 组，Bearer JWT）。
 *
 * <h2>端点</h2>
 *
 * <ul>
 *   <li>{@code GET /api/v1/info-sources} —— 分组视图（active by category + archived 归档组）
 *   <li>{@code POST /api/v1/info-sources} —— 新增通用源（sourceCode 后端生成；robots 禁抓 30075 硬拦截）
 *   <li>{@code PATCH /api/v1/info-sources/{id}} —— 参数编辑（下一 tick 热生效；adapterType 不可变 30072）
 *   <li>{@code POST /api/v1/info-sources/{id}/enable|disable} —— 启停（下一 tick 摘除/加入）
 *   <li>{@code DELETE /api/v1/info-sources/{id}} —— 通用源软删（预置源 30073）
 *   <li>{@code POST /api/v1/info-sources/{id}/restore} —— 恢复软删源（回停用态）
 *   <li>{@code POST /api/v1/info-sources/{id}/connectivity-test} —— 干跑诊断（200 恒返回，不落库）
 *   <li>{@code POST /api/v1/info-sources/{id}/poll} —— 手动抓取（202 受理；在飞 30074）
 *   <li>{@code GET /api/v1/info-sources/stats?days=7} —— 数据面（rollup + 感知延迟 P50/P90）
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/info-sources")
public class InfoSourceController {

    /** stats 窗口缺省天数（方案 §4.5）。 */
    static final int DEFAULT_STATS_DAYS = 7;

    private static final int STATS_DAYS_MIN = 1;
    private static final int STATS_DAYS_MAX = 30;

    private final SourceRegistryService registry;

    public InfoSourceController(SourceRegistryService registry) {
        this.registry = registry;
    }

    /** 分组列表（active 按 category 分组 + archived 归档组）。 */
    @GetMapping
    public Result<InfoSourcesListView> list() {
        return Result.ok(registry.list());
    }

    /** 新增通用源（保存即启用，next_due_at = now → 首抓 ≤1 个调度周期）。 */
    @PostMapping
    public Result<InfoSourceCardView> create(@RequestBody CreateInfoSourceRequest request) {
        return Result.ok(registry.create(toCommand(request)));
    }

    /** 参数编辑（热生效：调度每 tick 现读行内配置）。 */
    @PatchMapping("/{id}")
    public Result<InfoSourceCardView> update(
            @PathVariable Long id, @RequestBody UpdateInfoSourceRequest request) {
        return Result.ok(
                registry.update(
                        id,
                        new SourceRegistryService.UpdateCommand(
                                request.name(),
                                request.category(),
                                request.adapterType(),
                                request.endpoint(),
                                request.intervalMinutes(),
                                request.enabled(),
                                request.config() == null ? null : request.config().toDomain())));
    }

    /** 启用（下一 tick 加入调度）。 */
    @PostMapping("/{id}/enable")
    public Result<InfoSourceCardView> enable(@PathVariable Long id) {
        return Result.ok(registry.changeEnabled(id, true));
    }

    /** 停用（下一 tick 摘除；可逆，区别于软删）。 */
    @PostMapping("/{id}/disable")
    public Result<InfoSourceCardView> disable(@PathVariable Long id) {
        return Result.ok(registry.changeEnabled(id, false));
    }

    /** 软删（通用源：停用 + 归档 + 条目保留；预置源 30073）。 */
    @DeleteMapping("/{id}")
    public Result<InfoSourceCardView> archive(@PathVariable Long id) {
        return Result.ok(registry.archive(id));
    }

    /** 恢复软删源（deleted=0，恢复为停用态——启用时机由用户确认）。 */
    @PostMapping("/{id}/restore")
    public Result<InfoSourceCardView> restore(@PathVariable Long id) {
        return Result.ok(registry.restore(id));
    }

    /** 连通性干跑（不落库：可达性 + robots 判读 + 解析样本 ≤3 条 + 延迟；失败原因在 body，200 恒返回）。 */
    @PostMapping("/{id}/connectivity-test")
    public Result<ConnectivityTestResultView> connectivityTest(@PathVariable Long id) {
        return Result.ok(registry.connectivityTest(id));
    }

    /** 手动抓取（202 受理，同通道同去重；在飞 409/30074）。 */
    @PostMapping("/{id}/poll")
    public ResponseEntity<Result<PollAcceptedView>> poll(@PathVariable Long id) {
        com.info.platform.domain.feed.InfoSource source = registry.submitPoll(id);
        return ResponseEntity.accepted()
                .body(Result.ok(new PollAcceptedView(source.getId(), source.getSourceCode())));
    }

    /** 数据面（days 1~30 缺省 7：逐源逐日 rollup + 全局感知延迟 P50/P90）。 */
    @GetMapping("/stats")
    public Result<SourceStatsView> stats(
            @RequestParam(value = "days", required = false) Integer days) {
        return Result.ok(registry.stats(resolveDays(days)));
    }

    private static SourceRegistryService.CreateCommand toCommand(CreateInfoSourceRequest request) {
        return new SourceRegistryService.CreateCommand(
                request.name(),
                request.category(),
                parseAdapterType(request.adapterType()),
                request.endpoint(),
                request.intervalMinutes(),
                request.enabled(),
                request.config() == null ? SourceConfig.empty() : request.config().toDomain());
    }

    /** adapterType 线解析：缺失/未知值 30072 字段级（通道白名单 rss/json_api 由服务层把关）。 */
    private static AdapterType parseAdapterType(String raw) {
        AdapterType type = raw == null || raw.isBlank() ? null : AdapterType.from(raw);
        if (type == null) {
            throw new BusinessException(
                    ErrorCode.INFO_SOURCE_CONFIG_INVALID,
                    "adapterType: 须为 rss / json_api，当前值 " + raw);
        }
        return type;
    }

    private static int resolveDays(Integer days) {
        if (days == null) {
            return DEFAULT_STATS_DAYS;
        }
        if (days < STATS_DAYS_MIN || days > STATS_DAYS_MAX) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID, "days 须在 " + STATS_DAYS_MIN + "~" + STATS_DAYS_MAX);
        }
        return days;
    }

    /** 手动抓取受理回执。 */
    public record PollAcceptedView(long sourceId, String sourceCode) {}
}
