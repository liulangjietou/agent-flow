package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.Money;
import io.agentflow.finance.ReservedAmount;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 事前控制模式和共享账本的边界，保留旧快照的严格语义。
 * @author owlzhangfq@gmail.com
 */
class ExpensePriorControlTest {
    private final JsonUtil json = new JsonUtil(new ObjectMapper().findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL));

    @Test void explicitToleranceTracksActualExposureBeyondItsReviewThreshold() {
        var line = json.read("""
                {"lineNo":1,"approvedAmount":{"value":100,"currency":"CNY"},"toleranceFraction":0.1,
                 "policyReference":"APPROVAL:1:1","control":{"categoryCode":"TRAVEL","categoryRevision":2,
                 "control":{"mode":"TOLERANCE","toleranceFraction":0.1}}}
                """, ExpenseRequest.ApprovedLine.class);
        var request = request(line);
        assertThatCode(() -> request.reserve(1, 1, use(1), money("120"))).doesNotThrowAnyException();
        assertThat(request.balance(1).reserved()).isEqualTo(money("120"));
        assertThat(json.write(request.state())).contains("\"mode\":\"TOLERANCE\"");
    }

    @Test void advanceCannotBeRestoredWithAReferenceOnlyLedgerEvenBelowItsLimit() {
        var advance = new EmployeeAdvance(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice", money("100"),
                "paid-1", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 28));
        var balance = json.read("""
                {"limit":{"value":100,"currency":"CNY"},"consumptions":[],"reservations":[],
                 "ceiling":"REFERENCE_ONLY"}
                """, ReservedAmount.class);
        var original = advance.state();
        var state = new EmployeeAdvance.State(original.id(), original.tenantId(), original.legalEntityId(), original.employeeId(),
                original.paymentReference(), original.paidOn(), original.dueOn(), balance, original.version(), false,
                List.of(), false, List.of(), List.of());
        assertThatThrownBy(() -> EmployeeAdvance.restore(state)).isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).code()).isEqualTo("INVALID_ADVANCE");
    }

    @Test void modesRequireExplicitValidFractionsAndSource() {
        for (var mode : List.of(ExpensePriorControl.Mode.STRICT, ExpensePriorControl.Mode.NONE)) {
            assertThat(new ExpensePriorControl(mode, null).referenceFraction()).isEqualTo(BigDecimal.ZERO);
            fails("INVALID_EXPENSE_PRIOR_CONTROL", () -> new ExpensePriorControl(mode, BigDecimal.ZERO));
        }
        for (var fraction : List.of("-0.000001", "1.000001", "0.0000001")) {
            fails("INVALID_EXPENSE_PRIOR_CONTROL", () -> new ExpensePriorControl(ExpensePriorControl.Mode.TOLERANCE, new BigDecimal(fraction)));
        }
        fails("INVALID_EXPENSE_PRIOR_CONTROL", () -> new ExpensePriorControl(ExpensePriorControl.Mode.TOLERANCE, null));
        fails("INVALID_EXPENSE_PRIOR_CONTROL", () -> new ExpensePriorControl(null, null));
        var control = new ExpensePriorControl(ExpensePriorControl.Mode.TOLERANCE, new BigDecimal("0.000001"));
        assertThat(new ExpensePriorControl(ExpensePriorControl.Mode.TOLERANCE, BigDecimal.ONE).referenceFraction()).isEqualTo(BigDecimal.ONE);
        fails("INVALID_EXPENSE_PRIOR_CONTROL", () -> new ExpensePriorControl.Snapshot(" TRAVEL", 1, control));
        fails("INVALID_EXPENSE_PRIOR_CONTROL", () -> new ExpensePriorControl.Snapshot("TRAVEL", 0, control));
        fails("INVALID_EXPENSE_PRIOR_CONTROL", () -> new ExpensePriorControl.Snapshot("TRAVEL", 1, null));
        fails("INVALID_PRIOR_REQUEST", () -> new ExpenseRequest.ApprovedLine(1, money("100"), BigDecimal.ZERO,
                "approval", new ExpensePriorControl.Snapshot("TRAVEL", 1, control)));
    }

    @Test void oldPositiveToleranceRemainsHardAndLegacyJsonDoesNotAcquireNewFields() {
        var line = new ExpenseRequest.ApprovedLine(1, money("100"), new BigDecimal("0.1"), "old-approval");
        var request = request(line);
        request.reserve(request.version(), 1, use(1), money("110"));
        var original = json.write(request.state());
        var restored = ExpenseRequest.restore(json.read(original, ExpenseRequest.State.class));
        assertThat(json.write(restored.state())).isEqualTo(original).doesNotContain("\"control\"", "\"ceiling\"");
        fails("INSUFFICIENT_FINANCIAL_BALANCE", () -> restored.reserve(restored.version(), 1, use(1), money("0.01")));
        assertThat(restored.state()).isEqualTo(request.state());
        var explicitStrict = request(controlled(ExpensePriorControl.Mode.STRICT, null));
        fails("INSUFFICIENT_FINANCIAL_BALANCE", () -> explicitStrict.reserve(1, 1, use(1), money("100.01")));
    }

    @Test void referenceLedgerPreservesActualAmountsThroughCloseMoveConsumeReduceAndReverse() {
        for (var mode : List.of(ExpensePriorControl.Mode.TOLERANCE, ExpensePriorControl.Mode.NONE)) {
            var request = request(controlled(mode, mode == ExpensePriorControl.Mode.TOLERANCE ? new BigDecimal("0.1") : null));
            var first = use(1); var second = use(2); var third = use(3);
            request.reserve(request.version(), 1, first, money("120"));
            request.reserve(request.version(), 1, second, money("30"));
            assertThat(request.balance(1).exceeded()).isEqualTo(money(mode == ExpensePriorControl.Mode.TOLERANCE ? "40" : "50"));
            assertThat(request.balance(1).available()).isEqualTo(money("0"));
            request.reserve(request.version(), 1, third, money("10"));
            request.reserve(request.version(), 1, third, money("0"));
            request.consume(request.version(), 1, first);
            request.reduceConsumption(request.version(), 1, first, money("20"), UUID.randomUUID(), Instant.parse("2026-10-04T10:00:00Z"));
            request.close(request.version());
            var before = request.state();
            fails("EXPENSE_REQUEST_CLOSED", () -> request.reserve(request.version(), 1, use(1), money("1")));
            var next = new ExpenseUse(second.reportId(), 2, second.lineNo());
            fails("EXPENSE_REQUEST_CLOSED", () -> request.move(request.version(), 1, second, next, money("31")));
            assertThat(request.state()).isEqualTo(before);
            request.move(request.version(), 1, second, next, money("20"));
            request.consume(request.version(), 1, next);
            request.reverseConsumption(request.version(), 1, next, UUID.randomUUID(), Instant.parse("2026-10-04T11:00:00Z"));
            assertThat(request.balance(1).consumed()).isEqualTo(money("100"));
            assertThat(request.balance(1).hardLimit()).isFalse();
            assertThat(request.balance(1).ceiling()).isEqualTo(ReservedAmount.Ceiling.REFERENCE_ONLY);
            assertThat(ExpenseRequest.restore(request.state()).state()).isEqualTo(request.state());
            fails("RESERVATION_ALREADY_CONSUMED", () -> request.balance(1).reserve(next, money("1")));
            fails("CONCURRENCY_CONFLICT", () -> request.reserve(1, 1, third, money("0")));
        }
    }

    @Test void thresholdRoundsDownAndModeCannotBeChangedThroughTheLedger() {
        var control = new ExpensePriorControl.Snapshot("TRAVEL", 3,
                new ExpensePriorControl(ExpensePriorControl.Mode.TOLERANCE, new BigDecimal("0.5")));
        var line = new ExpenseRequest.ApprovedLine(1, money("0.03"), new BigDecimal("0.5"), "approval", control);
        var request = request(line);
        assertThat(request.balance(1).limit()).isEqualTo(money("0.04"));
        request.reserve(1, 1, use(1), money("0.05"));
        assertThat(request.balance(1).exceeded()).isEqualTo(money("0.01"));
        for (var original : List.of(request(controlled(ExpensePriorControl.Mode.NONE, null)),
                request(new ExpenseRequest.ApprovedLine(1, money("100"), BigDecimal.ZERO, "old")))) {
            var state = original.state();
            var wrong = original.balance(1).hardLimit() ? ReservedAmount.availableWithoutCeiling(money("100")) : ReservedAmount.available(money("100"));
            var altered = new ExpenseRequest.State(state.id(), state.tenantId(), state.applicationId(), state.legalEntityId(),
                    state.employeeId(), state.approvedLines(), Map.of(1, wrong), state.closed(), state.version());
            fails("INVALID_PRIOR_REQUEST", () -> ExpenseRequest.restore(altered));
        }
        fails("JSON_DESERIALIZATION_FAILED", () -> json.read("""
                {"limit":{"value":100,"currency":"CNY"},"consumptions":[],"reservations":[],"ceiling":"UNKNOWN"}
                """, ReservedAmount.class));
    }

    private static ExpenseRequest.ApprovedLine controlled(ExpensePriorControl.Mode mode, BigDecimal fraction) {
        var control = new ExpensePriorControl(mode, fraction);
        return new ExpenseRequest.ApprovedLine(1, money("100"), control.referenceFraction(), "approval",
                new ExpensePriorControl.Snapshot("TRAVEL", 1, control));
    }
    private static void fails(String code, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).code()).isEqualTo(code);
    }

    private static ExpenseRequest request(ExpenseRequest.ApprovedLine line) {
        return new ExpenseRequest(UUID.randomUUID(), "demo", UUID.randomUUID(), UUID.randomUUID(), "alice", List.of(line));
    }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private static ExpenseUse use(int line) { return new ExpenseUse(UUID.randomUUID(), 1, line); }
}
