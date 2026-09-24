package com.info.platform.interfaces.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import org.junit.jupiter.api.Test;

/** PageQuery 单元测试（M9 T60）：双模式参数组校验规则矩阵（默认值/边界/互斥/专属参数防呆），两 Controller 共用件的单点行为锁定。 */
class PageQueryTest {

    /** 执行并捕获业务异常（断言错误码/msg 用）；未抛即失败。 */
    private static BusinessException catchBiz(Runnable runnable) {
        try {
            runnable.run();
        } catch (BusinessException e) {
            return e;
        }
        throw new AssertionError("期望抛出 BusinessException 但未抛出");
    }

    @Test
    void resolve_pageAbsent_returnsNullForCursorMode() {
        // Arrange + Act：page 缺席且无专属参数 → null（调用方走既有游标路径）
        assertThat(PageQuery.resolve(null, null, null)).isNull();
        assertThat(PageQuery.resolve(null, null, 123L)).isNull();
    }

    @Test
    void resolve_pageAbsent_sizePresent_throws2001() {
        // Act + Assert：size 仅页码模式可用，缺 page 400（msg 注明缺 page）
        BusinessException ex = catchBiz(() -> PageQuery.resolve(null, 20, null));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(ex.getMessage()).contains("page").contains("size");
    }

    @Test
    void resolve_pageAndCursorMutex_throws2001() {
        BusinessException ex = catchBiz(() -> PageQuery.resolve(1, 20, 5L));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(ex.getMessage()).contains("互斥");
    }

    @Test
    void resolve_pageBounds_rejectZeroAndOverflow() {
        // page=0 → 400
        assertThatThrownBy(() -> PageQuery.resolve(0, 20, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("page 至少为 1");
        // page 超上限 1,000,000 → 400
        assertThatThrownBy(() -> PageQuery.resolve(PageQuery.MAX_PAGE + 1, 20, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("page 超过上限");
        // 边界值本身合法：page=1 与 page=MAX_PAGE
        assertThatCode(() -> PageQuery.resolve(1, 20, null)).doesNotThrowAnyException();
        assertThatCode(() -> PageQuery.resolve(PageQuery.MAX_PAGE, 50, null))
                .doesNotThrowAnyException();
    }

    @Test
    void resolve_sizeDefaultsTo20_whenAbsent() {
        PageQuery pq = PageQuery.resolve(3, null, null);
        assertThat(pq.page()).isEqualTo(3);
        assertThat(pq.size()).isEqualTo(20);
    }

    @Test
    void resolve_sizeBounds_rejectZeroAndOver50() {
        // size=0 → 400（至少为 1）
        assertThatThrownBy(() -> PageQuery.resolve(1, 0, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("size 至少为 1");
        // size=51 → 400（超过上限 50，拒绝不截断）
        assertThatThrownBy(() -> PageQuery.resolve(1, 51, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("size 超过上限 50");
        // 边界值合法：size=1 与 size=50
        assertThat(PageQuery.resolve(1, 1, null).size()).isEqualTo(1);
        assertThat(PageQuery.resolve(1, 50, null).size()).isEqualTo(50);
    }

    @Test
    void requirePageParam_absentParam_noOp() {
        // 专属参数缺席（blank 视为缺席）→ 不抛，游标模式放行
        assertThatCode(() -> PageQuery.requirePageParam(null, "keyword", false))
                .doesNotThrowAnyException();
        assertThatCode(() -> PageQuery.requirePageParam(1, "status", true))
                .doesNotThrowAnyException();
    }

    @Test
    void requirePageParam_paramPresentWithoutPage_throws2001() {
        // 专属参数出现而 page 缺席 → 400，msg 注明参数名
        BusinessException ex = catchBiz(() -> PageQuery.requirePageParam(null, "keyword", true));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(ex.getMessage()).contains("page").contains("keyword");
    }
}
