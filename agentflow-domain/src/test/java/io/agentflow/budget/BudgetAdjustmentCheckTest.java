package io.agentflow.budget;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import io.agentflow.organization.InitiatorContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.budget.BudgetAdjustmentTestSupport.*;
import static io.agentflow.budget.BudgetAdjustmentCheck.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 预检不能混用旧内容或目的地；预算调整路由只能由完整敏感明细和人工审核支撑。
 * @author owlzhangfq@gmail.com
 */
class BudgetAdjustmentCheckTest {
    @Test void formRequiresExactDerivedFieldsAndSensitiveProcurementDetails() {
        assertThatCode(() -> BudgetAdjustmentFormContract.requireSchema(schema(FieldVisibility.READ_ONLY))).doesNotThrowAnyException();
        assertThat(BudgetAdjustmentFormContract.structured(schema(FieldVisibility.READ_ONLY))).isTrue();
        assertThat(BudgetAdjustmentFormContract.structured(null)).isFalse();
        fails("BUDGET_ADJUSTMENT_FORM_REQUIRED", () -> BudgetAdjustmentFormContract.requireSchema(null));
        var fields = new ArrayList<>(schema(FieldVisibility.READ_ONLY).fields());
        fields.set(0, new FormSchema.Field(BudgetAdjustmentFormContract.DETAILS, "预算调整", FormSchema.FieldType.TEXT, true, null, null, null, null, null));
        fails("BUDGET_ADJUSTMENT_FORM_REQUIRED", () -> BudgetAdjustmentFormContract.requireSchema(new FormSchema(2, fields)));
        assertThat(BudgetAdjustmentFormContract.draftPayload()).doesNotContainKey("amount");
        assertThat(BudgetAdjustmentFormContract.submittedPayload(evidence().preview()).get("amount")).isEqualTo("70.00");
    }

    @Test void everyEndingPathNeedsReadableHumanReview() {
        for (var gateway : List.of(NodeType.EXCLUSIVE_GATEWAY, NodeType.PARALLEL_GATEWAY)) {
            var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("split", "分支", gateway, Map.of()),
                    new Node("review", "审核", NodeType.USER_TASK, Map.of()), new Node("end", "结束", NodeType.END, Map.of())),
                    List.of(edge("a", "start", "split"), edge("b", "split", "review"), edge("c", "review", "end"), edge("d", "split", "end")));
            fails("BUDGET_ADJUSTMENT_REVIEW_REQUIRED", () -> BudgetAdjustmentFormContract.requireReview(graph, schema(FieldVisibility.READ_ONLY)));
        }
        assertThatCode(() -> BudgetAdjustmentFormContract.requireReview(graph(), schema(FieldVisibility.READ_ONLY))).doesNotThrowAnyException();
        for (var visibility : List.of(FieldVisibility.HIDDEN, FieldVisibility.MASKED)) {
            fails("BUDGET_ADJUSTMENT_REVIEW_FIELDS_REQUIRED", () -> BudgetAdjustmentFormContract.requireReview(graph(), schema(visibility)));
            assertThat(BudgetAdjustmentFormContract.detailsReadable(schema(FieldVisibility.READ_ONLY), schema(visibility))).isFalse();
        }
    }

    @Test void lateSuccessfulFactsCannotOverwriteExpiredOrFinishedChecks() {
        var queued = queue(input(), NOW); var running = queued.start(NOW, NOW.plusSeconds(60));
        assertThat(running.finish(Result.ready(evidence()), NOW.plusSeconds(2)).status()).isEqualTo(Status.READY);
        var timeout = running.finish(Result.ready(evidence()), NOW.plusSeconds(60));
        assertThat(timeout.status()).isEqualTo(Status.UNAVAILABLE); assertThat(timeout.result().code()).isEqualTo("TIMEOUT");
        fails("BUDGET_ADJUSTMENT_CHECK_STATE_CONFLICT", () -> timeout.finish(Result.ready(evidence()), NOW.plusSeconds(61)));
        fails("BUDGET_ADJUSTMENT_CHECK_STATE_CONFLICT", () -> running.start(NOW, NOW.plusSeconds(60)));
    }

    @Test void readyFactsCannotBeReboundToDifferentContentRoundOrDestination() {
        var input = input();
        for (var changed : List.of(
                new Input(input.id(), input.tenantId(), input.requestId(), input.applicationId(), input.employeeId(), 1, 1, 2, 1, input.initiator(), input.targetDigest(), input.content()),
                new Input(input.id(), input.tenantId(), input.requestId(), input.applicationId(), input.employeeId(), 1, 2, 1, 1, input.initiator(), input.targetDigest(), input.content()),
                new Input(input.id(), input.tenantId(), input.requestId(), input.applicationId(), input.employeeId(), 1, 1, 1, 1, input.initiator(), "b".repeat(64), input.content()),
                new Input(input.id(), input.tenantId(), input.requestId(), input.applicationId(), input.employeeId(), 1, 1, 1, 1, input.initiator(), input.targetDigest(), content("60")))) {
            fails("INVALID_BUDGET_ADJUSTMENT_CHECK", () -> queue(changed, NOW).start(NOW, NOW.plusSeconds(60)).finish(Result.ready(evidence()), NOW.plusSeconds(2)));
        }
    }

    @Test void evidenceCannotOutliveOriginalCatalogOrLedgerObservationWindow() {
        var evidence = evidence();
        fails("INVALID_BUDGET_ADJUSTMENT_CHECK", () -> new Evidence(evidence.catalog(), evidence.preview(), NOW.plusSeconds(301)));
        fails("INVALID_BUDGET_ADJUSTMENT_CHECK", () -> new Evidence(evidence.catalog(), evidence.preview(), evidence.preview().submittedAt()));
        fails("INVALID_BUDGET_ADJUSTMENT_CHECK", () -> queue(input(), NOW).start(NOW, NOW.plusSeconds(500)).finish(Result.ready(evidence), evidence.validUntil()));
        fails("INVALID_BUDGET_ADJUSTMENT_CHECK", () -> new Result(Status.READY, null, "FAKE_APPROVAL"));
        fails("INVALID_BUDGET_ADJUSTMENT_CHECK", () -> new Result(Status.UNAVAILABLE, evidence, null));
    }

    private static BudgetAdjustmentContent content(String amount) { return BudgetAdjustmentTestSupport.content(BudgetAdjustmentContent.Type.TRANSFER, amount); }
    private static Input input() {
        var initiator = new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, ENTITY, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位");
        return new Input(UUID.randomUUID(), "demo", UUID.randomUUID(), UUID.randomUUID(), "alice", 1, 1, 1, 1, initiator, "a".repeat(64), content("70"));
    }
    private static Evidence evidence() {
        var catalog = new FinanceCatalog("alice", "catalog-v1", NOW.plusSeconds(600), List.of(new FinanceCatalog.LegalEntity(ENTITY, "法人", "CNY", false, "entity-v1", "Asia/Shanghai")), List.of(), List.of(), List.of(), List.of());
        var request = BudgetAdjustmentRequest.draft(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice", content("70"));
        request.freeze(1, 1, catalog, "a".repeat(64), ledger(content("70")), input().initiator(), NOW.plusSeconds(1));
        return new Evidence(catalog, request.currentRound(), NOW.plusSeconds(299));
    }
    private static FormSchema schema(FieldVisibility visibility) {
        return new FormSchema(2, List.of(new FormSchema.Field(BudgetAdjustmentFormContract.DETAILS, "预算调整", FormSchema.FieldType.TEXT, true, null, null, null, null, null, null, null, true, Map.of("review", visibility)),
                new FormSchema.Field("amount", "金额", FormSchema.FieldType.NUMBER, true, null, null, null, null, null), new FormSchema.Field("currency", "币种", FormSchema.FieldType.TEXT, true, null, null, null, null, null)));
    }
    private static Graph graph() { return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("review", "审核", NodeType.USER_TASK, Map.of()), new Node("end", "结束", NodeType.END, Map.of())), List.of(edge("a", "start", "review"), edge("b", "review", "end"))); }
    private static Edge edge(String id, String source, String target) { return new Edge(id, source, target, "", false); }
    private static void fails(String code, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code)); }
}
