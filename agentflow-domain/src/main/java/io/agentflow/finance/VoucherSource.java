package io.agentflow.finance;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.DomainException;
import io.agentflow.expense.AdvanceRequest;
import io.agentflow.expense.AdvanceRequestFormContract;
import io.agentflow.expense.CostAllocation;
import io.agentflow.expense.ExpenseFormContract;
import io.agentflow.expense.ExpenseReport;
import io.agentflow.expense.ExpenseSubmissionControl;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 跨审批与财务聚合派生凭证依据，不允许调用方另外填写金额、员工或成本分摊。
 * @author owlzhangfq@gmail.com
 */
public final class VoucherSource {
    private static final long MAX_PREPARATION_SECONDS = 300;
    private VoucherSource() { }

    /** 借款挂账只使用真实最终批准的本轮约定，记账日固定为法人当地批准日。 */
    public static Plan advance(Application application, AdvanceRequest request) {
        requireApproval(application, request.tenantId(), request.applicationId(), request.id(), request.employeeId(), BusinessReference.Type.ADVANCE_REQUEST);
        var approval = request.approval(); var round = request.currentRound();
        if (approval == null || approval.applicationVersion() != application.version() || approval.roundNo() != application.roundNo()
                || round.roundNo() != application.roundNo() || !AdvanceRequestFormContract.submittedPayload(round).equals(application.payload())) throw mismatch();
        var amount = round.content().amount(); var lines = new ArrayList<VoucherCommand.Line>();
        add(lines, key(AccountMappingPort.Role.EMPLOYEE_RECEIVABLE), VoucherCommand.Side.DEBIT, amount, 0, null, null, request.id());
        add(lines, key(AccountMappingPort.Role.EMPLOYEE_PAYABLE), VoucherCommand.Side.CREDIT, amount, 0, null, null, null);
        return new Plan(request.tenantId(), VoucherCommand.Kind.EMPLOYEE_ADVANCE, binding(application, request.version()), round.content().legalEntityId(), request.employeeId(),
                LocalDate.ofInstant(approval.approvedAt(), ZoneId.of(round.legalEntity().timeZone())), new VoucherCommand.Totals(amount, Money.zero(amount.currency()), Money.zero(amount.currency())), lines, null);
    }

    /** 费用按最终核定行生成，税额按本轮核定分摊权重拆分整分，不重新换算原币。 */
    public static Plan expense(Application application, ExpenseReport report, ExpenseSubmissionControl control) {
        requireApproval(application, report.tenantId(), report.applicationId(), report.id(), report.employeeId(), BusinessReference.Type.EXPENSE);
        var round = report.requireFrozenRound(); var source = control.input();
        if (round.roundNo() != application.roundNo() || !ExpenseFormContract.submittedPayload(round).equals(application.payload())
                || !source.tenantId().equals(report.tenantId()) || !source.reportId().equals(report.id()) || !source.applicationId().equals(application.id())
                || !source.employeeId().equals(report.employeeId()) || source.roundNo() != application.roundNo()
                || source.submittedFinancialVersion() != round.submittedFinancialVersion() + 1 || !control.paperReady()) throw mismatch();
        if (round.approvedGross().value().signum() == 0) throw new DomainException("VOUCHER_ZERO_AMOUNT", "A zero-value settlement does not create an accounting voucher");
        var lines = new ArrayList<VoucherCommand.Line>();
        for (var approved : round.approvedLines()) {
            if (approved.gross().value().signum() == 0) continue;
            var original = round.originalLines().stream().filter(line -> line.original().lineNo() == approved.lineNo()).findFirst().orElseThrow(VoucherSource::mismatch).original();
            var taxes = CostAllocation.apportion(approved.allocations(), approved.tax());
            for (int index = 0; index < approved.allocations().size(); index++) {
                var allocation = approved.allocations().get(index); var tax = taxes.get(index).amount();
                add(lines, new AccountMappingPort.Key(AccountMappingPort.Role.EXPENSE, original.categoryCode()), VoucherCommand.Side.DEBIT,
                        allocation.amount().minus(tax), approved.lineNo(), allocation.costCenter(), allocation.projectCode(), null);
                add(lines, key(AccountMappingPort.Role.DEDUCTIBLE_TAX), VoucherCommand.Side.DEBIT,
                        tax, approved.lineNo(), allocation.costCenter(), allocation.projectCode(), null);
            }
        }
        for (var offset : round.advanceOffsets()) add(lines, key(AccountMappingPort.Role.EMPLOYEE_RECEIVABLE), VoucherCommand.Side.CREDIT, offset.amount(), 0, null, null, offset.advanceId());
        add(lines, key(AccountMappingPort.Role.EMPLOYEE_PAYABLE), VoucherCommand.Side.CREDIT, round.payable(), 0, null, null, null);
        return new Plan(report.tenantId(), VoucherCommand.Kind.EXPENSE_ACCRUAL, binding(application, report.version()), report.content().legalEntityId(), report.employeeId(),
                source.accountingDate(), new VoucherCommand.Totals(round.approvedGross(), round.approvedTax(), round.offsetTotal()), lines, null);
    }

    /** 已发生的付款按原命令记账；审批随后撤销不抹去银行事实，持久来源由应用服务核验。 */
    public static Plan payment(Application application, PaymentCommand command, PaymentObservation receipt, ZoneId legalTimeZone) {
        var source = command.binding();
        var type = command.purpose() == PaymentCommand.Purpose.EMPLOYEE_ADVANCE ? BusinessReference.Type.ADVANCE_REQUEST : BusinessReference.Type.EXPENSE;
        if (!application.tenantId().equals(command.tenantId()) || !application.id().equals(source.applicationId())
                || !application.createdBy().equals(command.payee().employeeId()) || application.businessReference() == null
                || application.businessReference().type() != type || !application.businessReference().id().equals(source.businessId())
                || application.version() < source.applicationVersion() || application.roundNo() < source.roundNo() || legalTimeZone == null) throw mismatch();
        var proof = new VoucherCommand.PaymentProof(command, receipt); var lines = new ArrayList<VoucherCommand.Line>();
        add(lines, key(AccountMappingPort.Role.EMPLOYEE_PAYABLE), VoucherCommand.Side.DEBIT, command.amount(), 0, null, null, null);
        add(lines, new AccountMappingPort.Key(AccountMappingPort.Role.BANK, command.debitAccountReference()), VoucherCommand.Side.CREDIT, command.amount(), 0, null, null, null);
        var original = new VoucherCommand.Binding(source.businessId(), source.applicationId(), source.roundNo(), source.applicationVersion(), source.businessVersion());
        return new Plan(command.tenantId(), VoucherCommand.Kind.PAYMENT, original, command.payee().legalEntityId(), command.payee().employeeId(),
                LocalDate.ofInstant(receipt.completedAt(), legalTimeZone), new VoucherCommand.Totals(command.amount(), Money.zero(command.amount().currency()), Money.zero(command.amount().currency())), lines, proof);
    }

    private static void add(List<VoucherCommand.Line> lines, AccountMappingPort.Key account, VoucherCommand.Side side, Money amount, int sourceLine,
                            String costCenter, String project, UUID advance) {
        if (amount.value().signum() > 0) lines.add(new VoucherCommand.Line(lines.size() + 1, account, side, amount, sourceLine, costCenter, project, advance));
    }
    private static AccountMappingPort.Key key(AccountMappingPort.Role role) { return new AccountMappingPort.Key(role, ""); }
    private static VoucherCommand.Binding binding(Application application, long version) {
        return new VoucherCommand.Binding(application.businessReference().id(), application.id(), application.roundNo(), application.version(), version);
    }
    private static void requireApproval(Application application, String tenant, UUID applicationId, UUID businessId, String employee, BusinessReference.Type type) {
        if (application.status() != ApplicationStatus.APPROVED || !application.tenantId().equals(tenant) || !application.id().equals(applicationId)
                || !application.createdBy().equals(employee) || application.businessReference() == null || application.businessReference().type() != type
                || !application.businessReference().id().equals(businessId)) throw mismatch();
    }
    private static DomainException mismatch() { return new DomainException("VOUCHER_SOURCE_MISMATCH", "Voucher source must match the actual approved financial round and applicant"); }

    /**
     * 本地不可变业务依据，外部 ERP 只补齐期间与科目，不能改变明细。
     * @author owlzhangfq@gmail.com
     */
    public record Plan(String tenantId, VoucherCommand.Kind kind, VoucherCommand.Binding binding, UUID legalEntityId, String employeeId,
                       LocalDate accountingDate, VoucherCommand.Totals totals, List<VoucherCommand.Line> lines, VoucherCommand.PaymentProof payment) {
        /** 明细与金额由上面的真实业务工厂派生，调用方不能再原地修改。 */
        public Plan { lines = List.copyOf(lines); }
        /** 查询必要科目，零税、零应付不要求无关映射。 */
        public AccountMappingPort.Request mappingRequest() { return new AccountMappingPort.Request(legalEntityId, totals.gross().currency(), lines.stream().map(VoucherCommand.Line::account).distinct().toList()); }
        /** 期间查询不能自行使用服务器当前日期替换原日期。 */
        public AccountingPeriodPort.Request periodRequest() { return new AccountingPeriodPort.Request(legalEntityId, totals.gross().currency(), accountingDate); }
        /** 短发送窗口在登记时固定，查询恢复不延长原命令有效期。 */
        public VoucherCommand prepare(UUID id, AccountingPeriodPort.OpenPeriod period, AccountMappingPort.Mapping mapping, Instant now) {
            Instant expires = now.plusSeconds(MAX_PREPARATION_SECONDS);
            if (period.validUntil().isBefore(expires)) expires = period.validUntil();
            if (mapping.validUntil().isBefore(expires)) expires = mapping.validUntil();
            return new VoucherCommand(id, tenantId, kind, binding, legalEntityId, employeeId, accountingDate, totals, period, mapping, lines, payment, now, expires);
        }
        /** 领取和登记时与原命令再次比较全部业务依据，不从外部回执替代业务内容。 */
        public boolean matches(VoucherCommand command) {
            return tenantId.equals(command.tenantId()) && kind == command.kind() && binding.equals(command.binding()) && legalEntityId.equals(command.legalEntityId())
                    && employeeId.equals(command.employeeId()) && accountingDate.equals(command.accountingDate()) && totals.equals(command.totals())
                    && lines.equals(command.lines()) && java.util.Objects.equals(payment, command.payment());
        }
    }
}
