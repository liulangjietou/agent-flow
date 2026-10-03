package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.Money;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import io.agentflow.organization.InitiatorContext;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.expense.ExpensePlanCheck.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 覆盖表单实际类型、旁路路径与持久预检的跨轮次和超时边界。
 * @author owlzhangfq@gmail.com
 */
class ExpensePlanContractTest {
    private static final Instant NOW = Instant.parse("2026-09-28T02:00:00Z");
    private static final UUID ENTITY = UUID.randomUUID();

    @Test void realFormTypesAreAcceptedAndMissingOrNonSensitiveDetailsAreRejected() {
        var schema = schema(FieldVisibility.READ_ONLY);
        assertThatCode(() -> ExpensePlanFormContract.requireSchema(schema)).doesNotThrowAnyException();
        assertThat(ExpensePlanFormContract.structured(schema)).isTrue();
        assertThat(ExpensePlanFormContract.structured(null)).isFalse();
        fails("EXPENSE_PLAN_FORM_REQUIRED", () -> ExpensePlanFormContract.requireSchema(null));
        fails("EXPENSE_PLAN_FORM_REQUIRED", () -> ExpensePlanFormContract.requireSchema(new FormSchema(2, List.of(new FormSchema.Field("amount", "金额", FormSchema.FieldType.NUMBER, true, null, null, null, null, null)))));
        var fields = new ArrayList<>(schema.fields()); fields.set(0, new FormSchema.Field(ExpensePlanFormContract.DETAILS, "计划", FormSchema.FieldType.TEXT, true, null, null, null, null, null));
        fails("EXPENSE_PLAN_FORM_REQUIRED", () -> ExpensePlanFormContract.requireSchema(new FormSchema(2, fields)));
    }

    @Test void aReviewOnOnlyOneConditionalOrParallelBranchCannotAuthorizeTheWholePlan() {
        for (var gateway : List.of(NodeType.EXCLUSIVE_GATEWAY, NodeType.PARALLEL_GATEWAY)) {
            var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("split", "分支", gateway, Map.of()),
                    new Node("review", "审核", NodeType.USER_TASK, Map.of()), new Node("end", "结束", NodeType.END, Map.of())),
                    List.of(edge("a", "start", "split"), edge("b", "split", "review"), edge("c", "review", "end"), edge("d", "split", "end")));
            fails("EXPENSE_PLAN_REVIEW_REQUIRED", () -> ExpensePlanFormContract.requireReview(graph, schema(FieldVisibility.READ_ONLY)));
        }
        assertThatCode(() -> ExpensePlanFormContract.requireReview(reviewGraph(), schema(FieldVisibility.READ_ONLY))).doesNotThrowAnyException();
    }

    @Test void maskedDetailsNeverSatisfyReviewOrBusinessReadAuthorization() {
        var original = schema(FieldVisibility.READ_ONLY);
        for (var visibility : List.of(FieldVisibility.HIDDEN, FieldVisibility.MASKED)) {
            fails("EXPENSE_PLAN_REVIEW_FIELDS_REQUIRED", () -> ExpensePlanFormContract.requireReview(reviewGraph(), schema(visibility)));
            assertThat(ExpensePlanFormContract.detailsReadable(original, schema(visibility))).isFalse();
        }
        assertThat(ExpensePlanFormContract.detailsReadable(original, original)).isTrue();
    }

    @Test void lateFactsCannotCompleteAnExpiredLeaseOrOverwriteATerminalCheck() {
        var running = ExpensePlanCheck.queue(input(), NOW).start(NOW, NOW.plusSeconds(60));
        var finished = running.finish(Result.ready(evidence()), NOW.plusSeconds(60));
        assertThat(finished.status()).isEqualTo(Status.UNAVAILABLE); assertThat(finished.result().code()).isEqualTo("TIMEOUT");
        fails("EXPENSE_PLAN_CHECK_STATE_CONFLICT", () -> finished.finish(Result.ready(evidence()), NOW.plusSeconds(61)));
    }

    @Test void readyEvidenceCannotBelongToAnotherRoundOrPlanVersion() {
        var input = input(); var running = ExpensePlanCheck.queue(input, NOW).start(NOW, NOW.plusSeconds(60));
        assertThat(running.finish(Result.ready(evidence()), NOW.plusSeconds(1)).status()).isEqualTo(Status.READY);
        var changed = new Input(input.id(), input.tenantId(), input.planId(), input.applicationId(), input.employeeId(), input.applicationVersion(),
                2, 2, input.attempt(), input.initiator(), input.targetDigest());
        fails("INVALID_EXPENSE_PLAN_CHECK", () -> ExpensePlanCheck.queue(changed, NOW).start(NOW, NOW.plusSeconds(60)).finish(Result.ready(evidence()), NOW.plusSeconds(1)));
        fails("INVALID_EXPENSE_PLAN_CHECK", () -> new Result(Status.READY, null, "FAKE_APPROVED"));
    }

    @Test void mismatchingConversionSourceOrExpiredEvidenceCannotBePersistedAsReady() {
        var evidence = evidence();
        fails("INVALID_EXPENSE_PLAN_CHECK", () -> new Evidence(evidence.catalog(), Map.of("CNY", new ExpenseExchangeRate("CNY", "CNY", BigDecimal.ONE, "other-source", LocalDate.of(2026, 9, 28))), evidence.preview(), evidence.validUntil()));
        var running = ExpensePlanCheck.queue(input(), NOW).start(NOW, NOW.plusSeconds(500));
        fails("INVALID_EXPENSE_PLAN_CHECK", () -> running.finish(Result.ready(evidence), evidence.validUntil()));
    }

    private static Input input() {
        var initiator = new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, ENTITY, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位");
        return new Input(UUID.randomUUID(), "demo", UUID.randomUUID(), UUID.randomUUID(), "alice", 1, 1, 1, 1, initiator, "a".repeat(64));
    }
    private static Evidence evidence() {
        var catalog = new FinanceCatalog("alice", "catalog-v1", NOW.plusSeconds(300), List.of(new FinanceCatalog.LegalEntity(ENTITY, "法人", "CNY", false, "entity-v1", "Asia/Shanghai")), List.of(new FinanceCatalog.Category("TRAVEL", "差旅", List.of(ExpenseLine.Unit.ITEM))), List.of(new FinanceCatalog.CostCenter(ENTITY, "IT", "研发")), List.of(), List.of(new FinanceCatalog.City("SH", "上海")));
        var money = new Money(new BigDecimal("100"), "CNY");
        var line = new ExpensePlanContent.Line(1, "TRAVEL", LocalDate.of(2026, 9, 28), null, "SH", money, List.of(new CostAllocation("IT", null, money)), "计划依据");
        var plan = ExpensePlan.draft(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice", new ExpensePlanContent(ENTITY, ExpenseContent.Type.TRAVEL, "事前申请", List.of(line)));
        var rates = Map.of("CNY", new ExpenseExchangeRate("CNY", "CNY", BigDecimal.ONE, "source", LocalDate.of(2026, 9, 28)));
        plan.freeze(1, 1, catalog, rates, input().initiator(), NOW);
        return new Evidence(catalog, rates, plan.currentRound(), NOW.plusSeconds(300));
    }
    private static FormSchema schema(FieldVisibility visibility) {
        return new FormSchema(2, List.of(new FormSchema.Field(ExpensePlanFormContract.DETAILS, "计划", FormSchema.FieldType.TEXT, true, null, null, null, null, null, null, null, true, Map.of("review", visibility)),
                new FormSchema.Field("amount", "金额", FormSchema.FieldType.NUMBER, true, null, null, null, null, null), new FormSchema.Field("currency", "币种", FormSchema.FieldType.TEXT, true, null, null, null, null, null)));
    }
    private static Graph reviewGraph() { return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("review", "审核", NodeType.USER_TASK, Map.of()), new Node("end", "结束", NodeType.END, Map.of())), List.of(edge("a", "start", "review"), edge("b", "review", "end"))); }
    private static Edge edge(String id, String source, String target) { return new Edge(id, source, target, "", false); }
    private static void fails(String code, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code)); }
}
