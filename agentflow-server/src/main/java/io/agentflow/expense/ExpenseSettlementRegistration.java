package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.*;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 资金或挂账事实只登记本地结算意图，资源过期不能使银行成功变成未知。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseSettlementRegistration {
    private final ExpenseReportRepository reports;
    private final JdbcExpenseSettlementRepository settlements;
    private final ExpenseSettlementSources sources;
    private final JdbcPaymentOperationRepository payments;
    private final JdbcVoucherOperationRepository vouchers;
    private final JdbcVoucherPreparationRepository preparations;

    /** 原报销锁保证重复回执、后台补登与消费只形成一份账本。 */
    public ExpenseSettlementRegistration(ExpenseReportRepository reports, JdbcExpenseSettlementRepository settlements, ExpenseSettlementSources sources,
            JdbcPaymentOperationRepository payments, JdbcVoucherOperationRepository vouchers, JdbcVoucherPreparationRepository preparations) {
        this.reports = reports; this.settlements = settlements; this.sources = sources;
        this.payments = payments; this.vouchers = vouchers; this.preparations = preparations;
    }

    /** 到账与结算意图原子保存，争议只冻结原账本，不撤销已经消耗的资源。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void paid(PaymentOperationChanged event) {
        var value = event.current(); var command = value.input().command();
        if (command.purpose() != PaymentCommand.Purpose.EXPENSE_REIMBURSEMENT) return;
        String tenant = command.tenantId(); UUID id = command.binding().businessId();
        reports.lock(tenant, id);
        if (value.settleable()) register(sources.paid(value, report(tenant, id)));
        else if (value.status() == PaymentOperation.Status.REVERSED || value.status() == PaymentOperation.Status.RECONCILING) {
            var current = settlements.find(tenant, id).orElse(null);
            if (current != null && current.input().payment() != null && current.input().payment().operationId().equals(command.id())) review(current, "EXPENSE_PAYMENT_REVIEW");
        }
    }

    /** 全额借款冲销在挂账确认后无需等待付款，凭证反向事实始终保留人工处理状态。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void posted(VoucherOperationChanged event) {
        var value = event.current(); var command = value.input().command();
        if (command.kind() != VoucherCommand.Kind.EXPENSE_ACCRUAL) return;
        String tenant = command.tenantId(); UUID id = command.binding().businessId(); reports.lock(tenant, id);
        if (value.reversalId() != null || value.status() == VoucherOperation.Status.REVERSED || value.status() == VoucherOperation.Status.RECONCILING) {
            var current = settlements.find(tenant, id).orElse(null);
            if (current != null && command.id().equals(current.input().voucherOperationId())) review(current, "EXPENSE_VOUCHER_REVIEW");
        } else if (value.usablePosted() && settlements.find(tenant, id).isEmpty()) registerOffset(value, report(tenant, id));
    }

    /** 为升级前成功事实补登；逐次只读取原持久来源，不调用银行或制造新付款。 */
    @Transactional
    public void recover(JdbcExpenseSettlementRepository.RecoveryCandidate candidate) {
        String tenant = candidate.tenantId(); UUID id = candidate.reportId(); reports.lock(tenant, id);
        if (settlements.find(tenant, id).isPresent()) return;
        var report = report(tenant, id);
        switch (candidate.kind()) {
            case PAYMENT -> {
                var value = payments.find(tenant, candidate.id()).orElseThrow(ExpenseSettlementRegistration::notFound);
                if (value.settleable()) register(sources.paid(value, report));
            }
            case VOUCHER -> registerOffset(vouchers.find(tenant, candidate.id()).orElseThrow(ExpenseSettlementRegistration::notFound), report);
            case ZERO -> {
                var value = preparations.find(tenant, candidate.id()).orElseThrow(ExpenseSettlementRegistration::notFound);
                ExpenseSettlement.Input input;
                try { input = sources.zero(value, report); }
                catch (DomainException changed) { return; }
                if (input != null) register(input);
            }
        }
    }
    private void registerOffset(VoucherOperation value, ExpenseReport report) {
        ExpenseSettlement.Input input;
        // 无实际出款的来源已变化时不启动核销，也不回滚已经确认的 ERP 过账事实。
        try { input = sources.offset(value, report); }
        catch (DomainException changed) { return; }
        if (input != null) register(input);
    }
    private void register(ExpenseSettlement.Input input) {
        var source = input.source(); var current = settlements.find(source.tenantId(), source.businessId()).orElse(null);
        if (current == null) {
            var queued = ExpenseSettlement.queue(input, now()); queued.requireReport(report(source.tenantId(), source.businessId())); settlements.create(queued);
        } else if (!current.input().equals(input)) review(current, "EXPENSE_FUNDING_CONFLICT");
    }
    private void review(ExpenseSettlement current, String code) {
        var next = current.requireReview(code, now()); if (next != current) settlements.update(next);
    }
    private ExpenseReport report(String tenant, UUID id) { return reports.find(tenant, id).orElseThrow(ExpenseSettlementRegistration::notFound); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Expense settlement source not found"); }
}
