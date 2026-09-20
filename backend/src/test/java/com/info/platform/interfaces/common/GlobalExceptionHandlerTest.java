package com.info.platform.interfaces.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * 全局异常处理器单测（T18）：BusinessException 按 ErrorCode 映射正确 HTTP 状态码与 code； 兜底异常映射 500/50000。纯单元测试，不启
 * Spring 上下文。
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
}
