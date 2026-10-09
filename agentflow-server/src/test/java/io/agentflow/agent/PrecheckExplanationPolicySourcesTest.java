package io.agentflow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.CostAllocation;
import io.agentflow.expense.ExpenseConfigurationService;
import io.agentflow.expense.ExpenseContent;
import io.agentflow.expense.ExpenseLine;
import io.agentflow.expense.ExpensePolicyConfiguration;
import io.agentflow.expense.ExpensePolicyDefinition;
import io.agentflow.expense.ExpensePolicySelection;
import io.agentflow.expense.ExpensePrecheckJob;
import io.agentflow.expense.ExpenseReport;
import io.agentflow.expense.FinanceJsonConfiguration;
import io.agentflow.expense.PublishedExpensePolicy;
import io.agentflow.finance.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 只允许确定适用的已发布条款出现在本人可选目录；未知企业条件不能被模型或兜底规则替代。
 * @author owlzhangfq@gmail.com
 */
class PrecheckExplanationPolicySourcesTest {
    private final ExpensePolicyConfiguration policies = mock(ExpensePolicyConfiguration.class);
    private final PrecheckExplanationSources sources = new PrecheckExplanationSources(new JsonUtil(new ObjectMapper().findAndRegisterModules().registerModule(new FinanceJsonConfiguration().financeMoneyModule())), policies);
    private final UUID entity = UUID.randomUUID();
    private final ExpensePolicySelection selection = new ExpensePolicySelection(UUID.randomUUID(), 2, 1, 3, "a".repeat(64));
    private final ExpensePrecheckJob job = mock(ExpensePrecheckJob.class, RETURNS_DEEP_STUBS);

    @Test void exposesVersionedConstraintWithoutOtherEntitySelectorsOrPersonalFacts() {
        configure(List.of(rule("foreign", UUID.randomUUID(), List.of(), "CNY"), rule("office", entity, List.of(), "CNY")), selection);
        var choices = sources.available(report(), job);
        var policy = choices.stream().filter(value -> value.reference().sourceId().equals("expense:policy[1]")).findFirst().orElseThrow();
        assertThat(policy.content()).contains("\"policyVersion\":2", "\"ruleKey\":\"office\"", "\"value\":\"80.00\"", "a".repeat(64))
                .doesNotContain("foreign", "employeeGrades", "legalEntityIds", "alice", "private");
    }

    @Test void unknownEarlierEmployeeConditionDoesNotFallThroughToMorePermissiveRule() {
        configure(List.of(rule("senior", entity, List.of("PRIVATE_GRADE"), "CNY"), rule("fallback", entity, List.of(), "CNY")), selection);
        assertNoPolicy();
    }

    @Test void otherEntitiesCurrenciesAndChangedPublicationsCannotBecomeSources() {
        configure(List.of(rule("foreign", UUID.randomUUID(), List.of(), "CNY"), rule("dollar", entity, List.of(), "USD")), selection);
        assertNoPolicy();
        configure(List.of(rule("office", entity, List.of(), "CNY")), new ExpensePolicySelection(selection.policyId(), 3, 1, 4, "b".repeat(64)));
        assertNoPolicy();
    }

    private void assertNoPolicy() {
        assertThat(sources.available(report(), job)).noneMatch(value -> value.reference().sourceId().startsWith("expense:policy"));
    }
    private void configure(List<ExpensePolicyDefinition.Rule> rules, ExpensePolicySelection active) {
        when(job.status()).thenReturn(ExpensePrecheckJob.Status.BLOCKED);
        when(job.completedAt()).thenReturn(Instant.now());
        when(job.input().accountingDate()).thenReturn(LocalDate.now());
        when(job.result().findings()).thenReturn(List.of());
        when(job.result().observation().policySelection()).thenReturn(selection);
        var publication = new PublishedExpensePolicy(active.policyId(), "demo", "fixture", active.policyVersion(), active.policyVersion(), 1,
                new ExpensePolicyDefinition("合成制度", rules), "admin", Instant.now(), "合成发布");
        var current = new ExpenseConfigurationService.Current(null, active.activeRevision(), publication);
        when(policies.snapshot("demo")).thenReturn(new ExpensePolicyConfiguration.Snapshot(current, active));
    }
    private ExpenseReport report() {
        var gross = new Money(new BigDecimal("100"), "CNY");
        var line = new ExpenseLine(1, "OFFICE", LocalDate.now(), null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM, gross, Money.zero("CNY"),
                List.of(), null, List.of(new CostAllocation("private", null, gross)), "private", null);
        return ExpenseReport.draft(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice", new ExpenseContent(entity, ExpenseContent.Type.DAILY, "private", List.of(line), List.of()));
    }
    private ExpensePolicyDefinition.Rule rule(String key, UUID owner, List<String> grades, String currency) {
        return new ExpensePolicyDefinition.Rule(key, "合成条款", new ExpensePolicyDefinition.Match(List.of(owner), List.of("OFFICE"), List.of(), grades, null, null, currency),
                new ExpensePolicyDefinition.Constraints(ExpensePolicyDefinition.Effect.ALLOW, new Money(new BigDecimal("80"), currency), ExpenseLine.Unit.ITEM, null, null, List.of(), false));
    }
}
