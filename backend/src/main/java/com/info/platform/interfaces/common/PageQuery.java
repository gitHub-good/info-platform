package com.info.platform.interfaces.common;

import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;

/**
 * 页码分页参数校验共用件（M9 列表分页，方案 §4.1/§4.2、ADR-0035）。
 *
 * <p>同端点双模式契约的<b>模式判别与参数校验单点</b>：{@code page} 参数出现即页码模式；缺席走既有游标路径（字节级不动）。 非法参数抛 {@link
 * BusinessException}（{@code 2001 PARAM_INVALID}，字段级 msg），由 {@code GlobalExceptionHandler} 映射 400。
 *
 * <p>规则（两 Controller 共用）：{@code page≥1}（上限 {@link #MAX_PAGE} 防 offset 溢出）；{@code size} 缺省 {@link
 * #DEFAULT_SIZE}、1~50 越界 400 <b>拒绝不截断</b>；{@code page}×{@code cursor} 互斥 400；页码专属参数（size 与各端点专属
 * filter 参数）缺 {@code page} 400——契约确定性优先于宽容。
 */
public final class PageQuery {

    /** 页大小缺省值（对齐两列表既有游标页大小 20）。 */
    public static final int DEFAULT_SIZE = 20;

    /** 页大小上限（防攻击性大页，超限 400 拒绝——ADR-0035）。 */
    public static final int MAX_SIZE = 50;

    /** 页码上限（防 offset 溢出：(page-1)×size &lt; 5×10^7 &lt; int max）。 */
    public static final int MAX_PAGE = 1_000_000;

    private final int page;
    private final int size;

    private PageQuery(int page, int size) {
        this.page = page;
        this.size = size;
    }

    public int page() {
        return page;
    }

    public int size() {
        return size;
    }

    /**
     * 页码模式专属参数防呆：参数出现而 {@code page} 缺席 → 400（msg 注明缺 page，契约确定性优先）。
     *
     * <p>调用方须先算好「参数出现」布尔（blank 视为缺席——keyword/status trim 后空即不过滤）。
     *
     * @param page page 参数；null = 缺席
     * @param paramName 专属参数名（keyword/status，仅用于 msg）
     * @param paramPresent 专属参数是否出现
     */
    public static void requirePageParam(Integer page, String paramName, boolean paramPresent) {
        if (page == null && paramPresent) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID, "缺少 page 参数（" + paramName + " 仅页码模式可用）");
        }
    }

    /**
     * 分区子端点必填页码校验（M12 详情分区分页，ADR-0037 决策 1）：page 缺席/小于 1 → 400； 可带端点专属上限（公告 5 页——超限 msg
     * 引导走源站，外呼放大封顶由此成立）；上限兜底 {@link #MAX_PAGE}（防 offset 溢出）。
     *
     * @param page page 参数；null = 缺席（子端点 page 必填，与列表双模式不同）
     * @param maxPage 端点页码上限（公告 5 / 其余传 {@link #MAX_PAGE}）
     * @param overLimitMessage 超上限 msg（仅用于 msg）
     * @return 归一化页码（≥1 且 ≤maxPage）
     */
    public static int requirePage(Integer page, int maxPage, String overLimitMessage) {
        if (page == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "page 不能为空");
        }
        if (page < 1) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "page 至少为 1");
        }
        if (page > maxPage) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, overLimitMessage);
        }
        return page;
    }

    /**
     * 分区子端点可选页大小边界校验（M12）：显式传值时 1~50 越界 400 <b>拒绝不截断</b>（复用 {@link #MAX_SIZE} 口径）； 缺席返回
     * null，缺省值归应用层按端点语义解析（公告 = 运行时 announcePageSize / 事件 = 10——子端点缺省与列表 DEFAULT_SIZE
     * 语义不同，不在共用件内固化）。
     *
     * @param size size 参数；null = 缺席
     * @return 归一化页大小；缺席返回 null
     */
    public static Integer requireSizeIfPresent(Integer size) {
        if (size == null) {
            return null;
        }
        if (size < 1) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "size 至少为 1");
        }
        if (size > MAX_SIZE) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "size 超过上限 " + MAX_SIZE);
        }
        return size;
    }

    /**
     * 解析页码模式参数组（模式判别 + 边界校验）。
     *
     * @param page page 参数；null = 缺席
     * @param size size 参数；null = 缺席（页码模式下取默认 {@link #DEFAULT_SIZE}）
     * @param cursor 既有游标参数；与 page 互斥（同时出现 400）
     * @return 页码模式归一化参数；page 缺席（纯游标模式）返回 null
     */
    public static PageQuery resolve(Integer page, Integer size, Long cursor) {
        if (page == null) {
            if (size != null) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "缺少 page 参数（size 仅页码模式可用）");
            }
            return null;
        }
        if (cursor != null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "page 与 cursor 互斥，只能二选一");
        }
        if (page < 1) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "page 至少为 1");
        }
        if (page > MAX_PAGE) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "page 超过上限 " + MAX_PAGE);
        }
        return new PageQuery(page, resolvedSize(size));
    }

    private static int resolvedSize(Integer size) {
        if (size == null) {
            return DEFAULT_SIZE;
        }
        if (size < 1) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "size 至少为 1");
        }
        if (size > MAX_SIZE) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "size 超过上限 " + MAX_SIZE);
        }
        return size;
    }
}
