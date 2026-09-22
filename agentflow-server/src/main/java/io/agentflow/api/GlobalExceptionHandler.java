package io.agentflow.api;

import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;
import java.util.UUID;

/**
 * 将领域错误转换为稳定的 API 错误契约。
 * @author owlzhangfq@gmail.com
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    /** 处理请求体字段校验错误，统一返回 400 契约。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ":" + error.getDefaultMessage())
                .sorted().findFirst().orElse("Request validation failed");
        return org.springframework.http.ResponseEntity.badRequest().body(Map.of(
                "code", "INVALID_REQUEST", "message", message,
                "traceId", UUID.randomUUID().toString(), "path", request.getRequestURI()));
    }

    /** 处理领域规则错误。 */
    @ExceptionHandler(DomainException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> handleDomain(DomainException exception,
                                                                                       HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "UNAUTHENTICATED" -> HttpStatus.UNAUTHORIZED;
            case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "CONCURRENCY_CONFLICT", "IDEMPOTENCY_KEY_REUSED", "DRAFT_VERSION_CONFLICT",
                    "DEFINITION_DEPLOYMENT_CONFLICT" -> HttpStatus.CONFLICT;
            case "DEPENDENCY_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return org.springframework.http.ResponseEntity.status(status).body(Map.of(
                "code", exception.code(), "message", exception.getMessage(),
                "traceId", UUID.randomUUID().toString(), "path", request.getRequestURI()));
    }
}
