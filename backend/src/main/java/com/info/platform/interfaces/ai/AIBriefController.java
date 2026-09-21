package com.info.platform.interfaces.ai;

import com.info.platform.application.ai.AIBriefService;
import com.info.platform.application.ai.AIBriefView;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.common.UserContext;
import com.info.platform.interfaces.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 简报接口（对齐技术方案 §4.1.4）。
 *
 * <p>两端点受 JWT 保护（T17 JwtAuthFilter 写入 {@link UserContext}）： POST 创建（异步）→ 202 + taskId（幂等重复返回上次
 * taskId；成本上限超限 30030/429；个股型标的不存在 30001/404）； GET 查询 → 200 +
 * {status,content,sourceLinks,disclaimer} （任务不存在 30032/404）。全部响应恒附免责声明「AI 生成，非投资建议」。
 *
 * <p>POST 用 202 Accepted（异步受理，非 200）——区别于同步 CRUD 接口；前端据 taskId 轮询 GET 或订阅 SSE 结果事件。
 */
@RestController
@RequestMapping("/api/v1/ai-briefs")
public class AIBriefController {

    private static final Logger log = LoggerFactory.getLogger(AIBriefController.class);

    private final AIBriefService aiBriefService;

    public AIBriefController(AIBriefService aiBriefService) {
        this.aiBriefService = aiBriefService;
    }

    /**
     * 创建简报（异步受理）。
     *
     * @param idempotencyKey 幂等键头（业务语义键 subjectId+briefType+date 由服务层构造，头作审计）
     * @param request {subjectId, briefType}
     * @return 202 + {taskId}；重复返回上次 taskId；成本上限→429/30030；个股型标的缺失→404/30001
     */
    @PostMapping
    public ResponseEntity<Result<TaskIdView>> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateBriefRequest request) {
        log.debug(
                "创建 AI 简报 Idempotency-Key={} subjectId={} briefType={}",
                idempotencyKey,
                request.subjectId(),
                request.briefType());
        Long taskId =
                aiBriefService.createBrief(
                        request.subjectId(), BriefType.fromCode(request.briefType()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Result.ok(new TaskIdView(taskId)));
    }

    /**
     * 查询简报。
     *
     * @return 200 + {status,content,sourceLinks,disclaimer}；任务不存在→404/30032
     */
    @GetMapping("/{taskId}")
    public Result<AIBriefView> get(@PathVariable Long taskId) {
        return Result.ok(aiBriefService.getBrief(taskId));
    }

    /** 创建请求体。subjectId 可空（每日推荐型无单一标的）；briefType 1~4。 */
    public record CreateBriefRequest(Long subjectId, @NotNull @Min(1) @Max(4) Integer briefType) {}

    /** 创建响应：受理任务 id。 */
    public record TaskIdView(Long taskId) {}
}
