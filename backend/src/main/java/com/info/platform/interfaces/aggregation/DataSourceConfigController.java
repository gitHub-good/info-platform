package com.info.platform.interfaces.aggregation;

import com.info.platform.application.aggregation.DataSourceConfigFacade;
import com.info.platform.application.aggregation.DataSourceConfigFacade.AggregationGlobalUpdate;
import com.info.platform.application.aggregation.DataSourceConfigFacade.AggregationView;
import com.info.platform.application.aggregation.DataSourceConfigFacade.ConnectivityResult;
import com.info.platform.application.aggregation.DataSourceConfigFacade.DataSourceConfigUpdate;
import com.info.platform.application.aggregation.DataSourceConfigFacade.DataSourceConfigView;
import com.info.platform.application.aggregation.DataSourceConfigFacade.SourceCardView;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.interfaces.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据源配置接口（T36，方案 §4.4.2 datasource-configs 组，REQ 故事 3）。
 *
 * <p>受 JWT 保护（不在 {@code JwtAuthFilter} 白名单）。写接口幂等（同 body 重复提交结果一致），body 可带 {@code
 * expectedUpdatedAt} 防并发误覆盖（不符 30065/409）。 数据源参数全部 LIVE 级（保存即热生效，方案 §4.2），响应逐字段 {@code
 * effectiveModes} 供前端徽章渲染。
 *
 * <h2>端点</h2>
 *
 * <ul>
 *   <li>{@code GET /api/v1/datasource-configs} —— 7 源卡片（含健康徽章数据）+ 聚合总超时条
 *   <li>{@code PATCH /api/v1/datasource-configs/{sourceCode}} —— 单源部分字段合并（LIVE 保存即生效）
 *   <li>{@code PATCH /api/v1/datasource-configs/aggregation/global} —— 聚合总超时（页面总超时条）
 *   <li>{@code POST /api/v1/datasource-configs/{sourceCode}/connectivity-test} —— 分源连通性测试（测试已执行即
 *       200）
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/datasource-configs")
public class DataSourceConfigController {

    private final DataSourceConfigFacade facade;

    public DataSourceConfigController(DataSourceConfigFacade facade) {
        this.facade = facade;
    }

    /** 7 源卡片 + 聚合总超时条（健康空态：该源从未有 data_source_event → lastEventType/lastEventAt 为 null）。 */
    @GetMapping
    public Result<DataSourceConfigView> view() {
        return Result.ok(facade.view());
    }

    /** 更新单源配置（enabled/mode/超时/重试/TTL/params，均 LIVE）。 */
    @PatchMapping("/{sourceCode}")
    public Result<SourceCardView> update(
            @PathVariable String sourceCode, @RequestBody DataSourceConfigUpdate update) {
        return Result.ok(facade.update(parse(sourceCode), update));
    }

    /** 更新聚合编排总超时（页面「聚合总超时条」，LIVE 每请求生效）。 */
    @PatchMapping("/aggregation/global")
    public Result<AggregationView> updateAggregation(@RequestBody AggregationGlobalUpdate update) {
        return Result.ok(facade.updateAggregation(update));
    }

    /** 连通性测试：返回 {@code {ok, latencyMillis, itemCount, mode, error, note}}；ok=false 亦 200（测试已执行）。 */
    @PostMapping("/{sourceCode}/connectivity-test")
    public Result<ConnectivityResult> connectivityTest(@PathVariable String sourceCode) {
        return Result.ok(facade.connectivityTest(parse(sourceCode)));
    }

    private static SourceCode parse(String sourceCode) {
        try {
            return SourceCode.valueOf(sourceCode);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    ErrorCode.DATASOURCE_CONFIG_NOT_FOUND, "数据源不存在: " + sourceCode);
        }
    }
}
