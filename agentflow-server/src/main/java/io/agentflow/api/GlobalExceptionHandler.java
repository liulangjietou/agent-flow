package io.agentflow.api;

import io.agentflow.common.DomainException;
import io.agentflow.form.FormValidationException;
import io.agentflow.definition.DefinitionValidationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import jakarta.servlet.http.HttpServletRequest;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.sql.SQLTransactionRollbackException;
import java.util.Map;
import java.util.UUID;

/**
 * 将领域错误转换为稳定的 API 错误契约。
 * @author owlzhangfq@gmail.com
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    /** 引擎乐观锁或数据库已回滚的并发事务统一返回冲突；无关数据库异常保留原错误。 */
    @ExceptionHandler({FlowableOptimisticLockingException.class, SQLTransactionRollbackException.class})
    public org.springframework.http.ResponseEntity<Map<String, Object>> handleConcurrency(
            Exception exception, HttpServletRequest request) {
        return handleDomain(new DomainException("CONCURRENCY_CONFLICT", "The process was changed by another request"), request);
    }

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

    /** 表单错误包含稳定的字段规则码，不回显敏感填写值。 */
    @ExceptionHandler(FormValidationException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> handleForm(FormValidationException exception,
                                                                                   HttpServletRequest request) {
        Map<String, Object> response = new java.util.LinkedHashMap<>(handleDomain(exception, request).getBody());
        response.put("details", Map.of("fieldErrors", exception.fieldErrors()));
        return org.springframework.http.ResponseEntity.unprocessableEntity().body(response);
    }

    /** 图校验返回规则码与对象标识，便于设计器定位错误。 */
    @ExceptionHandler(DefinitionValidationException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> handleDefinition(DefinitionValidationException exception,
                                                                                        HttpServletRequest request) {
        Map<String, Object> response = new java.util.LinkedHashMap<>(handleDomain(exception, request).getBody());
        response.put("details", Map.of("definitionErrors", exception.errors()));
        return org.springframework.http.ResponseEntity.unprocessableEntity().body(response);
    }

    /** JSON 解码中的领域值对象结构错误仍保持稳定的业务错误码。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException exception,
                                                                                        HttpServletRequest request) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof DomainException domain) return handleDomain(domain, request);
            cause = cause.getCause();
        }
        return org.springframework.http.ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST",
                "message", "Request JSON is invalid", "traceId", UUID.randomUUID().toString(), "path", request.getRequestURI()));
    }

    /** 处理领域规则错误。 */
    @ExceptionHandler(DomainException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> handleDomain(DomainException exception,
                                                                                       HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "INVALID_TASK_QUERY", "INVALID_INBOX_QUERY", "INVALID_WORKSPACE_QUERY", "INVALID_HISTORY_QUERY", "IDEMPOTENCY_KEY_REQUIRED", "INVALID_IDEMPOTENCY_KEY",
                    "INVALID_IDEMPOTENCY_REQUEST", "INVALID_TEMPLATE_COPY_REQUEST" -> HttpStatus.BAD_REQUEST;
            case "UNAUTHENTICATED" -> HttpStatus.UNAUTHORIZED;
            case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "COUNTERSIGN_ASSIGNMENT_FIXED", "TASK_DELEGATION_PENDING", "TASK_DELEGATION_OWNER_MISSING", "CONCURRENCY_CONFLICT", "IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_KEY_EXPIRED", "DRAFT_VERSION_CONFLICT",
                    "DEFINITION_DEPLOYMENT_CONFLICT", "DEFINITION_BINDING_AMBIGUOUS", "TEMPLATE_VERSION_CONFLICT" -> HttpStatus.CONFLICT;
            case "DEPENDENCY_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return org.springframework.http.ResponseEntity.status(status).body(Map.of(
                "code", exception.code(), "message", exception.getMessage(),
                "traceId", UUID.randomUUID().toString(), "path", request.getRequestURI()));
    }
}
