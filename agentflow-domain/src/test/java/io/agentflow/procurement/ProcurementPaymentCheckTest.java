package io.agentflow.procurement;

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
import static io.agentflow.procurement.ProcurementPayableTest.*;
import static io.agentflow.procurement.ProcurementPaymentCheck.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 预检不能混用旧内容或目的地；采购路由只能由完整敏感明细和人工审核支撑。
 * @author owlzhangfq@gmail.com
 */
class ProcurementPaymentCheckTest {
    @Test void formRequiresExactDerivedFieldsAndSensitiveProcurementDetails() {
        assertThatCode(() -> ProcurementPaymentFormContract.requireSchema(schema(FieldVisibility.READ_ONLY))).doesNotThrowAnyException();
        assertThat(ProcurementPaymentFormContract.structured(schema(FieldVisibility.READ_ONLY))).isTrue();
        assertThat(ProcurementPaymentFormContract.structured(null)).isFalse();
        fails("PROCUREMENT_FORM_REQUIRED", () -> ProcurementPaymentFormContract.requireSchema(null));
        var fields = new ArrayList<>(schema(FieldVisibility.READ_ONLY).fields());
        fields.set(0, new FormSchema.Field(ProcurementPaymentFormContract.DETAILS, "采购", FormSchema.FieldType.TEXT, true, null, null, null, null, null));
        fails("PROCUREMENT_FORM_REQUIRED", () -> ProcurementPaymentFormContract.requireSchema(new FormSchema(2, fields)));
        assertThat(ProcurementPaymentFormContract.draftPayload()).doesNotContainKey("amount");
        assertThat(ProcurementPaymentFormContract.submittedPayload(evidence().preview()).get("amount")).isEqualTo("70.00");
    }

    @Test void everyEndingPathNeedsReadableHumanReview() {
        for (var gateway : List.of(NodeType.EXCLUSIVE_GATEWAY, NodeType.PARALLEL_GATEWAY)) {
            var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("split", "分支", gateway, Map.of()),
                    new Node("review", "审核", NodeType.USER_TASK, Map.of()), new Node("end", "结束", NodeType.END, Map.of())),
                    List.of(edge("a", "start", "split"), edge("b", "split", "review"), edge("c", "review", "end"), edge("d", "split", "end")));
            fails("PROCUREMENT_REVIEW_REQUIRED", () -> ProcurementPaymentFormContract.requireReview(graph, schema(FieldVisibility.READ_ONLY)));
        }
        assertThatCode(() -> ProcurementPaymentFormContract.requireReview(graph(), schema(FieldVisibility.READ_ONLY))).doesNotThrowAnyException();
        for (var visibility : List.of(FieldVisibility.HIDDEN, FieldVisibility.MASKED)) {
            fails("PROCUREMENT_REVIEW_FIELDS_REQUIRED", () -> ProcurementPaymentFormContract.requireReview(graph(), schema(visibility)));
            assertThat(ProcurementPaymentFormContract.detailsReadable(schema(FieldVisibility.READ_ONLY), schema(visibility))).isFalse();
        }
    }

    @Test void lateSuccessfulFactsCannotOverwriteExpiredOrFinishedChecks() {
        var queued = queue(input(), NOW); var running = queued.start(NOW, NOW.plusSeconds(60));
        assertThat(running.finish(Result.ready(evidence()), NOW.plusSeconds(2)).status()).isEqualTo(Status.READY);
        var timeout = running.finish(Result.ready(evidence()), NOW.plusSeconds(60));
        assertThat(timeout.status()).isEqualTo(Status.UNAVAILABLE); assertThat(timeout.result().code()).isEqualTo("TIMEOUT");
        fails("PROCUREMENT_CHECK_STATE_CONFLICT", () -> timeout.finish(Result.ready(evidence()), NOW.plusSeconds(61)));
        fails("PROCUREMENT_CHECK_STATE_CONFLICT", () -> running.start(NOW, NOW.plusSeconds(60)));
    }

    @Test void readyFactsCannotBeReboundToDifferentContentRoundOrDestination() {
        var input = input();
        for (var changed : List.of(
                new Input(input.id(), input.tenantId(), input.requestId(), input.applicationId(), input.employeeId(), 1, 1, 2, 1, input.initiator(), input.targetDigest(), input.content()),
                new Input(input.id(), input.tenantId(), input.requestId(), input.applicationId(), input.employeeId(), 1, 2, 1, 1, input.initiator(), input.targetDigest(), input.content()),
                new Input(input.id(), input.tenantId(), input.requestId(), input.applicationId(), input.employeeId(), 1, 1, 1, 1, input.initiator(), "b".repeat(64), input.content()),
                new Input(input.id(), input.tenantId(), input.requestId(), input.applicationId(), input.employeeId(), 1, 1, 1, 1, input.initiator(), input.targetDigest(), content("60")))) {
            fails("INVALID_PROCUREMENT_CHECK", () -> queue(changed, NOW).start(NOW, NOW.plusSeconds(60)).finish(Result.ready(evidence()), NOW.plusSeconds(2)));
        }
    }

    @Test void evidenceCannotOutliveOriginalCatalogOrPayableObservationWindow() {
        var evidence = evidence();
        fails("INVALID_PROCUREMENT_CHECK", () -> new Evidence(evidence.catalog(), evidence.preview(), NOW.plusSeconds(301)));
        fails("INVALID_PROCUREMENT_CHECK", () -> new Evidence(evidence.catalog(), evidence.preview(), evidence.preview().submittedAt()));
        fails("INVALID_PROCUREMENT_CHECK", () -> queue(input(), NOW).start(NOW, NOW.plusSeconds(500)).finish(Result.ready(evidence), evidence.validUntil()));
        fails("INVALID_PROCUREMENT_CHECK", () -> new Result(Status.READY, null, "FAKE_APPROVAL"));
        fails("INVALID_PROCUREMENT_CHECK", () -> new Result(Status.UNAVAILABLE, evidence, null));
    }

    private static ProcurementPaymentContent content(String amount) { return new ProcurementPaymentContent(ENTITY, "采购付款", "应付结算", "supplier-1", "payable-1", money(amount)); }
    private static Input input() {
        var initiator = new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, ENTITY, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位");
        return new Input(UUID.randomUUID(), "demo", UUID.randomUUID(), UUID.randomUUID(), "alice", 1, 1, 1, 1, initiator, "a".repeat(64), content("70"));
    }
    private static Evidence evidence() {
        var catalog = new FinanceCatalog("alice", "catalog-v1", NOW.plusSeconds(600), List.of(new FinanceCatalog.LegalEntity(ENTITY, "法人", "CNY", false, "entity-v1", "Asia/Shanghai")), List.of(), List.of(), List.of(), List.of());
        var request = ProcurementPaymentRequest.draft(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice", content("70"));
        request.freeze(1, 1, catalog, "a".repeat(64), payable(List.of(line(1, 1, "100", "10")), "100", "30"), input().initiator(), NOW.plusSeconds(1));
        return new Evidence(catalog, request.currentRound(), NOW.plusSeconds(300));
    }
    private static FormSchema schema(FieldVisibility visibility) {
        return new FormSchema(2, List.of(new FormSchema.Field(ProcurementPaymentFormContract.DETAILS, "采购", FormSchema.FieldType.TEXT, true, null, null, null, null, null, null, null, true, Map.of("review", visibility)),
                new FormSchema.Field("amount", "金额", FormSchema.FieldType.NUMBER, true, null, null, null, null, null), new FormSchema.Field("currency", "币种", FormSchema.FieldType.TEXT, true, null, null, null, null, null)));
    }
    private static Graph graph() { return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("review", "审核", NodeType.USER_TASK, Map.of()), new Node("end", "结束", NodeType.END, Map.of())), List.of(edge("a", "start", "review"), edge("b", "review", "end"))); }
    private static Edge edge(String id, String source, String target) { return new Edge(id, source, target, "", false); }
    private static void fails(String code, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code)); }
}
