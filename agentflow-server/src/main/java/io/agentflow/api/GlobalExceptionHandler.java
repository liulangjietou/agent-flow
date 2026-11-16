package io.agentflow.api;

import io.agentflow.common.DomainException;
import io.agentflow.common.ErrorCode;
import io.agentflow.definition.DefinitionValidationException;
import io.agentflow.expense.InvoiceOccupationConflict;
import io.agentflow.form.FormValidationException;
import io.agentflow.observability.RequestTrace;
import jakarta.servlet.http.HttpServletRequest;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.sql.SQLTransactionRollbackException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将领域错误转换为稳定的 API 错误契约；HTTP 状态统一取自 {@link ErrorCode}，此处不再维护平行映射。
 * @author owlzhangfq@gmail.com
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 发票竞争在回滚后返回当前授权来源；禁止缓存包含业务单号的错误响应。 */
    @ExceptionHandler(InvoiceOccupationConflict.class)
    public ResponseEntity<Map<String, Object>> handleInvoiceOccupation(InvoiceOccupationConflict exception,
                                                                       HttpServletRequest request) {
        Map<String, Object> response = new LinkedHashMap<>(handleDomain(exception, request).getBody());
        response.put("details", Map.of("invoiceConflicts", exception.conflicts()));
        // 发票占用冲突固定返回 409：错误码可能是 RESOURCES_CHANGED 等业务码，但这里的语义始终是资源被他人占用。
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore()).body(response);
    }

    /**
     * 引擎乐观锁、数据库死锁、锁等待超时和序列化失败统一返回冲突。
     * MyBatis-Spring 会把驱动异常翻译为 {@link ConcurrencyFailureException} 家族，原生 SQL 异常只在未经翻译的 JDBC 路径出现。
     */
    @ExceptionHandler({FlowableOptimisticLockingException.class, SQLTransactionRollbackException.class,
            ConcurrencyFailureException.class})
    public ResponseEntity<Map<String, Object>> handleConcurrency(Exception exception, HttpServletRequest request) {
        LOG.info("Concurrent modification rejected, errorCode={}, path={}, exceptionType={}", ErrorCode.CONCURRENCY_CONFLICT,
                request.getRequestURI(), exception.getClass().getSimpleName());
        return handleDomain(new DomainException(ErrorCode.CONCURRENCY_CONFLICT, "The process was changed by another request"), request);
    }

    /** 处理请求体字段校验错误，统一返回 400 契约。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception,
                                                                HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ":" + error.getDefaultMessage())
                .sorted().findFirst().orElse("Request validation failed");
        return invalidRequest(message, request);
    }

    /** 必填查询参数在进入控制器前缺失时仍返回统一错误契约。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParameter(MissingServletRequestParameterException exception,
                                                                      HttpServletRequest request) {
        return invalidRequest("Required request parameter is missing: " + exception.getParameterName(), request);
    }

    /** 查询或路径参数类型转换失败时只返回参数名，不回显提交值和底层异常。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleParameterTypeMismatch(MethodArgumentTypeMismatchException exception,
                                                                           HttpServletRequest request) {
        return invalidRequest("Request parameter is invalid: " + exception.getName(), request);
    }

    /** 表单错误包含稳定的字段规则码，不回显敏感填写值。 */
    @ExceptionHandler(FormValidationException.class)
    public ResponseEntity<Map<String, Object>> handleForm(FormValidationException exception, HttpServletRequest request) {
        Map<String, Object> response = new LinkedHashMap<>(handleDomain(exception, request).getBody());
        response.put("details", Map.of("fieldErrors", exception.fieldErrors()));
        return ResponseEntity.status(exception.status().httpStatus()).body(response);
    }

    /** 图校验返回规则码与对象标识，便于设计器定位错误。 */
    @ExceptionHandler(DefinitionValidationException.class)
    public ResponseEntity<Map<String, Object>> handleDefinition(DefinitionValidationException exception,
                                                                HttpServletRequest request) {
        Map<String, Object> response = new LinkedHashMap<>(handleDomain(exception, request).getBody());
        response.put("details", Map.of("definitionErrors", exception.errors()));
        return ResponseEntity.status(exception.status().httpStatus()).body(response);
    }

    /** JSON 解码中的领域值对象结构错误（含请求体超限）仍保持稳定的业务错误码。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException exception,
                                                                HttpServletRequest request) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof DomainException domain) return handleDomain(domain, request);
            cause = cause.getCause();
        }
        return invalidRequest("Request JSON is invalid", request);
    }

    /** 处理领域规则错误。 */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Map<String, Object>> handleDomain(DomainException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status().httpStatus()).body(body(exception.code(), exception.getMessage(), request));
    }

    private ResponseEntity<Map<String, Object>> invalidRequest(String message, HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.status().httpStatus())
                .body(body(ErrorCode.INVALID_REQUEST.name(), message, request));
    }

    private static Map<String, Object> body(String code, String message, HttpServletRequest request) {
        return Map.of("code", code, "message", message == null ? code : message,
                "traceId", RequestTrace.id(request), "path", request.getRequestURI());
    }
}
