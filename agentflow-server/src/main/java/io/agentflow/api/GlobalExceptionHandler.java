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
            case "EVENT_INPUT_INVALID", "INVALID_EVENT_INBOX_QUERY" -> HttpStatus.BAD_REQUEST;
            case "EVENT_UNAUTHENTICATED" -> HttpStatus.UNAUTHORIZED;
            case "EVENT_INGRESS_DISABLED" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "EVENT_BODY_TOO_LARGE" -> HttpStatus.PAYLOAD_TOO_LARGE;
            case "EVENT_SOURCE_CONFLICT", "EVENT_ID_CONFLICT" -> HttpStatus.CONFLICT;
            case "INVALID_EVENT_CONTRACT", "INVALID_EVENT_CONTRACT_QUERY", "INVALID_EVENT_WAIT_QUERY" -> HttpStatus.BAD_REQUEST;
            case "EVENT_CONTRACT_AVAILABILITY_UNCHANGED", "EVENT_CONTRACT_UNAVAILABLE" -> HttpStatus.CONFLICT;
            case "INVALID_COUNTERSIGN_QUERY", "INVALID_TIMER_QUERY", "INVALID_INSTANCE_QUERY", "INVALID_SUBPROCESS_QUERY" -> HttpStatus.BAD_REQUEST;
            case "COUNTERSIGN_STATE_INVALID", "COUNTERSIGN_MEMBER_UNAVAILABLE", "COUNTERSIGN_MEMBER_LIMIT", "COUNTERSIGN_MEMBER_EXISTS",
                    "COUNTERSIGN_LAST_MEMBER", "COUNTERSIGN_SELF_REMOVAL" -> HttpStatus.CONFLICT;
            case "PAYMENT_CALLBACK_INVALID", "INVALID_PAYMENT_BATCH" -> HttpStatus.BAD_REQUEST;
            case "PAYMENT_CALLBACK_UNAUTHENTICATED" -> HttpStatus.UNAUTHORIZED;
            case "PAYMENT_CALLBACK_DISABLED" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "PAYMENT_CALLBACK_TOO_LARGE" -> HttpStatus.PAYLOAD_TOO_LARGE;
            case "PAYMENT_CALLBACK_SOURCE_CONFLICT", "PAYMENT_CALLBACK_EVENT_CONFLICT" -> HttpStatus.CONFLICT;
            case "INVALID_PROCUREMENT_QUERY", "INVALID_SUPPLIER_PAYMENT_QUERY", "INVALID_BUDGET_ADJUSTMENT_QUERY" -> HttpStatus.BAD_REQUEST;
            case "SUPPLIER_PAYABLE_REVIEW_PENDING", "SUPPLIER_PAYABLE_REVIEW_STATE_CONFLICT", "SUPPLIER_PAYMENT_ALREADY_AUTHORIZED",
                    "SUPPLIER_PAYABLE_HOLD_STATE_CONFLICT", "SUPPLIER_AUTHORIZATION_RETIREMENT_UNSAFE", "SUPPLIER_PAYMENT_EXECUTION_PENDING",
                    "SUPPLIER_PAYMENT_ALREADY_REGISTERED", "SUPPLIER_PAYMENT_EXECUTION_STATE_CONFLICT", "SUPPLIER_PAYMENT_STATE_CONFLICT" -> HttpStatus.CONFLICT;
            case "PROCUREMENT_CHECK_ACTIVE", "PROCUREMENT_CHECK_STATE_CONFLICT", "PROCUREMENT_PAYABLE_OCCUPIED",
                    "BUDGET_ADJUSTMENT_CHECK_ACTIVE", "BUDGET_ADJUSTMENT_CHECK_STATE_CONFLICT",
                    "BUDGET_ADJUSTMENT_REVIEW_ACTIVE", "BUDGET_ADJUSTMENT_REVIEW_CONFLICT", "BUDGET_ADJUSTMENT_ALREADY_AUTHORIZED",
                    "BUDGET_ADJUSTMENT_OPERATION_CONFLICT", "BUDGET_ADJUSTMENT_RETIREMENT_UNSAFE",
                    "PROCUREMENT_RESERVATION_CONTEXT_CHANGED" -> HttpStatus.CONFLICT;
            case "INVALID_ATTACHMENT_QUERY", "INVALID_EXPENSE_QUERY", "INVALID_FINANCE_QUERY", "INVALID_INVOICE_QUERY", "INVALID_VOUCHER_QUERY", "INVALID_VOUCHER_REVERSAL_QUERY", "INVALID_PAYMENT_QUERY", "INVALID_ADVANCE_REPAYMENT_QUERY", "INVALID_EXPENSE_PAYMENT_RETURN_QUERY", "INVALID_EXPENSE_ADJUSTMENT_QUERY", "INVALID_SUPPLIER_PAYMENT_RETURN_QUERY" -> HttpStatus.BAD_REQUEST;
            case "ATTACHMENT_TOO_LARGE", "FILE_TOO_LARGE" -> HttpStatus.PAYLOAD_TOO_LARGE;
            case "ATTACHMENT_QUOTA_EXCEEDED", "AGENT_RUN_ACTIVE", "AGENT_INPUT_CHANGED", "AGENT_TARGET_CHANGED", "AGENT_RUN_STATE_CONFLICT",
                    "FINANCIAL_RESOURCE_EXISTS", "INVOICE_OCCUPIED", "INVOICE_OCCUPATION_CHANGED", "INVOICE_WALLET_QUOTA_EXCEEDED",
                    "INVOICE_VERIFICATION_ACTIVE", "INVOICE_VERIFICATION_STATE_CONFLICT", "FINANCE_TARGET_CHANGED",
                    "EXPENSE_PRECHECK_ACTIVE", "EXPENSE_PRECHECK_STATE_CONFLICT", "EXPENSE_PLAN_CHECK_ACTIVE", "EXPENSE_PLAN_CHECK_STATE_CONFLICT",
                    "ADVANCE_REQUEST_CHECK_ACTIVE", "ADVANCE_REQUEST_CHECK_STATE_CONFLICT", "ADVANCE_REPAYMENT_CHECK_PENDING", "ADVANCE_REPAYMENT_CHECK_CONFLICT", "ADVANCE_REPAYMENT_ALREADY_RECORDED", "BUDGET_OPERATION_PENDING", "BUDGET_TARGET_CHANGED",
                    "BUDGET_LEDGER_CONFLICT", "BUDGET_FINALIZED", "EXPENSE_RESERVATION_CHANGED",
                    "PAYMENT_AUTHORIZATION_EXISTS", "PAYMENT_EXECUTION_ALREADY_REQUESTED", "PAYMENT_AUTHORIZATION_STATE_CONFLICT", "PAYMENT_OPERATION_STATE_CONFLICT",
                    "VOUCHER_REVERSAL_PENDING", "VOUCHER_REVERSAL_ALREADY_RECORDED", "VOUCHER_REVERSAL_CHECK_CONFLICT",
                    "VOUCHER_REVERSAL_OPERATION_EXISTS", "VOUCHER_REVERSAL_OPERATION_CONFLICT", "VOUCHER_REVERSAL_PREPARATION_CONFLICT",
                    "VOUCHER_REVERSAL_RETIREMENT_UNSAFE", "VOUCHER_REVERSAL_ORIGINAL_RECHECK_REQUIRED",
                    "EXPENSE_PAYMENT_RETURN_PENDING", "EXPENSE_PAYMENT_RETURN_CHECK_CONFLICT", "EXPENSE_PAYMENT_RETURN_ALREADY_RECORDED",
                    "EXPENSE_PAYMENT_RETURN_EVIDENCE_CHANGED", "EXPENSE_PAYMENT_RETURN_OUTCOME_CHANGED",
                    "EXPENSE_PARTIAL_ADJUSTMENT_PENDING", "EXPENSE_PARTIAL_ADJUSTMENT_SOURCE_CHANGED", "EXPENSE_PARTIAL_ADJUSTMENT_CONFLICT", "EXPENSE_ADJUSTMENT_PENDING",
                    "EXPENSE_ADJUSTMENT_FUNDING_CHANGED", "EXPENSE_PARTIAL_ADJUSTMENT_PREPARATION_PENDING", "PARTIAL_ADJUSTMENT_PREPARATION_CONFLICT",
                    "BUDGET_REDUCTION_OPERATION_CONFLICT", "BUDGET_REDUCTION_AUTHORIZATION_EXPIRED", "EXPENSE_ACCRUAL_REDUCTION_OPERATION_CONFLICT",
                    "EXPENSE_ACCRUAL_REDUCTION_EXPIRED", "EXPENSE_ACCRUAL_REDUCTION_RETIREMENT_UNSAFE",
                    "BUDGET_REDUCTION_DISPUTE_UNRESOLVABLE", "EXPENSE_ACCRUAL_REDUCTION_DISPUTE_UNRESOLVABLE",
                    "SUPPLIER_SETTLEMENT_PENDING", "SUPPLIER_SETTLEMENT_PREPARATION_CONFLICT", "SUPPLIER_PAYABLE_SETTLEMENT_STATE_CONFLICT",
                    "SUPPLIER_PAYMENT_RETURN_PENDING", "SUPPLIER_PAYMENT_RETURN_CHECK_CONFLICT", "SUPPLIER_PAYMENT_RETURN_ALREADY_RECORDED",
                    "SUPPLIER_PAYMENT_RETURN_EVIDENCE_CHANGED", "SUPPLIER_PAYMENT_RETURN_OUTCOME_CHANGED", "SUPPLIER_PAYMENT_RETURN_REVIEW_REQUIRED",
                    "SUPPLIER_PAYMENT_RETURN_NOT_REQUIRED", "SUPPLIER_PAYMENT_RETURN_EVIDENCE_UNAVAILABLE", "SUPPLIER_PAYMENT_RETURN_PAYMENT_UNRESOLVED",
                    "SUPPLIER_PAYMENT_RETURN_SOURCE_CHANGED",
                    "SUPPLIER_ADJUSTMENT_PENDING", "SUPPLIER_ADJUSTMENT_SOURCE_CHANGED", "SUPPLIER_ADJUSTMENT_COMPLETION_CHANGED",
                    "SUPPLIER_ADJUSTMENT_PREPARATION_CONFLICT", "SUPPLIER_PAYABLE_ADJUSTMENT_STATE_CONFLICT", "SUPPLIER_PAYABLE_ADJUSTMENT_EVIDENCE_CHANGED", "SUPPLIER_ADJUSTMENT_RETIREMENT_UNSAFE",
                    "SUPPLIER_SETTLEMENT_RETIREMENT_UNSAFE" -> HttpStatus.CONFLICT;
            case "AGENT_MODEL_DISABLED", "AGENT_MODEL_UNCONFIGURED", "FINANCE_GATEWAY_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "ATTACHMENT_STORAGE_UNAVAILABLE", "ATTACHMENT_INTEGRITY_FAILED", "FILE_STORAGE_UNAVAILABLE", "FILE_INTEGRITY_FAILED" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "INVALID_ORGANIZATION_QUERY", "INVALID_AVAILABILITY_QUERY", "INVALID_AGENT_QUERY", "INVALID_DEFINITION_QUERY", "INVALID_WEBHOOK_QUERY", "INVALID_DIAGRAM_QUERY", "INVALID_AUDIT_QUERY", "INVALID_APPLICATION_QUERY", "INVALID_CALENDAR_QUERY", "INVALID_CALENDAR_CALCULATION", "INVALID_COMMENT_QUERY", "INVALID_FIRST_WORKFLOW_QUERY", "INVALID_OPERATIONS_QUERY", "INVALID_TASK_QUERY", "INVALID_INBOX_QUERY", "INVALID_WORKSPACE_QUERY", "INVALID_HISTORY_QUERY", "IDEMPOTENCY_KEY_REQUIRED", "INVALID_IDEMPOTENCY_KEY",
                    "INVALID_IDEMPOTENCY_REQUEST", "INVALID_TEMPLATE_COPY_REQUEST", "INVALID_SUPPLIER_SETTLEMENT_QUERY", "INVALID_SUPPLIER_DISPUTE_QUERY",
                    "INVALID_SUPPLIER_SETTLEMENT_DISPUTE_QUERY", "INVALID_SUPPLIER_ADJUSTMENT_DISPUTE_QUERY", "INVALID_SUPPLIER_ADJUSTMENT_QUERY",
                    "INVALID_EXPENSE_PARTIAL_ADJUSTMENT_QUERY" -> HttpStatus.BAD_REQUEST;
            case "UNAUTHENTICATED" -> HttpStatus.UNAUTHORIZED;
            case "FORBIDDEN", "PAYMENT_ACTOR_UNAVAILABLE" -> HttpStatus.FORBIDDEN;
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "ORGANIZATION_ALREADY_INITIALIZED", "ORGANIZATION_IDENTITY_CONFLICT", "ORGANIZATION_APPOINTMENT_CONFLICT", "DEFINITION_DISABLED", "DEFINITION_AVAILABILITY_UNCHANGED", "WEBHOOK_DELIVERY_CONFLICT", "WEBHOOK_TARGET_UNAVAILABLE", "CALENDAR_KEY_CONFLICT", "COUNTERSIGN_ASSIGNMENT_FIXED", "TASK_DELEGATION_PENDING", "TASK_DELEGATION_OWNER_MISSING", "CONCURRENCY_CONFLICT", "IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_KEY_EXPIRED", "DRAFT_VERSION_CONFLICT",
                    "DEFINITION_DEPLOYMENT_CONFLICT", "DEFINITION_BINDING_AMBIGUOUS", "TEMPLATE_VERSION_CONFLICT" -> HttpStatus.CONFLICT;
            case "APPLICATION_EXPORT_BUSY", "AUDIT_EXPORT_BUSY" -> HttpStatus.TOO_MANY_REQUESTS;
            case "DEPENDENCY_UNAVAILABLE", "APPLICATION_EXPORT_FAILED", "AUDIT_EXPORT_FAILED" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return org.springframework.http.ResponseEntity.status(status).body(Map.of(
                "code", exception.code(), "message", exception.getMessage(),
                "traceId", UUID.randomUUID().toString(), "path", request.getRequestURI()));
    }
}
