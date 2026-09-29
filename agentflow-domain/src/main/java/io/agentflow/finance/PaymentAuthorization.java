package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 已过账依据上的人工付款授权，执行登记固定出纳和命令，不能再次换人、换账户或延期。
 * @author owlzhangfq@gmail.com
 */
public record PaymentAuthorization(Terms terms, Decision decision, long version, Status status, Instant updatedAt,
                                   Execution execution, Withdrawal withdrawal, Retirement retirement) {
    public static final Duration MAX_VALIDITY = Duration.ofHours(24);

    /** 恢复快照时核对授权与已登记命令一致，不能把已登记执行恢复成可二次执行。 */
    public PaymentAuthorization {
        if (terms == null || decision == null || status == null || updatedAt == null || updatedAt.isBefore(decision.authorizedAt())
                || terms.payee().employeeId().equals(decision.authorizedBy()) || (status == Status.RETIRED) != (retirement != null)) throw invalid();
        switch (status) {
            case AUTHORIZED -> { if (version != 1 || execution != null || withdrawal != null || !updatedAt.equals(decision.authorizedAt())) throw invalid(); }
            case EXECUTION_REGISTERED -> {
                if (version != 2 || execution == null || withdrawal != null || !updatedAt.equals(execution.registeredAt())
                        || !execution.command().equals(command(terms, decision, execution.command().authorization().executedBy(), execution.debitAccount()))) throw invalid();
                execution.command().requireSendAt(execution.registeredAt());
            }
            case VOIDED -> {
                if (version != 2 || execution != null || withdrawal == null || !updatedAt.equals(withdrawal.withdrawnAt())
                        || terms.payee().employeeId().equals(withdrawal.withdrawnBy())) throw invalid();
            }
            case EXPIRED -> { if (version != 2 || execution != null || withdrawal != null || updatedAt.isBefore(decision.expiresAt())) throw invalid(); }
            case RETIRED -> {
                if (version != 3 || execution == null || withdrawal != null || !updatedAt.equals(retirement.retiredAt())
                        || updatedAt.isBefore(execution.registeredAt()) || terms.payee().employeeId().equals(retirement.retiredBy())
                        || execution.command().authorization().executedBy().equals(retirement.retiredBy())
                        || !execution.command().equals(command(terms, decision, execution.command().authorization().executedBy(), execution.debitAccount()))) throw invalid();
                execution.command().requireSendAt(execution.registeredAt());
            }
        }
    }

    /** 兼容既有未结束授权的构造与历史快照，结束证据只出现在第三版。 */
    public PaymentAuthorization(Terms terms, Decision decision, long version, Status status, Instant updatedAt, Execution execution, Withdrawal withdrawal) {
        this(terms, decision, version, status, updatedAt, execution, withdrawal, null);
    }

    /** 金额、轮次、目标和凭证引用均从已保存的实际过账派生，财务只能决定是否授权及期限。 */
    public static PaymentAuthorization issue(UUID id, VoucherOperation voucher, EmployeeAccountSnapshot payee, String authorizer, Instant now, Instant expiresAt) {
        if (voucher == null || !voucher.usablePosted() || now == null || voucher.updatedAt().isAfter(now)) throw changedVoucher();
        var source = voucher.input().command(); var binding = source.binding();
        if (source.kind() == VoucherCommand.Kind.PAYMENT || payee == null || !payee.employeeId().equals(source.employeeId()) || !payee.legalEntityId().equals(source.legalEntityId())) throw changedVoucher();
        var amount = source.totals().payable();
        if (amount.value().signum() == 0) throw new DomainException("PAYMENT_NOT_REQUIRED", "Zero payable settlements do not create payment authorizations");
        var terms = new Terms(id, source.tenantId(), source.kind() == VoucherCommand.Kind.EMPLOYEE_ADVANCE ? PaymentCommand.Purpose.EMPLOYEE_ADVANCE : PaymentCommand.Purpose.EXPENSE_REIMBURSEMENT,
                new PaymentCommand.Binding(binding.businessId(), binding.applicationId(), binding.roundNo(), binding.applicationVersion(), binding.businessVersion()),
                amount, payee, source.id(), source.digest(), voucher.highestRevision(), voucher.observation().voucherReference(), voucher.input().targetDigest());
        return new PaymentAuthorization(terms, new Decision(authorizer, now, expiresAt), 1, Status.AUTHORIZED, now, null, null);
    }

    /** 只从该出纳的当前目录选账户；原收款账户已变化或证据过期时不能固定付款命令。 */
    public PaymentAuthorization registerExecution(String cashier, PaymentAccountsPort.Directory directory, String debitReference,
                                                   EmployeeAccountPort.Account currentPayee, VoucherOperation currentVoucher, Instant now) {
        requireAuthorized(now);
        if (invalidText(cashier) || cashier.equals(decision.authorizedBy()) || cashier.equals(terms.payee().employeeId())) {
            throw new DomainException("PAYMENT_SEPARATION_REQUIRED", "Applicant, payment authorizer and cashier must be different people");
        }
        if (!matchesVoucher(currentVoucher, now)) throw changedVoucher();
        if (currentPayee == null || !currentPayee.snapshot().equals(terms.payee()) || !currentPayee.validUntil().isAfter(now)) {
            throw new DomainException("PAYMENT_ACCOUNT_CHANGED", "Original payee account must remain current and valid");
        }
        if (directory == null || !directory.matches(new PaymentAccountsPort.Request(terms.payee().legalEntityId(), terms.amount().currency(), cashier), now)) {
            throw new DomainException("PAYMENT_DEBIT_ACCOUNT_UNAVAILABLE", "A current debit account directory for the original cashier and legal entity is required");
        }
        var debit = directory.account(debitReference, now); var command = command(terms, decision, cashier, debit);
        Instant until = directory.validUntil().isBefore(currentPayee.validUntil()) ? directory.validUntil() : currentPayee.validUntil();
        return new PaymentAuthorization(terms, decision, 2, Status.EXECUTION_REGISTERED, now, new Execution(command, debit, now, until), null);
    }

    /** 已登记执行后只允许查询原交易，不能通过作废授权取消可能已被资金系统受理的付款。 */
    public PaymentAuthorization voidBeforeExecution(String actor, String reason, Instant now) {
        requireAuthorized(now);
        return new PaymentAuthorization(terms, decision, 2, Status.VOIDED, now, null, new Withdrawal(actor, reason, now));
    }

    /** 未登记执行的授权到期可关闭；已登记的原交易仍由支付执行状态机追踪。 */
    public PaymentAuthorization expire(Instant now) {
        if (status != Status.AUTHORIZED || now == null || now.isBefore(decision.expiresAt())) throw conflict();
        return new PaymentAuthorization(terms, decision, 2, Status.EXPIRED, now, null, null);
    }

    /** 操作者仍由入口验证财务岗位；实体另保证申请人及原出纳不能结束自己的付款。 */
    public boolean canRetire(PaymentOperation operation, String actor) {
        return status == Status.EXECUTION_REGISTERED && !invalidText(actor) && !terms.payee().employeeId().equals(actor)
                && !execution.command().authorization().executedBy().equals(actor) && matchesOperation(operation) && operation.retirementBasis() != null;
    }

    /** 跨聚合编排须先停止未发送队列，再以实际原执行版本结束授权，不能仅凭人工说明释放占用。 */
    public PaymentAuthorization retire(PaymentOperation operation, String actor, String reason, Instant now) {
        if (!canRetire(operation, actor) || operation.running() || operation.status() == PaymentOperation.Status.QUEUED
                || now == null || now.isBefore(operation.updatedAt()) || now.isBefore(updatedAt)) {
            throw new DomainException("PAYMENT_RETIREMENT_UNSAFE", "Original payment is not proven safely finished for this finance actor");
        }
        return new PaymentAuthorization(terms, decision, 3, Status.RETIRED, now, execution, null,
                new Retirement(actor, reason, now, operation.version(), operation.retirementBasis()));
    }

    /** 恢复结束记录时与保留的原执行修订交叉核验，防止快照声明替代实际资金证据。 */
    public boolean matchesRetirement(PaymentOperation proof) {
        return status == Status.RETIRED && matchesOperation(proof) && !proof.running() && proof.status() != PaymentOperation.Status.QUEUED
                && retirement.operationVersion() == proof.version() && retirement.basis() == proof.retirementBasis() && !proof.updatedAt().isAfter(retirement.retiredAt());
    }

    private boolean matchesOperation(PaymentOperation operation) {
        return operation != null && execution != null && operation.input().command().equals(execution.command())
                && operation.input().targetDigest().equals(terms.targetDigest()) && operation.input().debitAccount().equals(execution.debitAccount());
    }

    /** 重新核对已过账事实，查询中、冲突、已冲销或更换命令的凭证均不可继续付款。 */
    public boolean matchesVoucher(VoucherOperation voucher, Instant now) {
        if (voucher == null || now == null || !voucher.usablePosted() || voucher.updatedAt().isAfter(now)) return false;
        var source = voucher.input().command(); var binding = source.binding();
        return source.kind() != VoucherCommand.Kind.PAYMENT
                && terms.tenantId().equals(source.tenantId()) && terms.amount().equals(source.totals().payable())
                && terms.purpose() == (source.kind() == VoucherCommand.Kind.EMPLOYEE_ADVANCE ? PaymentCommand.Purpose.EMPLOYEE_ADVANCE : PaymentCommand.Purpose.EXPENSE_REIMBURSEMENT)
                && terms.binding().equals(new PaymentCommand.Binding(binding.businessId(), binding.applicationId(), binding.roundNo(), binding.applicationVersion(), binding.businessVersion()))
                && terms.payee().legalEntityId().equals(source.legalEntityId()) && terms.payee().employeeId().equals(source.employeeId())
                && terms.voucherOperationId().equals(source.id()) && terms.voucherCommandDigest().equals(source.digest())
                && terms.targetDigest().equals(voucher.input().targetDigest()) && terms.voucherReference().equals(voucher.observation().voucherReference())
                && voucher.highestRevision() >= terms.voucherRevision();
    }

    private void requireAuthorized(Instant now) {
        if (status != Status.AUTHORIZED || now == null || now.isBefore(updatedAt)) throw conflict();
        if (!now.isBefore(decision.expiresAt())) throw new DomainException("PAYMENT_AUTHORIZATION_EXPIRED", "Payment authorization is outside its execution window");
    }
    private static PaymentCommand command(Terms terms, Decision decision, String cashier, PaymentAccountsPort.DebitAccount debit) {
        return new PaymentCommand(terms.id(), terms.tenantId(), terms.purpose(), terms.binding(), terms.amount(), debit.reference(), terms.payee(), terms.voucherReference(),
                new PaymentCommand.Authorization(decision.authorizedBy(), cashier, decision.authorizedAt(), decision.expiresAt()));
    }
    private static boolean invalidText(String value) { return StringUtils.isBlank(value) || value.length() > 128; }
    private static boolean digest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static DomainException invalid() { return new DomainException("INVALID_PAYMENT_AUTHORIZATION", "Payment authorization must preserve original approved terms and separated execution"); }
    private static DomainException conflict() { return new DomainException("PAYMENT_AUTHORIZATION_STATE_CONFLICT", "Payment authorization no longer allows this transition"); }
    private static DomainException changedVoucher() { return new DomainException("PAYMENT_VOUCHER_CHANGED", "Original accounting voucher is not a current usable posted fact"); }

    /** 日志只显示标识与状态，不泄露账户、金额或人员。 */
    @Override public String toString() { return "PaymentAuthorization[id=" + terms.id() + ", version=" + version + ", status=" + status + "]"; }

    /**
     * 付款对象一经财务授权即不可变化，与挂账凭证的原命令和外部版本关联。
     * @author owlzhangfq@gmail.com
     */
    public record Terms(UUID id, String tenantId, PaymentCommand.Purpose purpose, PaymentCommand.Binding binding, Money amount,
                        EmployeeAccountSnapshot payee, UUID voucherOperationId, String voucherCommandDigest, long voucherRevision,
                        String voucherReference, String targetDigest) {
        /** 零额和无过账依据不构成有效付款授权。 */
        public Terms {
            if (id == null || invalidText(tenantId) || tenantId.length() > 64 || purpose == null || binding == null || amount == null
                    || amount.value().signum() <= 0 || payee == null || voucherOperationId == null || !digest(voucherCommandDigest) || voucherRevision < 1
                    || invalidText(voucherReference) || !digest(targetDigest)) throw invalid();
        }
        /** 账户信息只留在受控持久事实中。 */
        @Override public String toString() { return "PaymentAuthorizationTerms[id=" + id + ", purpose=" + purpose + "]"; }
    }

    /**
     * 短期人工授权不自动续期；应用配置可进一步缩短，最多二十四小时。
     * @author owlzhangfq@gmail.com
     */
    public record Decision(String authorizedBy, Instant authorizedAt, Instant expiresAt) {
        /** 到期为排他边界，恢复不能延长原授权。 */
        public Decision {
            if (invalidText(authorizedBy) || authorizedAt == null || expiresAt == null || !expiresAt.isAfter(authorizedAt)
                    || Duration.between(authorizedAt, expiresAt).compareTo(MAX_VALIDITY) > 0) throw invalid();
        }
    }

    /**
     * 只记录一次实际出纳选择以及读取时有效的账户依据，命令编号始终等于原授权号。
     * @author owlzhangfq@gmail.com
     */
    public record Execution(PaymentCommand command, PaymentAccountsPort.DebitAccount debitAccount, Instant registeredAt, Instant accountsValidUntil) {
        /** 同币种原账户引用和有效读取时刻必须同时满足。 */
        public Execution {
            if (command == null || debitAccount == null || registeredAt == null || accountsValidUntil == null || !accountsValidUntil.isAfter(registeredAt)
                    || !command.debitAccountReference().equals(debitAccount.reference()) || !command.amount().currency().equals(debitAccount.currency())) throw invalid();
        }
        /** 日志不输出付款命令明细或账户证据。 */
        @Override public String toString() { return "PaymentExecution[authorizationId=" + command.id() + "]"; }
    }

    /**
     * 作废仅针对未登记执行的授权，保留操作者和原因。
     * @author owlzhangfq@gmail.com
     */
    public record Withdrawal(String withdrawnBy, String reason, Instant withdrawnAt) {
        /** 原因作为审计事实保留，不允许空说明。 */
        public Withdrawal { if (invalidText(withdrawnBy) || StringUtils.isBlank(reason) || reason.length() > 2000 || withdrawnAt == null) throw invalid(); reason = reason.trim(); }
    }

    /**
     * 安全结束保留财务决定与原执行修订；已发送且未知的交易无法形成此证据。
     * @author owlzhangfq@gmail.com
     */
    public record Retirement(String retiredBy, String reason, Instant retiredAt, long operationVersion, PaymentOperation.RetirementBasis basis) {
        /** 原因和版本必填，已执行付款不能通过无依据的结束快照恢复。 */
        public Retirement {
            if (invalidText(retiredBy) || StringUtils.isBlank(reason) || reason.length() > 2000 || retiredAt == null || operationVersion < 1 || basis == null) throw invalid();
            reason = reason.trim();
        }
    }

    /**
     * 执行已登记不等于已提交资金系统，更不等于付款成功。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { AUTHORIZED, EXECUTION_REGISTERED, VOIDED, EXPIRED, RETIRED }
}
