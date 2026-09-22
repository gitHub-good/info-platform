package com.info.platform.interfaces.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理器单测（T18）：BusinessException 按 ErrorCode 映射正确 HTTP 状态码与 code； 兜底异常映射 500/50000。纯单元测试，不启
 * Spring 上下文。
 *
 * <p>DEFECT-2 回归（M4）：@RequestParam 类型不匹配（如 cursor=zzz）映射 400/2001 字段级原因——此前被兜底 Exception 处理器 吞成
 * 500/50000。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleBusinessException_subjectNotFound_mapsTo404AndCode30001() {
        // Arrange
        BusinessException ex = new BusinessException(ErrorCode.SUBJECT_NOT_FOUND);

        // Act
        ResponseEntity<Result<Void>> response = handler.handleBusiness(ex);

        // Assert
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(30001);
        assertThat(response.getBody().getMsg()).isEqualTo("标的不存在");
        assertThat(response.getBody().getData()).isNull();
    }

    @Test
    void handleBusinessException_quotaExhausted_mapsTo429AndCode30030() {
        // Arrange
        BusinessException ex = new BusinessException(ErrorCode.AI_QUOTA_EXHAUSTED, "今日配额已用尽");

        // Act
        ResponseEntity<Result<Void>> response = handler.handleBusiness(ex);

        // Assert
        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(30030);
        assertThat(response.getBody().getMsg()).isEqualTo("今日配额已用尽");
    }

    @Test
    void handleBusinessException_conflict_mapsTo409AndCode30011() {
        // Arrange
        BusinessException ex = new BusinessException(ErrorCode.SUBJECT_ALREADY_IN_WATCHLIST);

        // Act
        ResponseEntity<Result<Void>> response = handler.handleBusiness(ex);

        // Assert
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getCode()).isEqualTo(30011);
    }

    @Test
    void handleUnexpectedException_mapsTo500AndCode50000() {
        // Arrange
        Exception ex = new RuntimeException("DB down");

        // Act
        ResponseEntity<Result<Void>> response = handler.handleUnexpected(ex);

        // Assert
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(50000);
        assertThat(response.getBody().getData()).isNull();
    }

    @Test
    void handleTypeMismatch_mapsTo400AndCode2001WithFieldReason() {
        // Arrange：FeedController.personal 的 cursor=zzz（Long 参数收到非数值），DEFECT-2
        MethodArgumentTypeMismatchException ex =
                new MethodArgumentTypeMismatchException("zzz", Long.class, "cursor", null, null);

        // Act
        ResponseEntity<Result<Void>> response = handler.handleTypeMismatch(ex);

        // Assert：参数校验口径 400/2001，msg 带字段与非法值
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(2001);
        assertThat(response.getBody().getMsg()).contains("cursor").contains("zzz");
    }
}
