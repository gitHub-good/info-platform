package com.info.platform.interfaces.feed;

import com.info.platform.application.feed.NewsItemsQueryService;
import com.info.platform.domain.analysis.IndustryCategory;
import com.info.platform.domain.analysis.L0Result;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.feed.FeedItemRepository.LibraryFilter;
import com.info.platform.interfaces.common.PageQuery;
import com.info.platform.interfaces.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一资讯流接口（M13 T104，方案 §4.5；T160 增资讯库过滤与 analysis 字段）：{@code GET /api/v1/news-items}（Bearer JWT）。
 *
 * <p><b>同端点双模式</b>（M9 / ADR-0035 惯例）：{@code page} 参数出现即页码模式（{items, total, page, size}）；
 * 缺席走游标模式（{items, nextBeforeId}，{@code beforeId} 续取 + {@code limit} 默认 20、1~50 越界 400 拒绝不截断）。
 * 两模式均支持 {@code sourceId} 源过滤；默认排除软删源条目（join info_source）。参数校验经 {@link PageQuery} 共用件（page/cursor
 * 互斥、size 缺省与上限）， limit 为游标模式专属（与 page 同现 400——契约确定性优先）。
 *
 * <p><b>T160 增量（M19 V2.1，REQ-20260926-16 拍板一）</b>——响应追加 analysis join 字段
 * （l0Result/l0Detail/l1Main/l1Confidence/lowConfidence/nearDupMasterId/nearDupMasterUrl，两模式一致、无行兜底）；
 * 页码模式新增三过滤参数 （页码模式专属，M9 filter 先例——出现而 page 缺席 400）：
 *
 * <ul>
 *   <li>{@code q}：关键词，trim 后空 = 不过滤；长度 2~64 越界 400/2001（防单字全表模糊，M9 keyword 同口径）。
 *   <li>{@code l0}：L0 状态，取值 PASS / NOISE / NEAR_DUP / ALL，非法 400/2001。<b>缺省 PASS</b>——裁量留档：REQ 拍板一
 *       API 缺口表冻结「缺省 PASS」（对齐管道消费口径：默认视图 = 有效资讯全量）；既有消费方经核实仅为 M13 验收一次性 curl
 *       （前端源码无引用、无持久代码消费方），行为变化零波及面；ALL 显式值保留全量可见能力，游标路径不受 l0 影响（字节级不动）。
 *   <li>{@code l1}：主分类，35 枚举白名单（{@link IndustryCategory}，代码侧校验权威），非法 400/2001（字段级 msg， 本端点参数错误统一
 *       2001 段——30079 为事件流专属不复用）。
 *   <li>{@code publishedFrom}/{@code publishedTo}：发布时间窗（BUG-M23-01），yyyy-MM-dd 非法 400、from&gt;to
 *       400。
 *   <li>{@code fetchedFrom}/{@code fetchedTo}：入库时间窗（T210，M24 V2.4，REQ-20260928-20 拍板三）——上海日界
 *       含端点，大盘「今日入库」弹框与 source_daily_stats.newCount 对账的口径前提；校验与 publishedFrom/To 同例（yyyy-MM-dd 非法
 *       400、from&gt;to 400、页码模式专属）。
 *   <li>{@code subjectCode}：回联标的代码（V3.1 资讯库标的增强）——matched_subjects 含该代码的行（L1 DONE）；trim 后空 =
 *       不过滤；值域限 {@code [A-Za-z0-9]{2,16}}（标的代码口径，兼 LIKE 通配面防护——%/下划线按字面拒绝）非法 400/2001； 页码模式专属 （出现而
 *       page 缺席 400）。响应字段增量见 {@code NewsItemView.matchedSubjects}。
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/news-items")
public class NewsItemsController {

    /** 游标页大小缺省（对齐方案 §4.5：默认 20）。 */
    static final int DEFAULT_LIMIT = PageQuery.DEFAULT_SIZE;

    /** 关键词长度下限（防单字全表模糊，M9 §4.1 同口径）。 */
    static final int KEYWORD_MIN_LENGTH = 2;

    /** 关键词长度上限（M9 §4.1 同口径）。 */
    static final int KEYWORD_MAX_LENGTH = 64;

    /** l0 显式全量值（REQ 拍板一：l0=PASS/NOISE/NEAR_DUP/ALL）。 */
    static final String L0_ALL = "ALL";

    /** 标的代码值域（V3.1：[A-Za-z0-9]{2,16}——标的代码口径，兼 matched_subjects LIKE 通配面防护）。 */
    static final String SUBJECT_CODE_PATTERN = "[A-Za-z0-9]{2,16}";

    private final NewsItemsQueryService queryService;

    public NewsItemsController(NewsItemsQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 统一资讯流（双模式分派：page 出现即页码模式，缺席走游标路径）。
     *
     * @return 游标模式 200 + {items[], nextBeforeId}；页码模式 200 + {items[], total, page, size}
     */
    @GetMapping
    public Result<?> list(
            @RequestParam(value = "sourceId", required = false) Long sourceId,
            @RequestParam(value = "beforeId", required = false) Long beforeId,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "l0", required = false) String l0,
            @RequestParam(value = "l1", required = false) String l1,
            @RequestParam(value = "publishedFrom", required = false) String publishedFrom,
            @RequestParam(value = "publishedTo", required = false) String publishedTo,
            @RequestParam(value = "fetchedFrom", required = false) String fetchedFrom,
            @RequestParam(value = "fetchedTo", required = false) String fetchedTo,
            @RequestParam(value = "subjectCode", required = false) String subjectCode) {
        String trimmedQ = trimToNull(q);
        String trimmedL1 = trimToNull(l1);
        String trimmedL0 = trimToNull(l0);
        String validatedSubjectCode = validatedSubjectCode(trimToNull(subjectCode));
        String from = validatedDate(publishedFrom, "publishedFrom");
        String to = validatedDate(publishedTo, "publishedTo");
        if (from != null && to != null && from.compareTo(to) > 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "publishedFrom 不得晚于 publishedTo");
        }
        String fetchedFromDay = validatedDate(fetchedFrom, "fetchedFrom");
        String fetchedToDay = validatedDate(fetchedTo, "fetchedTo");
        if (fetchedFromDay != null
                && fetchedToDay != null
                && fetchedFromDay.compareTo(fetchedToDay) > 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "fetchedFrom 不得晚于 fetchedTo");
        }
        PageQuery.requirePageParam(
                page,
                "q/l0/l1/publishedFrom/publishedTo/fetchedFrom/fetchedTo/subjectCode",
                trimmedQ != null
                        || trimmedL0 != null
                        || trimmedL1 != null
                        || from != null
                        || to != null
                        || fetchedFromDay != null
                        || fetchedToDay != null
                        || validatedSubjectCode != null);
        PageQuery pageQuery = PageQuery.resolve(page, size, beforeId);
        if (pageQuery != null) {
            rejectLimitInPageMode(limit);
            LibraryFilter filter =
                    new LibraryFilter(
                            sourceId,
                            validatedKeyword(trimmedQ),
                            resolvedL0(trimmedL0),
                            validatedCategory(trimmedL1),
                            from,
                            to,
                            fetchedFromDay,
                            fetchedToDay,
                            validatedSubjectCode);
            return Result.ok(queryService.listPaged(filter, pageQuery.page(), pageQuery.size()));
        }
        return Result.ok(queryService.listCursor(sourceId, beforeId, resolvedLimit(limit)));
    }

    /** limit 归一化：缺席取缺省 20；1~50 越界 400 拒绝不截断（对齐 size 口径）。 */
    private static int resolvedLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "limit 至少为 1");
        }
        if (limit > PageQuery.MAX_SIZE) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID, "limit 超过上限 " + PageQuery.MAX_SIZE);
        }
        return limit;
    }

    private static void rejectLimitInPageMode(Integer limit) {
        if (limit != null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "limit 仅游标模式可用（页码模式请使用 size）");
        }
    }

    /** trim 后空串归一为 null（blank 视为缺席——M9 keyword/status 同惯例）。 */
    private static String trimToNull(String raw) {
        return raw == null || raw.trim().isEmpty() ? null : raw.trim();
    }

    /** q 校验（M9 keyword 同口径）：null = 不过滤；非空长度 2~64，越界 400/2001（字段级 msg）。 */
    private static String validatedKeyword(String trimmedQ) {
        if (trimmedQ == null) {
            return null;
        }
        if (trimmedQ.length() < KEYWORD_MIN_LENGTH) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID, "q 至少为 " + KEYWORD_MIN_LENGTH + " 个字符");
        }
        if (trimmedQ.length() > KEYWORD_MAX_LENGTH) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID, "q 最长 " + KEYWORD_MAX_LENGTH + " 个字符");
        }
        return trimmedQ;
    }

    /**
     * l0 归一化：缺席取缺省 PASS（裁量留档见类注释）；ALL = 不过滤；枚举名非法 400/2001（字段级 msg）。
     *
     * @return 过滤值；ALL 归一为 null（仓储层 null = 无 l0 条件）
     */
    private static L0Result resolvedL0(String trimmedL0) {
        if (trimmedL0 == null) {
            return L0Result.PASS;
        }
        if (L0_ALL.equals(trimmedL0)) {
            return null;
        }
        try {
            return L0Result.fromName(trimmedL0);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID, "l0 须为 PASS/NOISE/NEAR_DUP/ALL，当前值 " + trimmedL0);
        }
    }

    /** l1 校验：null = 不过滤；35 枚举白名单外 400/2001（字段级 msg）。 */
    private static String validatedCategory(String trimmedL1) {
        if (trimmedL1 == null) {
            return null;
        }
        if (!IndustryCategory.isValid(trimmedL1)) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID, "l1 须为 35 个主分类枚举之一，当前值 " + trimmedL1);
        }
        return trimmedL1;
    }

    /**
     * subjectCode 校验（V3.1）：null = 不过滤；值域 {@link #SUBJECT_CODE_PATTERN} 外 400/2001（字段级 msg）——
     * 代码口径排除通配/分隔字符（matched_subjects LIKE 值位定界的前提，无转义面）。
     */
    private static String validatedSubjectCode(String trimmedSubjectCode) {
        if (trimmedSubjectCode == null) {
            return null;
        }
        if (!trimmedSubjectCode.matches(SUBJECT_CODE_PATTERN)) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID,
                    "subjectCode 须为 2~16 位字母数字（标的代码），当前值 " + trimmedSubjectCode);
        }
        return trimmedSubjectCode;
    }

    /** 发布时间窗日期校验（BUG-M23-01）：yyyy-MM-dd 合法格式，非法 400 字段级。 */
    private static String validatedDate(String raw, String field) {
        String trimmed = trimToNull(raw);
        if (trimmed == null) {
            return null;
        }
        if (!trimmed.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, field + ": 须为 yyyy-MM-dd 日期");
        }
        try {
            java.time.LocalDate.parse(trimmed);
        } catch (java.time.format.DateTimeParseException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, field + ": 非法日期 " + trimmed);
        }
        return trimmed;
    }
}
