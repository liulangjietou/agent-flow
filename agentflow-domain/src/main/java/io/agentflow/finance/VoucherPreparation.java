package io.agentflow.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.UUID;

/**
 * 准备任务只获取会计依据，失败重查与实际凭证重发分开；迟到结果不能登记凭证。
 * @author owlzhangfq@gmail.com
 */
public record VoucherPreparation(Input input, long version, Status status, Instant createdAt, Instant startedAt,
                                 Instant leaseUntil, Instant completedAt, Result result,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) AccountMappingPort.Request mappingRequest) {
    /** 每次只读准备有独立编号、固定批准版本和三版生命周期。 */
    public VoucherPreparation {
        if (input == null || status == null || createdAt == null || version < 1 || version > 3
                || startedAt != null && startedAt.isBefore(createdAt)
                || status == Status.QUEUED && (version != 1 || startedAt != null || leaseUntil != null)
                || status != Status.QUEUED && (startedAt == null || leaseUntil == null || !leaseUntil.isAfter(startedAt))
                || status == Status.RUNNING && version != 2) throw invalid();
        boolean active = status == Status.QUEUED || status == Status.RUNNING;
        if (active ? result != null || completedAt != null : version != 3 || result == null || result.status() != status || completedAt == null || completedAt.isBefore(startedAt)) throw invalid();
        if (status == Status.READY && (input.targetDigest() == null || !input.id().equals(result.operationId()) || !completedAt.isBefore(leaseUntil))) throw invalid();
        if (mappingRequest != null) {
            if (status == Status.QUEUED || input.targetDigest() == null) throw invalid();
            var managed = mappingRequest.managedMapping();
            if (managed != null && (!managed.tenantId().equals(input.source().tenantId()) || !managed.selection().targetDigest().equals(input.targetDigest()))) throw invalid();
        }
    }
    /** 恢复升级前的状态不添加选择；新领取由应用服务明确绑定后再读取 ERP。 */
    public VoucherPreparation(Input input, long version, Status status, Instant createdAt, Instant startedAt,
                              Instant leaseUntil, Instant completedAt, Result result) {
        this(input, version, status, createdAt, startedAt, leaseUntil, completedAt, result, null);
    }
    /** 批准事务只入队，即使 ERP 尚未配置也能保留待处理事实。 */
    public static VoucherPreparation queue(Input input, Instant now) { return new VoucherPreparation(input, 1, Status.QUEUED, now, null, null, null, null); }
    /** 领取只读准备的租约，跨执行器通过仓储版本隔离。 */
    public VoucherPreparation start(Instant now, Instant until) {
        return start(now, until, null);
    }
    /** 固定领取时的必要科目及管理版本，终态保留原选择用于恢复与审计。 */
    public VoucherPreparation start(Instant now, Instant until, AccountMappingPort.Request request) {
        if (status != Status.QUEUED) throw conflict();
        return new VoucherPreparation(input, 2, Status.RUNNING, createdAt, now, until, null, null, request);
    }
    /** 过期只能记录不可用，不能把迟到会计依据转成可发送命令。 */
    public VoucherPreparation finish(Result result, Instant now) {
        if (status != Status.RUNNING) throw conflict();
        var value = expired(now) ? Result.unavailable("LEASE_EXPIRED") : result;
        return new VoucherPreparation(input, 3, value.status(), createdAt, startedAt, leaseUntil, now, value, mappingRequest);
    }
    public boolean active() { return status == Status.QUEUED || status == Status.RUNNING; }
    public boolean expired(Instant now) { return status == Status.RUNNING && !leaseUntil.isAfter(now); }
    private static DomainException invalid() { return new DomainException("INVALID_VOUCHER_PREPARATION", "Voucher preparation identity and lifecycle must match"); }
    private static DomainException conflict() { return new DomainException("VOUCHER_PREPARATION_STATE_CONFLICT", "Voucher preparation is no longer executable"); }

    /**
     * 挂账绑定批准双版本，付款另绑定实际成功回单的本地修订，不随重复查询漂移。
     * @author owlzhangfq@gmail.com
     */
    public record Source(String tenantId, BusinessReference.Type businessType, UUID businessId, UUID applicationId,
                         int roundNo, long applicationVersion, long businessVersion, String employeeId,
                         UUID paymentOperationId, Long paymentVersion) {
        /** 明确业务类型，不能让普通表单自行声明财务金额。 */
        public Source {
            if (StringUtils.isBlank(tenantId) || tenantId.length() > 64 || businessId == null || applicationId == null || roundNo < 1
                    || applicationVersion < 1 || businessVersion < 1 || StringUtils.isBlank(employeeId) || employeeId.length() > 128
                    || businessType != BusinessReference.Type.ADVANCE_REQUEST && businessType != BusinessReference.Type.EXPENSE
                    || (paymentOperationId == null) != (paymentVersion == null) || paymentVersion != null && paymentVersion < 1) throw invalid();
        }
        /** 原挂账输入继续保持相同 JSON；缺少付款绑定不能成为付款凭证。 */
        public Source(String tenantId, BusinessReference.Type businessType, UUID businessId, UUID applicationId,
                      int roundNo, long applicationVersion, long businessVersion, String employeeId) {
            this(tenantId, businessType, businessId, applicationId, roundNo, applicationVersion, businessVersion, employeeId, null, null);
        }
        public VoucherCommand.Kind kind() { return paymentOperationId != null ? VoucherCommand.Kind.PAYMENT
                : businessType == BusinessReference.Type.ADVANCE_REQUEST ? VoucherCommand.Kind.EMPLOYEE_ADVANCE : VoucherCommand.Kind.EXPENSE_ACCRUAL; }
    }
    /**
     * 每次准备绑定请求者与当时目标，凭据不进入任务。
     * @author owlzhangfq@gmail.com
     */
    public record Input(UUID id, Source source, long attempt, String requestedBy, String targetDigest) {
        /** 未配置可以排队并形成可见错误，重试需创建新的只读准备尝试。 */
        public Input {
            if (id == null || source == null || attempt < 1 || StringUtils.isBlank(requestedBy) || requestedBy.length() > 128
                    || targetDigest != null && !targetDigest.matches("[a-f0-9]{64}")) throw invalid();
        }
    }
    /**
     * READY 仅表示实际凭证已登记，NOT_REQUIRED 仅表示金额为零不需要凭证。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, READY, BLOCKED, UNAVAILABLE, VOIDED, NOT_REQUIRED }
    /**
     * 凭证身份与稳定错误互斥，不保存任意外部文字。
     * @author owlzhangfq@gmail.com
     */
    public record Result(Status status, UUID operationId, String code) {
        /** 无金额影响仍须由后续结算独立确认，不在这里宣布业务结清。 */
        public Result {
            if (status == null || status == Status.QUEUED || status == Status.RUNNING
                    || status == Status.READY && (operationId == null || code != null)
                    || status != Status.READY && (operationId != null || code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}"))
                    || status == Status.NOT_REQUIRED && !"ZERO_AMOUNT".equals(code)) throw invalid();
        }
        /** 成功与凭证登记使用相同编号且同事务保存。 */
        public static Result ready(UUID operation) { return new Result(Status.READY, operation, null); }
        /** 外部服务不可用没有业务拒绝含义。 */
        public static Result unavailable(String code) { return new Result(Status.UNAVAILABLE, null, code); }
        /** 外部或本地业务规则明确阻断。 */
        public static Result blocked(String code) { return new Result(Status.BLOCKED, null, code); }
        /** 原批准依据变化，旧准备不再有效。 */
        public static Result voided() { return new Result(Status.VOIDED, null, "SOURCE_CHANGED"); }
        /** 零金额不产生虚假的零金额会计凭证。 */
        public static Result notRequired() { return new Result(Status.NOT_REQUIRED, null, "ZERO_AMOUNT"); }
    }
}
