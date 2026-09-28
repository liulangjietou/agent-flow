package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.EmployeeAccountSnapshot;
import io.agentflow.finance.Money;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 提交与重提共享计划验证互斥、精确额度、释放顺序、关闭与核销边界，输入永不部分变更。
 * @author owlzhangfq@gmail.com
 */
class ExpenseSubmissionResourcesTest {
    private static final UUID ENTITY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 28);
    private final ExpenseSubmissionResources planner = new ExpenseSubmissionResources();

    @Test
    void firstSubmissionProducesReservationsWithoutChangingAnyInputResource() {
        var invoice = invoice(UUID.randomUUID(), "12345678901234567890"); var request = request("100"); var advance = advance("80");
        var report = frozen(List.of(line(1, "100", List.of(invoice.id()), request.id())), List.of(new AdvanceOffset(advance.id(), money("80"))));
        var inputs = resources(List.of(invoice), List.of(request), List.of(advance));
        var plan = planner.plan(report, inputs, NOW);
        assertThat(plan.invoices()).hasSize(1); assertThat(plan.requests()).hasSize(1); assertThat(plan.advances()).hasSize(1);
        var changed = applied(inputs, plan);
        assertThat(changed.invoices().get(invoice.id()).occupation()).isEqualTo(Invoice.Occupation.OCCUPIED);
        assertThat(changed.invoices().get(invoice.id()).use()).isEqualTo(new ExpenseUse(report.id(), 1, 1));
        assertThat(changed.requests().get(request.id()).balances().get(1).available()).isEqualTo(money("0"));
        assertThat(changed.advances().get(advance.id()).balance().available()).isEqualTo(money("0"));
        assertThat(inputs.invoices().get(invoice.id()).occupation()).isEqualTo(Invoice.Occupation.AVAILABLE);
        assertThat(inputs.requests().get(request.id()).balances().get(1).available()).isEqualTo(money("100"));
        assertThat(inputs.advances().get(advance.id()).balance().available()).isEqualTo(money("80"));
    }

    @Test
    void resubmissionReleasesRemovedResourcesAndMovesOnlyRetainedPreviousRoundUses() {
        var first = invoice(UUID.randomUUID(), "12345678901234567890"); var removed = invoice(UUID.randomUUID(), "22345678901234567890");
        var request = request("200"); var advance = advance("100"); var removedAdvance = advance("100");
        var initial = frozen(List.of(line(1, "100", List.of(first.id()), request.id()), line(2, "100", List.of(removed.id()), request.id())),
                List.of(new AdvanceOffset(advance.id(), money("80")), new AdvanceOffset(removedAdvance.id(), money("80"))));
        var input = resources(List.of(first, removed), List.of(request), List.of(advance, removedAdvance));
        var retained = applied(input, planner.plan(initial, input, NOW));
        var revised = corrected(initial, List.of(line(1, "90", List.of(first.id()), request.id())), List.of(new AdvanceOffset(advance.id(), money("90"))));
        var plan = planner.plan(revised, retained, NOW.plusSeconds(20)); var changed = applied(retained, plan);
        assertThat(plan.invoices().get(0).operation()).isEqualTo(ExpenseSubmissionResources.Operation.RELEASE);
        assertThat(changed.invoices().get(first.id()).use()).isEqualTo(new ExpenseUse(initial.id(), 2, 1));
        assertThat(changed.invoices().get(removed.id()).occupation()).isEqualTo(Invoice.Occupation.AVAILABLE);
        assertThat(plan.requests().stream().map(value -> value.after().version())).containsExactly(4L, 5L);
        assertThat(changed.requests().get(request.id()).balances().get(1).available()).isEqualTo(money("110"));
        assertThat(changed.advances().get(advance.id()).balance().reservedFor(new ExpenseUse(initial.id(), 2, 0))).isEqualTo(money("90"));
        assertThat(changed.advances().get(removedAdvance.id()).balance().available()).isEqualTo(money("100"));
        assertThat(retained.requests().get(request.id()).balances().get(1).available()).isEqualTo(money("0"));
    }

    @Test
    void priorRequestRedistributionReducesBeforeIncreasingAndPreservesEveryVersion() {
        var request = request("100");
        var initial = frozen(List.of(line(1, "80", List.of(), request.id()), line(2, "20", List.of(), request.id())), List.of());
        var input = resources(List.of(), List.of(request), List.of()); var retained = applied(input, planner.plan(initial, input, NOW));
        var revised = corrected(initial, List.of(line(1, "90", List.of(), request.id()), line(2, "10", List.of(), request.id())), List.of());
        var plan = planner.plan(revised, retained, NOW.plusSeconds(20));
        var first = plan.requests().get(0).after().balances().get(1);
        assertThat(first.reservedFor(new ExpenseUse(initial.id(), 2, 2))).isEqualTo(money("10"));
        assertThat(first.reservedFor(new ExpenseUse(initial.id(), 1, 1))).isEqualTo(money("80"));
        assertThat(first.available()).isEqualTo(money("10"));
        var last = plan.requests().get(1).after();
        assertThat(last.version()).isEqualTo(5); assertThat(last.balances().get(1).available()).isEqualTo(money("0"));
        assertThat(last.balances().get(1).reservedFor(new ExpenseUse(initial.id(), 2, 1))).isEqualTo(money("90"));
    }

    @Test
    void aClosedPriorRequestCanCarryALowerReservationButCannotIncreaseOrReceiveANewLine() {
        var request = request("100"); var initial = frozen(List.of(line(1, "80", List.of(), request.id())), List.of());
        var input = resources(List.of(), List.of(request), List.of()); var retained = applied(input, planner.plan(initial, input, NOW));
        var closed = ExpenseRequest.restore(retained.requests().get(request.id())); closed.close(closed.version());
        var closedResources = new ExpenseSubmissionResources.Resources(Map.of(), Map.of(request.id(), closed.state()), Map.of());
        var decreased = corrected(initial, List.of(line(1, "60", List.of(), request.id())), List.of());
        var change = planner.plan(decreased, closedResources, NOW.plusSeconds(20)).requests().get(0);
        assertThat(change.operation()).isEqualTo(ExpenseSubmissionResources.Operation.MOVE);
        assertThat(change.after().balances().get(1).reservedFor(new ExpenseUse(initial.id(), 2, 1))).isEqualTo(money("60"));
        for (var line : List.of(line(1, "90", List.of(), request.id()), line(2, "60", List.of(), request.id()))) {
            var next = corrected(initial, List.of(line), List.of());
            fails("EXPENSE_REQUEST_CLOSED", () -> planner.plan(next, closedResources, NOW.plusSeconds(20)));
        }
        assertThat(closedResources.requests().get(request.id()).balances().get(1).reservedFor(new ExpenseUse(initial.id(), 1, 1))).isEqualTo(money("80"));
    }

    @Test
    void replacingAnOriginalOfTheSameCanonicalInvoiceReleasesBeforeReservingEvenWhenIdsSortOtherwise() {
        var old = invoice(UUID.fromString("7fffffff-ffff-4fff-8fff-ffffffffffff"), "12345678901234567890");
        var fresh = invoice(UUID.fromString("00000000-0000-4000-8000-000000000001"), "12345678901234567890");
        var initial = frozen(List.of(line(1, "100", List.of(old.id()), null)), List.of());
        var input = resources(List.of(old, fresh), List.of(), List.of()); var retained = applied(input, planner.plan(initial, input, NOW));
        var next = corrected(initial, List.of(line(1, "100", List.of(fresh.id()), null)), List.of());
        var plan = planner.plan(next, retained, NOW.plusSeconds(20));
        assertThat(plan.invoices().stream().map(value -> value.after().id())).containsExactly(old.id(), fresh.id());
        assertThat(plan.invoices().stream().map(ExpenseSubmissionResources.InvoiceChange::operation)).containsExactly(ExpenseSubmissionResources.Operation.RELEASE, ExpenseSubmissionResources.Operation.RESERVE);
        var duplicate = corrected(initial, List.of(line(1, "100", List.of(old.id(), fresh.id()), null)), List.of());
        fails("INVOICE_OCCUPIED", () -> planner.plan(duplicate, retained, NOW.plusSeconds(20)));
    }

    @Test
    void failingTheLastResourceDoesNotLeaveEarlierReservationsInTheInputSnapshots() {
        var invoice = invoice(UUID.randomUUID(), "12345678901234567890"); var request = request("100"); var advance = advance("20");
        var report = frozen(List.of(line(1, "100", List.of(invoice.id()), request.id())), List.of(new AdvanceOffset(advance.id(), money("50"))));
        var input = resources(List.of(invoice), List.of(request), List.of(advance));
        fails("INSUFFICIENT_FINANCIAL_BALANCE", () -> planner.plan(report, input, NOW));
        assertThat(input.invoices().get(invoice.id())).isEqualTo(invoice.state());
        assertThat(input.requests().get(request.id())).isEqualTo(request.state());
        assertThat(input.advances().get(advance.id())).isEqualTo(advance.state());
    }

    @Test
    void foreignResourcesMissingFactsAndOtherReportOccupationsCannotBeBorrowed() {
        var invoice = invoice(UUID.randomUUID(), "12345678901234567890");
        var report = frozen(List.of(line(1, "100", List.of(invoice.id()), null)), List.of());
        fails("EXPENSE_RESOURCE_UNAVAILABLE", () -> planner.plan(report, resources(List.of(), List.of(), List.of()), NOW));
        invoice.occupy(2, new ExpenseUse(UUID.randomUUID(), 1, 1), "alice", ENTITY, NOW);
        fails("INVOICE_OCCUPIED", () -> planner.plan(report, resources(List.of(invoice), List.of(), List.of()), NOW));
        var foreign = new EmployeeAdvance(UUID.randomUUID(), "foreign", ENTITY, "alice", money("100"), "synthetic", DATE, DATE.plusDays(30));
        var withForeign = frozen(List.of(line(1, "100", List.of(), null)), List.of(new AdvanceOffset(foreign.id(), money("10"))));
        fails("EXPENSE_RESOURCE_UNAVAILABLE", () -> planner.plan(withForeign, resources(List.of(), List.of(), List.of(foreign)), NOW));
        var another = new EmployeeAdvance(UUID.randomUUID(), "demo", ENTITY, "bob", money("100"), "synthetic", DATE, DATE.plusDays(30));
        var withAnother = frozen(List.of(line(1, "100", List.of(), null)), List.of(new AdvanceOffset(another.id(), money("10"))));
        fails("EXPENSE_RESOURCE_OWNER_MISMATCH", () -> planner.plan(withAnother, resources(List.of(), List.of(), List.of(another)), NOW));
    }

    @Test
    void anAlreadyConsumedPreviousRoundCannotBeResubmittedAgainstRemainingCredit() {
        var request = request("100"); var advance = advance("100");
        var initial = frozen(List.of(line(1, "80", List.of(), request.id())), List.of(new AdvanceOffset(advance.id(), money("80"))));
        var input = resources(List.of(), List.of(request), List.of(advance)); var retained = applied(input, planner.plan(initial, input, NOW));
        var consumedRequest = ExpenseRequest.restore(retained.requests().get(request.id()));
        consumedRequest.consume(consumedRequest.version(), 1, new ExpenseUse(initial.id(), 1, 1));
        var consumedAdvance = EmployeeAdvance.restore(retained.advances().get(advance.id()));
        consumedAdvance.settle(consumedAdvance.version(), new ExpenseUse(initial.id(), 1, 0));
        var next = corrected(initial, List.of(line(1, "10", List.of(), request.id())), List.of(new AdvanceOffset(advance.id(), money("10"))));
        for (var consumed : List.of(new ExpenseSubmissionResources.Resources(Map.of(), Map.of(request.id(), consumedRequest.state()), retained.advances()),
                new ExpenseSubmissionResources.Resources(Map.of(), retained.requests(), Map.of(advance.id(), consumedAdvance.state())))) {
            fails("RESERVATION_ALREADY_CONSUMED", () -> planner.plan(next, consumed, NOW.plusSeconds(20)));
        }
        var invoice = invoice(UUID.randomUUID(), "12345678901234567890");
        var invoiceReport = frozen(List.of(line(1, "100", List.of(invoice.id()), null)), List.of());
        var invoiceInput = resources(List.of(invoice), List.of(), List.of());
        var occupied = applied(invoiceInput, planner.plan(invoiceReport, invoiceInput, NOW));
        var consumedInvoice = Invoice.restore(occupied.invoices().get(invoice.id()));
        consumedInvoice.consume(consumedInvoice.version(), new ExpenseUse(invoiceReport.id(), 1, 1), NOW);
        var removedInvoice = corrected(invoiceReport, List.of(line(1, "10", List.of(), null)), List.of());
        fails("RESERVATION_ALREADY_CONSUMED", () -> planner.plan(removedInvoice, resources(List.of(consumedInvoice), List.of(), List.of()), NOW.plusSeconds(20)));
    }

    private ExpenseReport frozen(List<ExpenseLine> lines, List<AdvanceOffset> offsets) {
        var report = ExpenseReport.draft(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice", content(lines, offsets)); freeze(report, NOW); return report;
    }
    private ExpenseReport corrected(ExpenseReport initial, List<ExpenseLine> lines, List<AdvanceOffset> offsets) {
        var next = ExpenseReport.restore(initial.state()); next.revise(next.version(), content(lines, offsets)); freeze(next, NOW.plusSeconds(20)); return next;
    }
    private void freeze(ExpenseReport report, Instant at) {
        var assessments = new HashMap<Integer, ExpenseAssessment>();
        for (var line : report.content().lines()) assessments.put(line.lineNo(), new ExpenseAssessment(
                new ExpenseExchangeRate("CNY", "CNY", BigDecimal.ONE, "synthetic-rate", DATE),
                new ExpensePolicySnapshot(UUID.randomUUID(), 1, line.claimedGross(), line.claimedGross(), ExpensePolicySnapshot.Decision.WITHIN_LIMIT, "synthetic-tax", "synthetic-policy"), money("0")));
        report.freeze(report.version(), report.rounds().size() + 1, "CNY", new EmployeeAccountSnapshot(ENTITY, "alice", "synthetic-account", "****1234", "a".repeat(64), "v1"), assessments, "alice", at);
    }
    private ExpenseContent content(List<ExpenseLine> lines, List<AdvanceOffset> offsets) { return new ExpenseContent(ENTITY, ExpenseContent.Type.DAILY, "合成资源预检", lines, offsets); }
    private ExpenseLine line(int no, String gross, List<UUID> invoices, UUID request) {
        return new ExpenseLine(no, "DAILY", DATE, null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM, money(gross), money("0"), invoices,
                request == null ? null : new ExpenseLine.PriorRequestLine(request, 1), List.of(new CostAllocation("IT", null, money(gross))), "合成明细", null);
    }
    private Invoice invoice(UUID id, String number) {
        var value = Invoice.uploaded(id, "demo", "alice", UUID.randomUUID(), "a".repeat(64));
        value.verified(1, new Invoice.VerifiedFacts(new InvoiceKey(InvoiceKey.Type.DIGITAL, null, number), ENTITY, money("100"), money("0"), DATE,
                "a".repeat(64), "synthetic-verification", NOW, NOW.plusSeconds(300))); return value;
    }
    private ExpenseRequest request(String amount) { return new ExpenseRequest(UUID.randomUUID(), "demo", UUID.randomUUID(), ENTITY, "alice", List.of(new ExpenseRequest.ApprovedLine(1, money(amount), BigDecimal.ZERO, "synthetic-policy"))); }
    private EmployeeAdvance advance(String amount) { return new EmployeeAdvance(UUID.randomUUID(), "demo", ENTITY, "alice", money(amount), "synthetic-" + UUID.randomUUID(), DATE, DATE.plusDays(30)); }
    private Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private ExpenseSubmissionResources.Resources resources(List<Invoice> invoices, List<ExpenseRequest> requests, List<EmployeeAdvance> advances) {
        var invoiceStates = new HashMap<UUID, Invoice.State>(); invoices.forEach(value -> invoiceStates.put(value.id(), value.state()));
        var requestStates = new HashMap<UUID, ExpenseRequest.State>(); requests.forEach(value -> requestStates.put(value.id(), value.state()));
        var advanceStates = new HashMap<UUID, EmployeeAdvance.State>(); advances.forEach(value -> advanceStates.put(value.id(), value.state()));
        return new ExpenseSubmissionResources.Resources(invoiceStates, requestStates, advanceStates);
    }
    private ExpenseSubmissionResources.Resources applied(ExpenseSubmissionResources.Resources input, ExpenseSubmissionResources.Plan plan) {
        var invoices = new HashMap<>(input.invoices()); plan.invoices().forEach(value -> invoices.put(value.after().id(), value.after()));
        var requests = new HashMap<>(input.requests()); plan.requests().forEach(value -> requests.put(value.after().id(), value.after()));
        var advances = new HashMap<>(input.advances()); plan.advances().forEach(value -> advances.put(value.after().id(), value.after()));
        return new ExpenseSubmissionResources.Resources(invoices, requests, advances);
    }
    private void fails(String code, Runnable action) { assertThatExceptionOfType(DomainException.class).isThrownBy(action::run).satisfies(failure -> assertThat(failure.code()).isEqualTo(code)); }
}
