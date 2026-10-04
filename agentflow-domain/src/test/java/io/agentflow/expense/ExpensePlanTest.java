package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.Money;
import io.agentflow.organization.InitiatorContext;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 事前授权围绕快照、严格额度、真实目录归属与汇兑边界验证，不把申请草稿当作可用资金。
 * @author owlzhangfq@gmail.com
 */
class ExpensePlanTest {
    private static final UUID ENTITY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-28T01:00:00Z");
    private static final LocalDate DATE = LocalDate.parse("2026-09-28");

    @Test void approvedCreditKeepsEveryExplicitModeAndOriginalCategorySource() {
        for (var mode : ExpensePriorControl.Mode.values()) {
            var control = new ExpensePriorControl(mode, mode == ExpensePriorControl.Mode.TOLERANCE ? new BigDecimal("0.125") : null);
            var source = new ExpensePriorControl.Snapshot("TRAVEL", 2, control);
            var plan = plan(content(List.of(line(1, "100", "CNY"), line(2, "50", "CNY"))));
            var controls = new java.util.HashMap<>(Map.of("TRAVEL", source));
            plan.freeze(1, 1, catalog(), rates(), initiator(ENTITY, "alice"), NOW, 2L, controls); controls.clear();
            var restored = ExpensePlan.restore(plan.state());
            assertThat(restored.currentRound().lines()).allSatisfy(frozen -> assertThat(frozen.priorControl()).isEqualTo(source));
            var approved = restored.approvedRequest(1);
            assertThat(approved.approvedLines()).allSatisfy(value -> assertThat(value.control()).isEqualTo(source));
            assertThat(approved.balance(1).hardLimit()).isEqualTo(mode == ExpensePriorControl.Mode.STRICT);
            assertThat(approved.balance(1).limit()).isEqualTo(money(mode == ExpensePriorControl.Mode.TOLERANCE ? "112.50" : "100", "CNY"));
        }
    }

    @Test void frozenControlsCannotClaimAnotherCategoryOrRevision() {
        var control = new ExpensePriorControl(ExpensePriorControl.Mode.NONE, null);
        for (var source : List.of(new ExpensePriorControl.Snapshot("OTHER", 2, control), new ExpensePriorControl.Snapshot("TRAVEL", 3, control))) {
            var plan = plan(content(List.of(line(1, "100", "CNY"))));
            fails("INVALID_EXPENSE_PLAN_ROUND", () -> plan.freeze(1, 1, catalog(), rates(), initiator(ENTITY, "alice"), NOW, 2L, Map.of("TRAVEL", source)));
            assertThat(plan.rounds()).isEmpty();
        }
        var plan = plan(content(List.of(line(1, "100", "CNY"), line(2, "50", "CNY")))); freeze(plan);
        var first = plan.currentRound().lines().get(0); var second = plan.currentRound().lines().get(1);
        var changed = new ExpensePlanRound.FrozenLine(second.original(), second.rate(), second.amount(), second.allocations(),
                new ExpensePriorControl.Snapshot("TRAVEL", 2, control));
        var round = plan.currentRound();
        fails("INVALID_EXPENSE_PLAN_ROUND", () -> new ExpensePlanRound(round.roundNo(), round.submittedPlanVersion(), round.submittedBy(),
                round.submittedAt(), round.content(), round.legalEntity(), round.catalogVersion(), List.of(first, changed), 2L));
    }

    @Test void emptyDraftCannotGenerateCreditOrBeSubmitted() {
        var plan = plan(content(List.of()));
        fails("EXPENSE_PLAN_NOT_SUBMITTED", () -> plan.approvedRequest(1));
        fails("EXPENSE_PLAN_LINES_REQUIRED", () -> freeze(plan));
        assertThat(plan.version()).isEqualTo(1); assertThat(plan.rounds()).isEmpty();
    }

    @Test void originalLinesAndAllocationsAreCopiedAndMustBalanceExactly() {
        var allocations = new ArrayList<>(List.of(allocation("IT", "100", "CNY")));
        var line = line(7, "100", "CNY", allocations);
        var lines = new ArrayList<>(List.of(line)); var content = content(lines); allocations.clear(); lines.clear();
        assertThat(content.lines()).hasSize(1); assertThat(content.lines().get(0).allocations()).hasSize(1);
        fails("ALLOCATION_UNBALANCED", () -> line(1, "100", "CNY", List.of(allocation("IT", "99.99", "CNY"))));
        fails("INVALID_EXPENSE_PLAN", () -> line(1, "100", "CNY", List.of(allocation("IT", "50", "CNY"), allocation("IT", "50", "CNY"))));
        fails("INVALID_EXPENSE_PLAN", () -> content(List.of(line, line)));
        fails("CURRENCY_MISMATCH", () -> line(1, "100", "CNY", List.of(allocation("IT", "100", "USD"))));
    }

    @Test void currentFrozenLinesCreateStrictCreditWithoutImplicitTolerance() {
        var plan = plan(content(List.of(line(7, "100", "CNY")))); freeze(plan);
        var request = plan.approvedRequest(1);
        assertThat(request.id()).isEqualTo(plan.id()); assertThat(request.applicationId()).isEqualTo(plan.applicationId());
        assertThat(request.employeeId()).isEqualTo("alice"); assertThat(request.balance(7).limit()).isEqualTo(money("100", "CNY"));
        assertThat(request.approvedLines().get(0).toleranceFraction()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(request.approvedLines().get(0).policyReference()).isEqualTo("APPROVAL:" + plan.applicationId() + ":1");
        assertThat(request.balance(7).reservations()).isEmpty(); assertThat(request.balance(7).consumptions()).isEmpty();
    }

    @Test void returnAndResubmissionPreserveOriginalRoundAndInvalidateOldGrantInput() {
        var plan = plan(content(List.of(line(1, "100", "CNY")))); freeze(plan);
        var first = plan.currentRound(); plan.revise(2, content(List.of(line(1, "50", "CNY"))));
        fails("INVALID_EXPENSE_PLAN", () -> plan.approvedRequest(1));
        plan.freeze(3, 2, catalog(), rates(), initiator(ENTITY, "alice"), NOW.plusSeconds(1));
        assertThat(plan.rounds().get(0)).isEqualTo(first); assertThat(first.total()).isEqualTo(money("100", "CNY"));
        assertThat(plan.approvedRequest(2).balance(1).limit()).isEqualTo(money("50", "CNY"));
        fails("INVALID_EXPENSE_PLAN", () -> plan.approvedRequest(1));
        fails("CONCURRENCY_CONFLICT", () -> plan.revise(3, plan.content()));
    }

    @Test void foreignRowsRoundToBaseCurrencyBeforeTotalsAndKeepOriginalAmounts() {
        var plan = plan(content(List.of(line(1, "0.01", "USD"), line(2, "0.01", "USD"))));
        var rate = new ExpenseExchangeRate("USD", "CNY", new BigDecimal("1.5"), "verified-source", DATE);
        plan.freeze(1, 1, catalog(), Map.of("USD", rate), initiator(ENTITY, "alice"), NOW);
        assertThat(plan.currentRound().total()).isEqualTo(money("0.04", "CNY"));
        assertThat(plan.currentRound().lines()).allSatisfy(line -> {
            assertThat(line.original().amount()).isEqualTo(money("0.01", "USD")); assertThat(line.rate()).isEqualTo(rate);
            assertThat(line.amount()).isEqualTo(money("0.02", "CNY")); assertThat(line.allocations().get(0).amount()).isEqualTo(money("0.02", "CNY"));
        });
    }

    @Test void catalogExpiryApplicantAndLegalEntityMismatchCannotFreezePartialState() {
        var plan = plan(content(List.of(line(1, "100", "CNY")))); var c = catalog();
        for (var invalid : List.of(new FinanceCatalog("bob", c.sourceVersion(), c.validUntil(), c.legalEntities(), c.categories(), c.costCenters(), c.projects(), c.cities()),
                new FinanceCatalog(c.employeeId(), c.sourceVersion(), NOW, c.legalEntities(), c.categories(), c.costCenters(), c.projects(), c.cities()))) {
            fails("EXPENSE_PLAN_CATALOG_CHANGED", () -> plan.freeze(1, 1, invalid, rates(), initiator(ENTITY, "alice"), NOW));
        }
        for (var initiator : List.of(initiator(UUID.randomUUID(), "alice"), initiator(ENTITY, "bob"))) {
            fails("EXPENSE_PLAN_INITIATOR_MISMATCH", () -> plan.freeze(1, 1, c, rates(), initiator, NOW));
        }
        assertThat(plan.version()).isEqualTo(1); assertThat(plan.rounds()).isEmpty();
    }

    @Test void nonexistentCategoryCityAndForeignCostObjectAreRejected() {
        var line = line(1, "100", "CNY"); var c = catalog();
        for (var invalid : List.of(new FinanceCatalog(c.employeeId(), c.sourceVersion(), c.validUntil(), c.legalEntities(), List.of(), c.costCenters(), c.projects(), c.cities()),
                new FinanceCatalog(c.employeeId(), c.sourceVersion(), c.validUntil(), c.legalEntities(), c.categories(), c.costCenters(), c.projects(), List.of()))) {
            var plan = plan(content(List.of(line)));
            fails("EXPENSE_PLAN_CATEGORY_UNAVAILABLE", () -> plan.freeze(1, 1, invalid, rates(), initiator(ENTITY, "alice"), NOW));
        }
        var plan = plan(content(List.of(line(1, "100", "CNY", List.of(allocation("FOREIGN", "100", "CNY"))))));
        fails("COST_OBJECT_UNAVAILABLE", () -> freeze(plan)); assertThat(plan.version()).isEqualTo(1);
    }

    @Test void missingWrongDateOrWrongTargetRatesCannotGenerateApprovalAmounts() {
        var plan = plan(content(List.of(line(1, "100", "USD"))));
        fails("EXPENSE_PLAN_RATE_REQUIRED", () -> plan.freeze(1, 1, catalog(), rates(), initiator(ENTITY, "alice"), NOW));
        fails("EXPENSE_PLAN_RATE_REQUIRED", () -> plan.freeze(1, 1, catalog(), Map.of("USD", new ExpenseExchangeRate("USD", "EUR", BigDecimal.ONE, "source", DATE)), initiator(ENTITY, "alice"), NOW));
        fails("INVALID_EXPENSE_PLAN_ROUND", () -> plan.freeze(1, 1, catalog(), Map.of("USD", new ExpenseExchangeRate("USD", "CNY", BigDecimal.ONE, "source", DATE.minusDays(1))), initiator(ENTITY, "alice"), NOW));
        assertThat(plan.version()).isEqualTo(1); assertThat(plan.rounds()).isEmpty();
    }

    @Test void rateDateFollowsLegalEntityTimeZoneAndNotServerDay() {
        var c = catalog(); var e = c.legalEntities().get(0);
        var west = new FinanceCatalog(c.employeeId(), c.sourceVersion(), c.validUntil(), List.of(new FinanceCatalog.LegalEntity(e.id(), e.name(), e.baseCurrency(), e.paperReceiptRequired(), e.sourceVersion(), "America/New_York")), c.categories(), c.costCenters(), c.projects(), c.cities());
        var plan = plan(content(List.of(line(1, "100", "CNY"))));
        fails("INVALID_EXPENSE_PLAN_ROUND", () -> plan.freeze(1, 1, west, rates(), initiator(ENTITY, "alice"), NOW));
        plan.freeze(1, 1, west, Map.of("CNY", new ExpenseExchangeRate("CNY", "CNY", BigDecimal.ONE, "source", DATE.minusDays(1))), initiator(ENTITY, "alice"), NOW);
        assertThat(plan.currentRound().total()).isEqualTo(money("100", "CNY"));
    }

    @Test void overflowOrZeroAfterConversionLeavesNoHalfFrozenRound() {
        var overflow = plan(content(List.of(line(1, Money.MAX_VALUE.toPlainString(), "CNY"), line(2, "0.01", "CNY"))));
        fails("INVALID_MONEY", () -> freeze(overflow)); assertThat(overflow.version()).isEqualTo(1); assertThat(overflow.rounds()).isEmpty();
        var zero = plan(content(List.of(line(1, "0.01", "USD"))));
        fails("INVALID_EXPENSE_PLAN_ROUND", () -> zero.freeze(1, 1, catalog(), Map.of("USD", new ExpenseExchangeRate("USD", "CNY", new BigDecimal("0.001"), "source", DATE)), initiator(ENTITY, "alice"), NOW));
        assertThat(zero.version()).isEqualTo(1);
    }

    @Test void restoreRejectsChangedFrozenContentOrNonSequentialHistory() {
        var plan = plan(content(List.of(line(1, "100", "CNY")))); freeze(plan); var state = plan.state();
        assertThat(ExpensePlan.restore(state).state()).isEqualTo(state);
        fails("INVALID_EXPENSE_PLAN", () -> ExpensePlan.restore(new ExpensePlan.State(state.id(), state.tenantId(), state.applicationId(), state.employeeId(), content(List.of(line(1, "200", "CNY"))), state.rounds(), state.version())));
        fails("INVALID_EXPENSE_PLAN", () -> ExpensePlan.restore(new ExpensePlan.State(state.id(), state.tenantId(), state.applicationId(), state.employeeId(), state.content(), List.of(state.rounds().get(0), state.rounds().get(0)), state.version())));
        fails("INVALID_EXPENSE_PLAN", () -> ExpensePlan.restore(new ExpensePlan.State(state.id(), state.tenantId(), state.applicationId(), "bob", state.content(), state.rounds(), state.version())));
    }

    @Test void managedCategoryRevisionMustBePositiveAndSurvivesRestoringTheRound() {
        var plan = plan(content(List.of(line(1, "100", "CNY"))));
        for (long revision : List.of(0L, -1L)) {
            fails("INVALID_EXPENSE_PLAN_ROUND", () -> plan.freeze(1, 1, catalog(), rates(), initiator(ENTITY, "alice"), NOW, revision));
            assertThat(plan.version()).isEqualTo(1); assertThat(plan.rounds()).isEmpty();
        }
        plan.freeze(1, 1, catalog(), rates(), initiator(ENTITY, "alice"), NOW, 2L);
        assertThat(ExpensePlan.restore(plan.state()).currentRound().managedCategoryRevision()).isEqualTo(2L);
        var unmanaged = plan(content(List.of(line(1, "100", "CNY")))); freeze(unmanaged);
        assertThat(unmanaged.currentRound().managedCategoryRevision()).isNull();
    }

    private static void freeze(ExpensePlan plan) { plan.freeze(1, 1, catalog(), rates(), initiator(ENTITY, "alice"), NOW); }
    private static ExpensePlan plan(ExpensePlanContent content) { return ExpensePlan.draft(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice", content); }
    private static ExpensePlanContent content(List<ExpensePlanContent.Line> lines) { return new ExpensePlanContent(ENTITY, ExpenseContent.Type.TRAVEL, "差旅事前申请", lines); }
    private static ExpensePlanContent.Line line(int number, String value, String currency) { return line(number, value, currency, List.of(allocation("IT", value, currency))); }
    private static ExpensePlanContent.Line line(int number, String value, String currency, List<CostAllocation> allocations) { return new ExpensePlanContent.Line(number, "TRAVEL", DATE, DATE.plusDays(1), "SH", money(value, currency), allocations, "客户现场交流计划"); }
    private static CostAllocation allocation(String center, String value, String currency) { return new CostAllocation(center, null, money(value, currency)); }
    private static Money money(String value, String currency) { return new Money(new BigDecimal(value), currency); }
    private static Map<String, ExpenseExchangeRate> rates() { return Map.of("CNY", new ExpenseExchangeRate("CNY", "CNY", BigDecimal.ONE, "source", DATE)); }
    private static InitiatorContext initiator(UUID entity, String employee) { return new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), employee, 1, entity, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位"); }
    private static FinanceCatalog catalog() { return new FinanceCatalog("alice", "v1", NOW.plusSeconds(300), List.of(new FinanceCatalog.LegalEntity(ENTITY, "法人", "CNY", false, "entity-v1", "Asia/Shanghai")), List.of(new FinanceCatalog.Category("TRAVEL", "差旅", List.of(ExpenseLine.Unit.ITEM))), List.of(new FinanceCatalog.CostCenter(ENTITY, "IT", "研发")), List.of(), List.of(new FinanceCatalog.City("SH", "上海"))); }
    private static void fails(String code, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code)); }
}
