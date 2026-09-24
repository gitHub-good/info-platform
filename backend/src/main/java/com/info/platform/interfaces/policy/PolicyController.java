package com.info.platform.interfaces.policy;

import com.info.platform.application.policy.PolicyDetailView;
import com.info.platform.application.policy.PolicyService;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.UserContext;
import com.info.platform.interfaces.common.PageQuery;
import com.info.platform.interfaces.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 政策时事流接口（对齐技术方案 §4.1.5 + M9 §4.1 页码模式与关键词搜索）。
 *
 * <p>列表<b>同端点双模式</b>（ADR-0035）： {@code GET /api/v1/policies?days=7&industry=&cursor=}（Bearer）游标分页，
 * {@code page} 参数缺席时本路径字节级不变； {@code GET
 * /api/v1/policies?days=&industry=&keyword=&page=2&size=20}（Bearer）页码模式， 返回 {@code {policies[],
 * total, page, size}}（列表字段与游标模式逐字段一致）。参数校验经 {@link PageQuery} 共用件与 keyword 长度校验（400/2001）。 {@code
 * GET /api/v1/policies/{id}}（Bearer）：政策详情 + 关联自选标的 （按 relatedIndustries 匹配当前用户 watchlist）+
 * ai_tendency（0 未判，T28 填）。
 *
 * <p>受 JWT 保护（T17 {@code JwtAuthFilter} 写入 {@link UserContext}），除登录/换发/actuator 外均需 Bearer； 错误码
 * {@code 30040} 政策条目不存在（404，{@code GlobalExceptionHandler} 映射）。
 */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

    private static final Logger log = LoggerFactory.getLogger(PolicyController.class);

    /** keyword 长度下限（防单字全表模糊，§4.1）。 */
    private static final int KEYWORD_MIN_LENGTH = 2;

    /** keyword 长度上限。 */
    private static final int KEYWORD_MAX_LENGTH = 64;

    private final PolicyService policyService;

    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    /**
     * 政策列表（双模式分派：page 出现即页码模式，缺席走既有游标路径）。
     *
     * @return 游标模式 200 + {policies[], nextCursor}；页码模式 200 + {policies[], total, page, size}
     */
    @GetMapping
    public Result<?> list(
            @RequestParam(value = "days", defaultValue = "7") int days,
            @RequestParam(value = "industry", required = false) String industry,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "keyword", required = false) String keyword) {
        String trimmedKeyword = keyword == null ? null : keyword.trim();
        PageQuery.requirePageParam(
                page, "keyword", trimmedKeyword != null && !trimmedKeyword.isEmpty());
        PageQuery pageQuery = PageQuery.resolve(page, size, cursor);
        if (pageQuery == null) {
            return Result.ok(policyService.listPolicies(days, industry, cursor));
        }
        return Result.ok(
                policyService.listPoliciesPaged(
                        days,
                        industry,
                        validatedKeyword(trimmedKeyword),
                        pageQuery.page(),
                        pageQuery.size()));
    }

    /** keyword 校验（§4.1）：trim 后空 = 缺席（不过滤）；非空长度 2~64，越界 400（2001，字段级 msg）。 */
    private static String validatedKeyword(String trimmedKeyword) {
        if (trimmedKeyword == null || trimmedKeyword.isEmpty()) {
            return null;
        }
        if (trimmedKeyword.length() < KEYWORD_MIN_LENGTH) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID, "关键词至少为 " + KEYWORD_MIN_LENGTH + " 个字符");
        }
        if (trimmedKeyword.length() > KEYWORD_MAX_LENGTH) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID, "关键词最长 " + KEYWORD_MAX_LENGTH + " 个字符");
        }
        return trimmedKeyword;
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
