package io.agentflow.finance;

import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.DomainException;
import io.agentflow.expense.AdvanceRequestRepository;
import io.agentflow.expense.ExpenseReportRepository;
import io.agentflow.expense.JdbcExpenseSubmissionControlRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 凭证执行的短事务编排，按申请和财务聚合锁串行化本轮副作用。
 * @author owlzhangfq@gmail.com
 */
@Service
public class VoucherOperationService {
    private final ApplicationRepository applications;
    private final AdvanceRequestRepository advances;
    private final ExpenseReportRepository expenses;
    private final JdbcExpenseSubmissionControlRepository controls;
    private final JdbcBudgetOccupationRepository budgets;
    private final JdbcVoucherOperationRepository operations;
    private final ApplicationEventPublisher events;
    private final Duration lease;

    /** 领取租约有界，网络调用不进入本服务的事务。 */
    public VoucherOperationService(ApplicationRepository applications, AdvanceRequestRepository advances, ExpenseReportRepository expenses,
            JdbcExpenseSubmissionControlRepository controls, JdbcBudgetOccupationRepository budgets, JdbcVoucherOperationRepository operations,
            ApplicationEventPublisher events, @Value("${agentflow.vouchers.lease-seconds:90}") int leaseSeconds) {
        if (leaseSeconds < 15 || leaseSeconds > 300) throw new IllegalArgumentException("Voucher lease must be between 15 and 300 seconds");
        this.applications = applications; this.advances = advances; this.expenses = expenses; this.controls = controls; this.budgets = budgets;
        this.operations = operations; this.events = events; this.lease = Duration.ofSeconds(leaseSeconds);
    }

    /** 结算准备服务在已有事务内登记；重新从真实批准聚合派生全部金额和分摊，不信任传入命令自身的声明。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public VoucherOperation register(VoucherCommand command, String targetDigest, Instant now) {
        lock(command);
        var input = new VoucherOperation.Input(command, targetDigest);
        var existing = operations.forRound(command.tenantId(), command.binding().applicationId(), command.binding().roundNo(), command.kind()).orElse(null);
        if (existing != null) {
            if (existing.input().equals(input)) return existing;
            throw new DomainException("VOUCHER_OPERATION_EXISTS", "This business round already has an original voucher operation");
        }
        requireSource(command);
        var operation = VoucherOperation.queue(input, time(now)); operations.create(operation); return operation;
    }

    /** 领取后重新读取当前依据；原批准已变的未发送命令停止发送，未知结果仍查询原操作。 */
    @Transactional
    public VoucherOperation claim(String tenant, UUID id, Instant now) {
        var initial = operations.find(tenant, id).orElse(null); if (initial == null) return null;
        lock(initial.input().command()); var current = operations.find(tenant, id).orElseThrow(VoucherOperationService::notFound); now = time(now);
        if (current.expired(now)) { operations.update(current.expire(now)); return null; }
        if (current.running() || current.nextAttemptAt() == null || now.isBefore(current.nextAttemptAt())) return null;
        if (current.status() == VoucherOperation.Status.QUEUED) {
            try { requireSource(current.input().command()); }
            catch (DomainException changed) { operations.update(current.voidBeforeSend(now)); return null; }
        }
        var claimed = current.claim(now, lease); operations.update(claimed); return claimed.running() ? claimed : null;
    }

    /** 有效领取结果和事件同事务落地，事件消费者失败会回滚本地确认，再通过原操作查询恢复。 */
    @Transactional
    public void finish(VoucherOperation claimed, FinanceResult<VoucherObservation> result, Instant now) {
        var current = currentClaim(claimed); if (current == null) return;
        complete(current, current.complete(result, time(now)));
    }

    /** 本地错误只记录稳定分类，不能认定 ERP 未过账。 */
    @Transactional
    public void fail(VoucherOperation claimed, VoucherOperation.Failure failure, Instant now) {
        var current = currentClaim(claimed); if (current == null) return;
        complete(current, current.unavailable(failure, time(now)));
    }

    private VoucherOperation currentClaim(VoucherOperation claimed) {
        var command = claimed.input().command(); lock(command);
        var current = operations.find(command.tenantId(), command.id()).orElseThrow(VoucherOperationService::notFound);
        return current.version() == claimed.version() && current.running() && current.status() == claimed.status() && current.input().equals(claimed.input()) ? current : null;
    }
    private void complete(VoucherOperation previous, VoucherOperation value) {
        operations.update(value); events.publishEvent(new VoucherOperationChanged(previous, value));
    }
    private void lock(VoucherCommand command) {
        if (JdbcVoucherOperationRepository.businessType(command) == BusinessReference.Type.ADVANCE_REQUEST) advances.lock(command.tenantId(), command.binding().businessId());
        else expenses.lock(command.tenantId(), command.binding().businessId());
    }
    private void requireSource(VoucherCommand command) {
        var application = applications.findById(command.tenantId(), command.binding().applicationId()).orElseThrow(VoucherOperationService::notFound);
        VoucherSource.Plan source;
        if (command.kind() == VoucherCommand.Kind.EMPLOYEE_ADVANCE) {
            source = VoucherSource.advance(application, advances.find(command.tenantId(), command.binding().businessId()).orElseThrow(VoucherOperationService::notFound));
        } else if (command.kind() == VoucherCommand.Kind.EXPENSE_ACCRUAL) {
            var report = expenses.find(command.tenantId(), command.binding().businessId()).orElseThrow(VoucherOperationService::notFound);
            var control = controls.find(command.tenantId(), report.id(), command.binding().roundNo()).orElseThrow(VoucherOperationService::notFound);
            source = VoucherSource.expense(application, report, control);
            var budget = budgets.find(command.tenantId(), report.id()).orElseThrow(VoucherOperationService::notFound);
            if (!budget.frozenFor(BudgetPrecheckPort.Request.fromCurrent(report, control.input().accountingDate()))) throw new DomainException("VOUCHER_BUDGET_NOT_FROZEN", "Current expense amount must retain its confirmed budget reservation");
        } else {
            // 付款凭证须由后续持久支付结果创建，不能仅凭调用方提供的一份回单声明登记。
            throw new DomainException("VOUCHER_PAYMENT_SOURCE_REQUIRED", "A persisted successful payment is required before registering its voucher");
        }
        if (!source.matches(command)) throw new DomainException("VOUCHER_SOURCE_MISMATCH", "Voucher command no longer matches the approved financial source");
    }
    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Voucher financial source or operation not found"); }
}
