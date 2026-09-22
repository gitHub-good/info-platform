package com.info.platform.interfaces.policy;

import com.info.platform.application.policy.PolicyDetailView;
import com.info.platform.application.policy.PolicyListView;
import com.info.platform.application.policy.PolicyService;
import com.info.platform.domain.common.UserContext;
import com.info.platform.interfaces.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 政策时事流接口（对齐技术方案 §4.1.5）。
 *
 * <p>{@code GET /api/v1/policies?days=7&industry=&cursor=}（Bearer）：政策条目列表（游标分页，每条含
 * 标题/来源/时间/摘要/关联行业标签）。 {@code GET /api/v1/policies/{id}}（Bearer）：政策详情 + 关联自选标的 （按 relatedIndustries
 * 匹配当前用户 watchlist）+ ai_tendency（0 未判，T28 填）。
 *
 * <p>受 JWT 保护（T17 {@code JwtAuthFilter} 写入 {@link UserContext}），除登录/换发/actuator 外均需 Bearer； 错误码
 * {@code 30040} 政策条目不存在（404，{@code GlobalExceptionHandler} 映射）。
 */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

    private static final Logger log = LoggerFactory.getLogger(PolicyController.class);

    private final PolicyService policyService;

    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    /**
     * 政策列表（游标分页 + 行业过滤）。
     *
     * @return 200 + {policies[], nextCursor}
     */
    @GetMapping
    public Result<PolicyListView> list(
            @RequestParam(value = "days", defaultValue = "7") int days,
            @RequestParam(value = "industry", required = false) String industry,
            @RequestParam(value = "cursor", required = false) Long cursor) {
        return Result.ok(policyService.listPolicies(days, industry, cursor));
    }

    /**
     * 政策详情 + 关联自选标的 + ai_tendency。
     *
     * @return 200 + 详情体；条目不存在 → 404 + 30040
     */
    @GetMapping("/{id}")
    public Result<PolicyDetailView> get(@PathVariable Long id) {
        long userId = currentUserId();
        log.debug("政策详情请求 id={} userId={}", id, userId);
        return Result.ok(policyService.getPolicy(id, userId));
    }

    private static long currentUserId() {
        UserContext.Principal p = UserContext.get();
        return p == null ? 0L : p.userId();
    }
}
