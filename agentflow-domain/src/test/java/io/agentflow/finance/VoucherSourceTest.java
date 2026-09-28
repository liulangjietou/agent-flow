package io.agentflow.finance;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.DomainException;
import io.agentflow.notification.NotificationTexts;
import io.agentflow.expense.*;
import io.agentflow.organization.InitiatorContext;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 从真实领域冻结和核减动作派生凭证，验证整分、税额、冲销与最终批准绑定。
 * @author owlzhangfq@gmail.com
 */
class VoucherSourceTest {
    private static final Instant NOW = Instant.parse("2026-09-28T01:00:00Z");
    private static final UUID ENTITY = UUID.randomUUID();

    @Test
    void approvedAdvanceUsesLegalApprovalDateAndExactFrozenTerms() {
        var request = advance(); var application = application(request);
        var plan = VoucherSource.advance(application, request);
        assertThat(plan.accountingDate()).isEqualTo(LocalDate.parse("2026-09-27"));
        assertThat(plan.binding().businessVersion()).isEqualTo(3); assertThat(plan.binding().applicationVersion()).isEqualTo(5);
        assertThat(plan.lines()).extracting(VoucherCommand.Line::amount).containsExactly(money("100", "CNY"), money("100", "CNY"));
        assertThat(plan.lines().get(0).advanceId()).isEqualTo(request.id());
        assertThat(plan.matches(prepare(plan, NOW.plusSeconds(1)))).isTrue();
        assertThatThrownBy(() -> plan.lines().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> VoucherSource.advance(changed(application, ApplicationStatus.IN_APPROVAL, application.version(), application.payload()), request)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> VoucherSource.advance(changed(application, ApplicationStatus.APPROVED, application.version() + 1, application.payload()), request)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> VoucherSource.advance(changed(application, ApplicationStatus.APPROVED, application.version(), Map.of("amount", "200")), request)).isInstanceOf(DomainException.class);
    }

    @Test
    void foreignExpensesAllocateTaxFromAlreadyFrozenCentsWithoutReconversion() {
        var report = expense(); var control = control(report, false);
        var plan = VoucherSource.expense(application(report), report, control); var command = prepare(plan, NOW.plusSeconds(1));
        assertThat(command.totals().gross()).isEqualTo(money("0.06", "CNY")); assertThat(command.totals().tax()).isEqualTo(money("0.02", "CNY"));
        assertThat(command.totals().payable()).isEqualTo(Money.zero("CNY"));
        assertThat(command.lines().stream().filter(line -> line.account().role() == AccountMappingPort.Role.EXPENSE).map(line -> line.amount().value().toPlainString())).containsExactly("0.01", "0.01", "0.02");
        assertThat(command.lines().stream().filter(line -> line.account().role() == AccountMappingPort.Role.DEDUCTIBLE_TAX).map(VoucherCommand.Line::costCenter)).containsExactly("A", "B");
        assertThat(command.mapping().request().keys()).noneMatch(key -> key.role() == AccountMappingPort.Role.EMPLOYEE_PAYABLE || key.role() == AccountMappingPort.Role.BANK);
        assertThat(command.lines().get(command.lines().size() - 1).advanceId()).isEqualTo(report.currentRound().advanceOffsets().get(0).advanceId());
        assertThat(command.accountingDate()).isEqualTo(control.input().accountingDate());
    }

    @Test
    void reductionsUseCurrentApprovedAmountsAndRejectOldRoutingSnapshot() {
        var report = expense(); var old = application(report); var original = report.currentRound().originalLines();
        report.reduce(2, List.of(new ExpenseReport.Reduction(1, money("0.05", "CNY"), money("0.01", "CNY"))), "finance", "POLICY", "合成核减", NOW.plusSeconds(1));
        assertThatThrownBy(() -> VoucherSource.expense(old, report, control(report, false))).isInstanceOf(DomainException.class);
        var plan = VoucherSource.expense(application(report), report, control(report, false)); var command = prepare(plan, NOW.plusSeconds(2));
        assertThat(command.binding().businessVersion()).isEqualTo(3); assertThat(command.totals().gross()).isEqualTo(money("0.05", "CNY"));
        assertThat(command.totals().offset()).isEqualTo(money("0.05", "CNY")); assertThat(command.totals().tax()).isEqualTo(money("0.01", "CNY"));
        assertThat(report.currentRound().originalLines()).isEqualTo(original);
        report.reduce(3, List.of(new ExpenseReport.Reduction(1, Money.zero("CNY"), Money.zero("CNY"))), "finance", "POLICY", "合成全额核减", NOW.plusSeconds(2));
        assertThatThrownBy(() -> VoucherSource.expense(application(report), report, control(report, false))).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("VOUCHER_ZERO_AMOUNT"));
    }

    @Test
    void paperRequiredAndWrongRoundControlCannotGenerateVouchers() {
        var report = expense(); var app = application(report); var pending = control(report, true);
        assertThatThrownBy(() -> VoucherSource.expense(app, report, pending)).isInstanceOf(DomainException.class);
        var received = pending.receive("receipt-task", "receipt", "finance", "合成签收", NOW.plusSeconds(1));
        assertThatCode(() -> prepare(VoucherSource.expense(app, report, received), NOW.plusSeconds(2))).doesNotThrowAnyException();
        var input = received.input();
        var wrong = new ExpenseSubmissionControl.Input(input.tenantId(), input.reportId(), input.applicationId(), input.employeeId(), 2, 2, input.precheckId(), input.accountingDate(), false, input.stages());
        assertThatThrownBy(() -> VoucherSource.expense(app, report, ExpenseSubmissionControl.submitted(wrong, NOW))).isInstanceOf(DomainException.class);
    }

    @Test
    void paymentVoucherUsesSuccessfulReceiptDateAndOriginalAuthorizedBank() {
        var request = advance(); var app = application(request); var account = request.currentRound().account();
        var payment = new PaymentCommand(UUID.randomUUID(), "demo", PaymentCommand.Purpose.EMPLOYEE_ADVANCE,
                new PaymentCommand.Binding(request.id(), app.id(), 1, 5, request.version()), request.content().amount(), "bank-source-1", account, "accrual-voucher",
                new PaymentCommand.Authorization("finance", "cashier", NOW, NOW.plusSeconds(300)));
        var receipt = new PaymentObservation(payment.id(), payment.digest(), PaymentObservation.Status.SUCCEEDED, 2L, NOW.plusSeconds(600), "bank-1", payment.amount(), account.accountDigest(), NOW.plusSeconds(600), "receipt-1", null);
        var plan = VoucherSource.payment(app, payment, receipt, ZoneId.of("America/New_York"));
        assertThat(plan.accountingDate()).isEqualTo(LocalDate.parse("2026-09-27"));
        var command = prepare(plan, NOW.plusSeconds(601));
        assertThat(command.lines().get(1).account()).isEqualTo(new AccountMappingPort.Key(AccountMappingPort.Role.BANK, "bank-source-1"));
        assertThat(command.payment().receipt()).isEqualTo(receipt);
        assertThatThrownBy(() -> VoucherSource.payment(changed(app, ApplicationStatus.REVOKED, 6, app.payload()), payment, receipt, ZoneId.of("UTC"))).isInstanceOf(DomainException.class);
    }

    private static VoucherCommand prepare(VoucherSource.Plan plan, Instant at) {
        var date = plan.accountingDate();
        var period = new AccountingPeriodPort.OpenPeriod(plan.periodRequest(), "period-1", "period-v1", date.minusDays(30), date.plusDays(30), at, at.plusSeconds(600));
        var mapping = new AccountMappingPort.Mapping(plan.mappingRequest(), "map-v1", at, at.plusSeconds(600), plan.mappingRequest().keys().stream().map(key -> new AccountMappingPort.Entry(key, "synthetic-" + key.role().name())).toList());
        return plan.prepare(UUID.randomUUID(), period, mapping, at);
    }
    private static AdvanceRequest advance() {
        var request = AdvanceRequest.draft(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice", new AdvanceRequestContent(ENTITY, "合成借款", "合成用途", money("100", "CNY"), LocalDate.parse("2026-10-01")));
        var catalog = new FinanceCatalog("alice", "v1", NOW.plusSeconds(300), List.of(new FinanceCatalog.LegalEntity(ENTITY, "法人", "CNY", false, "v1", "America/New_York")), List.of(), List.of(), List.of(), List.of());
        var account = new EmployeeAccountSnapshot(ENTITY, "alice", "synthetic-employee", "****1234", "a".repeat(64), "v1");
        request.freeze(1, 1, catalog, new EmployeeAccountPort.Account(account, NOW.plusSeconds(300)), new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, ENTITY, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位"), NOW);
        request.approve(2, 1, 5, "manager", NOW); return request;
    }
    private static ExpenseReport expense() {
        var allocations = List.of(new CostAllocation("A", null, money("0.01", "USD")), new CostAllocation("B", "PROJECT", money("0.01", "USD")), new CostAllocation("C", null, money("0.01", "USD")));
        var line = new ExpenseLine(1, "TRAVEL", LocalDate.parse("2026-09-27"), null, "CITY", BigDecimal.ONE, ExpenseLine.Unit.ITEM, money("0.03", "USD"), money("0.01", "USD"), List.of(), null, allocations, "合成费用", null);
        var report = ExpenseReport.draft(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice", new ExpenseContent(ENTITY, ExpenseContent.Type.DAILY, "费用", List.of(line), List.of(new AdvanceOffset(UUID.randomUUID(), money("0.06", "CNY")))));
        var assessment = new ExpenseAssessment(new ExpenseExchangeRate("USD", "CNY", new BigDecimal("2"), "synthetic-rate", LocalDate.parse("2026-09-27")), new ExpensePolicySnapshot(UUID.randomUUID(), 1, money("0.06", "CNY"), money("0.06", "CNY"), ExpensePolicySnapshot.Decision.WITHIN_LIMIT, "synthetic-tax", "synthetic-evidence"), money("0.02", "CNY"));
        report.freeze(1, 1, "CNY", new EmployeeAccountSnapshot(ENTITY, "alice", "synthetic-employee", "****1234", "a".repeat(64), "v1"), Map.of(1, assessment), "alice", NOW); return report;
    }
    private static ExpenseSubmissionControl control(ExpenseReport report, boolean paper) {
        return ExpenseSubmissionControl.submitted(new ExpenseSubmissionControl.Input(report.tenantId(), report.id(), report.applicationId(), report.employeeId(), 1, 2, UUID.randomUUID(), LocalDate.parse("2026-09-27"), paper, Map.of("receipt", ExpenseProcessPolicy.Stage.RECEIPT, "finance", ExpenseProcessPolicy.Stage.FINANCE_REVIEW)), NOW);
    }
    private static Application application(AdvanceRequest request) { return app(request.applicationId(), request.id(), BusinessReference.Type.ADVANCE_REQUEST, AdvanceRequestFormContract.submittedPayload(request.currentRound())); }
    private static Application application(ExpenseReport report) { return app(report.applicationId(), report.id(), BusinessReference.Type.EXPENSE, ExpenseFormContract.submittedPayload(report.currentRound())); }
    private static Application app(UUID id, UUID business, BusinessReference.Type type, Map<String, Object> payload) {
        return Application.restore(id, "demo", "SYNTHETIC", "synthetic-voucher", 1, "alice", "凭证合成源", payload, ApplicationStatus.APPROVED, 1, 5, null, null, NotificationTexts.EMPTY, new BusinessReference(type, business));
    }
    private static Application changed(Application app, ApplicationStatus status, long version, Map<String, Object> payload) {
        return Application.restore(app.id(), app.tenantId(), app.businessNo(), app.processKey(), app.definitionVersion(), app.createdBy(), app.title(), payload, status, app.roundNo(), version, null, null, NotificationTexts.EMPTY, app.businessReference());
    }
    private static Money money(String value, String currency) { return new Money(new BigDecimal(value), currency); }
}
