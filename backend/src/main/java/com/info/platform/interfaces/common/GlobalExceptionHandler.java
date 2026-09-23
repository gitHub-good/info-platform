package com.info.platform.interfaces.common;

import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理器（接口层横切）：业务异常→对应状态码、参数校验失败→2xxx、兜底→5xxx。
 *
 * <p>统一以 {@link Result} 包装返回；不向调用方泄露堆栈（兜底异常仅记 ERROR 告警）。参数类型不匹配（如 {@code cursor=zzz} 到 {@code
 * Long}）按参数校验口径 400/2001，不落兜底 5xxx（DEFECT-2）；HTTP 方法不支持映射 405/2002（ISSUE-C，不落兜底 5xxx）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException ex) {
        log.warn("业务异常: code={}, msg={}", ex.getErrorCode().getCode(), ex.getMessage());
        return ResponseEntity.status(ex.getErrorCode().getHttpStatus())
                .body(Result.fail(ex.getErrorCode(), ex.getDetail()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String detail =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                        .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", detail);
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.getHttpStatus())
                .body(Result.fail(ErrorCode.PARAM_INVALID, detail));
    }

    /** 请求参数类型不匹配（DEFECT-2）：与参数校验同口径 400/2001，msg 带字段与非法值。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String requiredType =
                ex.getRequiredType() == null ? "合法类型" : ex.getRequiredType().getSimpleName();
        String detail = ex.getName() + ": 须为 " + requiredType + "（收到 " + ex.getValue() + "）";
        log.warn("参数类型不匹配: {}", detail);
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.getHttpStatus())
                .body(Result.fail(ErrorCode.PARAM_INVALID, detail));
    }

    /**
     * HTTP 方法不支持（ISSUE-C）：如 {@code GET /api/v1/datasource-configs/aggregation/global}（仅注册
     * PATCH）。此前该异常被下方兜底 {@code Exception} 处理器吞成 500/50000，现按请求级错误映射 405/2002， 并按 RFC 9110 回 {@code
     * Allow} 头。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {
        Set<HttpMethod> supported = ex.getSupportedHttpMethods();
        String allowed =
                supported == null
                        ? "?"
                        : supported.stream()
                                .map(HttpMethod::name)
                                .collect(Collectors.joining(", "));
        String detail = ex.getMethod() + " 不被支持（允许: " + allowed + "）";
        log.warn("HTTP 方法不支持: {}", detail);
        return ResponseEntity.status(ErrorCode.METHOD_NOT_SUPPORTED.getHttpStatus())
                .allow(supported == null ? new HttpMethod[0] : supported.toArray(HttpMethod[]::new))
                .body(Result.fail(ErrorCode.METHOD_NOT_SUPPORTED, detail));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception ex) {
        log.error("未处理异常", ex);
        return ResponseEntity.status(ErrorCode.SERVER_ERROR.getHttpStatus())
                .body(Result.fail(ErrorCode.SERVER_ERROR));
    }
}
