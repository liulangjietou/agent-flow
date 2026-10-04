package io.agentflow.expense;

import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceGatewayConfiguration;
import io.agentflow.finance.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 保存准备只信服务端匹配结果，验证漏传依据、手改金额及准备和提交之间的状态变化。
 * @author owlzhangfq@gmail.com
 */
class ExpenseAllowancePreparationTest {
    private static final UUID ENTITY = UUID.randomUUID(), POLICY = UUID.randomUUID();
    private static final LocalDate START = LocalDate.of(2026, 10, 1), END = START.plusDays(2);
    private final CurrentActor actors = new CurrentActor();
    private final ExpensePolicyConfiguration policies = mock(ExpensePolicyConfiguration.class);
    private final ExpensePolicyGuidanceService guidance = mock(ExpensePolicyGuidanceService.class);
    private final FinanceGatewayConfiguration gateway = new FinanceGatewayConfiguration();
    private final ExpenseAllowancePreparation service = new ExpenseAllowancePreparation(actors, policies, guidance, gateway);
    private final ExpensePolicySelection selection = new ExpensePolicySelection(POLICY, 1, 1, 1, "a".repeat(64));
    private final ExpensePolicyDefinition.Rule rule = new ExpensePolicyDefinition.Rule("daily", "每日补贴",
            new ExpensePolicyDefinition.Match(List.of(ENTITY), List.of("ALLOWANCE"), List.of("T1"), List.of("G1"), null, null, "CNY"),
            new ExpensePolicyDefinition.Constraints(ExpensePolicyDefinition.Effect.ALLOW, null, null, null, null, List.of(), false,
                    new ExpenseAllowanceRule(money("100"), ExpenseAllowanceRule.DayCountBasis.CALENDAR_DAYS_INCLUSIVE)));

    @BeforeEach void prepare() {
        actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE")));
        var target = new FinanceGatewayConfiguration.Target(); target.setEndpoint("http://127.0.0.1:18280/finance"); target.setAllowUnauthenticatedLoopback(true);
        gateway.setEnabled(true); gateway.setTenants(Map.of("demo", target));
        var definition = new ExpensePolicyDefinition("差旅制度", List.of(rule));
        var publication = new PublishedExpensePolicy(POLICY, "demo", "daily", 1, 1, 1, definition, "admin", Instant.now(), "合成发布");
        var catalog = new ExpenseCategoryCatalog("demo", 1, List.of(new ExpenseCategoryCatalog.Category("ALLOWANCE", "补贴", List.of(ExpenseLine.Unit.DAY), true)));
        when(policies.snapshot("demo")).thenReturn(new ExpensePolicyConfiguration.Snapshot(new ExpenseConfigurationService.Current(catalog, 1, publication), selection));
        when(policies.current("demo", selection)).thenReturn(true);
        when(guidance.read(any())).thenAnswer(call -> view(call.getArgument(0), Instant.now().plusSeconds(120)));
    }
    @AfterEach void clear() { actors.clear(); }

    @Test void missingClientBasisIsCalculatedUsingCurrentEmployeeFacts() {
        var prepared = service.prepare(content(line("3", "300", "0", List.of(), END)));
        var saved = service.requireCurrent(prepared).lines().get(0);
        assertThat(saved.allowance().calculation().days()).isEqualTo(3);
        assertThat(saved.allowance().policy().factSourceReference()).isEqualTo("trusted-source");
        var order = inOrder(policies); order.verify(policies).snapshot("demo"); order.verify(policies).lockForSubmission("demo"); order.verify(policies).current("demo", selection);
    }

    @Test void matchingClientMathCannotForgeTheSavedFactSource() {
        var input = line("3", "300", "0", List.of(), END);
        input = input.withAllowance(ExpenseAllowanceBasis.calculate(new ExpensePolicyReceipt(selection, "daily", "forged-client-source"), rule, ENTITY, input));
        assertThat(service.prepare(content(input)).content().lines().get(0).allowance().policy().factSourceReference()).isEqualTo("trusted-source");
    }

    @Test void omittedBasisCannotBypassAmountDaysTaxOrInvoiceChecks() {
        for (var input : List.of(line("4", "300", "0", List.of(), END), line("3", "301", "0", List.of(), END),
                line("3", "300", "1", List.of(), END), line("3", "300", "0", List.of(UUID.randomUUID()), END))) {
            fails(() -> service.prepare(content(input)), "ALLOWANCE_CALCULATION_MISMATCH");
        }
    }

    @Test void incompleteItineraryDoesNotBecomeAHandEnteredAllowance() {
        fails(() -> service.prepare(content(line("3", "300", "0", List.of(), null))), "ALLOWANCE_ITINERARY_REQUIRED");
    }

    @Test void changingCurrencyCannotSkipMatchingForAPublishedAllowanceCategory() {
        var gross = new Money(new BigDecimal("300"), "USD");
        var input = new ExpenseLine(1, "ALLOWANCE", START, END, "SH", new BigDecimal("3"), ExpenseLine.Unit.DAY, gross, Money.zero("USD"),
                List.of(), null, List.of(new CostAllocation("IT", null, gross)), "修改币种", null);
        fails(() -> service.prepare(content(input)), "ALLOWANCE_POLICY_PERIOD_MISMATCH");
        verify(guidance).read(any());
    }

    @Test void overlappingLinesAreRejectedAfterAuthoritativeBinding() {
        var first = line("3", "300", "0", List.of(), END);
        var second = new ExpenseLine(2, first.categoryCode(), START, END, first.cityCode(), first.quantity(), first.unit(), first.claimedGross(),
                first.claimedTax(), List.of(), null, first.allocations(), "重复行程", null);
        fails(() -> service.prepare(new ExpenseContent(ENTITY, ExpenseContent.Type.TRAVEL, "补贴", List.of(first, second), List.of())), "ALLOWANCE_ITINERARY_OVERLAP");
    }

    @Test void changingPublishedSelectionWhileSavingRejectsThePreparedContent() {
        var prepared = service.prepare(content(line("3", "300", "0", List.of(), END)));
        when(policies.current("demo", selection)).thenReturn(false);
        fails(() -> service.requireCurrent(prepared), "POLICY_CONFIGURATION_CHANGED");
    }

    @Test void actorTargetAndExpiryAreCheckedAgainInsideTheWriteBoundary() {
        var prepared = service.prepare(content(line("3", "300", "0", List.of(), END)));
        actors.set(new Actor("demo", "bob", Set.of("EMPLOYEE")));
        fails(() -> service.requireCurrent(prepared), "FORBIDDEN");
        actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE")));
        gateway.getTenants().get("demo").setEndpoint("http://127.0.0.1:18281/finance");
        fails(() -> service.requireCurrent(prepared), "FINANCE_TARGET_CHANGED");
        gateway.getTenants().get("demo").setEndpoint("http://127.0.0.1:18280/finance");
        var expired = new ExpenseAllowancePreparation.Prepared(prepared.tenantId(), prepared.employeeId(), prepared.selection(), prepared.targetDigest(), Instant.EPOCH, prepared.content());
        fails(() -> service.requireCurrent(expired), "FACTS_EXPIRED");
    }

    @Test void ordinaryDayExpensesDoNotNeedAFinanceConnectionButStillCheckPublicationRace() {
        when(policies.snapshot("demo")).thenReturn(new ExpensePolicyConfiguration.Snapshot(
                new ExpenseConfigurationService.Current(new ExpenseCategoryCatalog("demo", 0, List.of()), 0, null), null));
        when(policies.current("demo", null)).thenReturn(true); gateway.setEnabled(false);
        var prepared = service.prepare(content(line("1.5", "321.23", "12", List.of(UUID.randomUUID()), END)));
        assertThat(service.requireCurrent(prepared).lines().get(0).allowance()).isNull();
        verifyNoInteractions(guidance);
        when(policies.current("demo", null)).thenReturn(false);
        fails(() -> service.requireCurrent(prepared), "POLICY_CONFIGURATION_CHANGED");
    }

    private ExpensePolicyGuidanceService.View view(ExpensePolicyGuidance.Context context, Instant until) {
        var receipt = new ExpensePolicyReceipt(selection, "daily", "trusted-source");
        var basis = context.endedOn() == null ? null : ExpenseAllowanceBasis.calculate(receipt, rule, ENTITY, context.categoryCode(), context.currency(), context.incurredOn(), context.endedOn());
        return new ExpensePolicyGuidanceService.View(context, new ExpensePolicyGuidance(POLICY, 1, "差旅制度", "daily", "每日补贴", rule.constraints(), receipt.factSourceReference(), until, selection), basis);
    }
    private ExpenseLine line(String days, String gross, String tax, List<UUID> invoices, LocalDate end) {
        return new ExpenseLine(1, "ALLOWANCE", START, end, "SH", new BigDecimal(days), ExpenseLine.Unit.DAY, money(gross), money(tax), invoices,
                null, List.of(new CostAllocation("IT", null, money(gross))), "行程补贴", null);
    }
    private ExpenseContent content(ExpenseLine line) { return new ExpenseContent(ENTITY, ExpenseContent.Type.TRAVEL, "补贴", List.of(line), List.of()); }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private static void fails(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOf(DomainException.class).extracting(failure -> ((DomainException) failure).code()).isEqualTo(code);
    }
}
