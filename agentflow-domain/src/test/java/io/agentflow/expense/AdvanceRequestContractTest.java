package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.EmployeeAccountPort;
import io.agentflow.finance.EmployeeAccountSnapshot;
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
import static io.agentflow.expense.AdvanceRequestCheck.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 覆盖表单实际类型、旁路路径与持久预检的跨轮次和超时边界。
 * @author owlzhangfq@gmail.com
 */
class AdvanceRequestContractTest {
    private static final Instant NOW = Instant.parse("2026-09-28T02:00:00Z");
    private static final UUID ENTITY = UUID.randomUUID();

    @Test void realFormTypesAreAcceptedAndMissingOrNonSensitiveDetailsAreRejected() {
        var schema = schema(FieldVisibility.READ_ONLY);
        assertThatCode(() -> AdvanceRequestFormContract.requireSchema(schema)).doesNotThrowAnyException();
        assertThat(AdvanceRequestFormContract.structured(schema)).isTrue();
        assertThat(AdvanceRequestFormContract.structured(null)).isFalse();
        fails("ADVANCE_REQUEST_FORM_REQUIRED", () -> AdvanceRequestFormContract.requireSchema(null));
        fails("ADVANCE_REQUEST_FORM_REQUIRED", () -> AdvanceRequestFormContract.requireSchema(new FormSchema(2, List.of(new FormSchema.Field("amount", "金额", FormSchema.FieldType.NUMBER, true, null, null, null, null, null)))));
        var fields = new ArrayList<>(schema.fields()); fields.set(0, new FormSchema.Field(AdvanceRequestFormContract.DETAILS, "借款", FormSchema.FieldType.TEXT, true, null, null, null, null, null));
        fails("ADVANCE_REQUEST_FORM_REQUIRED", () -> AdvanceRequestFormContract.requireSchema(new FormSchema(2, fields)));
    }

    @Test void aReviewOnOnlyOneConditionalOrParallelBranchCannotAuthorizeTheWholePlan() {
        for (var gateway : List.of(NodeType.EXCLUSIVE_GATEWAY, NodeType.PARALLEL_GATEWAY)) {
            var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("split", "分支", gateway, Map.of()),
                    new Node("review", "审核", NodeType.USER_TASK, Map.of()), new Node("end", "结束", NodeType.END, Map.of())),
                    List.of(edge("a", "start", "split"), edge("b", "split", "review"), edge("c", "review", "end"), edge("d", "split", "end")));
            fails("ADVANCE_REQUEST_REVIEW_REQUIRED", () -> AdvanceRequestFormContract.requireReview(graph, schema(FieldVisibility.READ_ONLY)));
        }
        assertThatCode(() -> AdvanceRequestFormContract.requireReview(reviewGraph(), schema(FieldVisibility.READ_ONLY))).doesNotThrowAnyException();
    }

    @Test void maskedDetailsNeverSatisfyReviewOrBusinessReadAuthorization() {
        var original = schema(FieldVisibility.READ_ONLY);
        for (var visibility : List.of(FieldVisibility.HIDDEN, FieldVisibility.MASKED)) {
            fails("ADVANCE_REQUEST_REVIEW_FIELDS_REQUIRED", () -> AdvanceRequestFormContract.requireReview(reviewGraph(), schema(visibility)));
            assertThat(AdvanceRequestFormContract.detailsReadable(original, schema(visibility))).isFalse();
        }
        assertThat(AdvanceRequestFormContract.detailsReadable(original, original)).isTrue();
    }

    @Test void lateFactsCannotCompleteAnExpiredLeaseOrOverwriteATerminalCheck() {
        var running = AdvanceRequestCheck.queue(input(), NOW).start(NOW, NOW.plusSeconds(60));
        var finished = running.finish(Result.ready(evidence()), NOW.plusSeconds(60));
        assertThat(finished.status()).isEqualTo(Status.UNAVAILABLE); assertThat(finished.result().code()).isEqualTo("TIMEOUT");
        fails("ADVANCE_REQUEST_CHECK_STATE_CONFLICT", () -> finished.finish(Result.ready(evidence()), NOW.plusSeconds(61)));
    }

    @Test void readyEvidenceCannotBelongToAnotherRoundOrRequestVersion() {
        var input = input(); var running = AdvanceRequestCheck.queue(input, NOW).start(NOW, NOW.plusSeconds(60));
        assertThat(running.finish(Result.ready(evidence()), NOW.plusSeconds(1)).status()).isEqualTo(Status.READY);
        var changed = new Input(input.id(), input.tenantId(), input.requestId(), input.applicationId(), input.employeeId(), input.applicationVersion(),
                2, 2, input.attempt(), input.initiator(), input.targetDigest());
        fails("INVALID_ADVANCE_REQUEST_CHECK", () -> AdvanceRequestCheck.queue(changed, NOW).start(NOW, NOW.plusSeconds(60)).finish(Result.ready(evidence()), NOW.plusSeconds(1)));
        fails("INVALID_ADVANCE_REQUEST_CHECK", () -> new Result(Status.READY, null, "FAKE_APPROVED"));
    }

    @Test void mismatchingAccountOrExpiredEvidenceCannotBePersistedAsReady() {
        var evidence = evidence();
        var other = new EmployeeAccountPort.Account(new EmployeeAccountSnapshot(ENTITY, "alice", "other-reference", "****5678", "b".repeat(64), "v2"), evidence.validUntil());
        fails("INVALID_ADVANCE_REQUEST_CHECK", () -> new Evidence(evidence.catalog(), other, evidence.preview(), evidence.validUntil()));
        fails("INVALID_ADVANCE_REQUEST_CHECK", () -> new Evidence(evidence.catalog(), new EmployeeAccountPort.Account(evidence.account().snapshot(), NOW.plusSeconds(1)), evidence.preview(), evidence.validUntil()));
        var running = AdvanceRequestCheck.queue(input(), NOW).start(NOW, NOW.plusSeconds(500));
        fails("INVALID_ADVANCE_REQUEST_CHECK", () -> running.finish(Result.ready(evidence), evidence.validUntil()));
    }

    private static Input input() {
        var initiator = new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, ENTITY, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位");
        return new Input(UUID.randomUUID(), "demo", UUID.randomUUID(), UUID.randomUUID(), "alice", 1, 1, 1, 1, initiator, "a".repeat(64));
    }
    private static Evidence evidence() {
        var catalog = new FinanceCatalog("alice", "catalog-v1", NOW.plusSeconds(300), List.of(new FinanceCatalog.LegalEntity(ENTITY, "法人", "CNY", false, "entity-v1", "Asia/Shanghai")), List.of(new FinanceCatalog.Category("TRAVEL", "差旅", List.of(ExpenseLine.Unit.ITEM))), List.of(new FinanceCatalog.CostCenter(ENTITY, "IT", "研发")), List.of(), List.of(new FinanceCatalog.City("SH", "上海")));
        var content = new AdvanceRequestContent(ENTITY, "出差借款", "客户现场交流", new Money(new BigDecimal("100"), "CNY"), LocalDate.of(2026, 10, 1));
        var request = AdvanceRequest.draft(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice", content);
        var account = new EmployeeAccountPort.Account(new EmployeeAccountSnapshot(ENTITY, "alice", "private-reference", "****1234", "a".repeat(64), "v1"), NOW.plusSeconds(300));
        request.freeze(1, 1, catalog, account, input().initiator(), NOW);
        return new Evidence(catalog, account, request.currentRound(), NOW.plusSeconds(300));
    }
    private static FormSchema schema(FieldVisibility visibility) {
        return new FormSchema(2, List.of(new FormSchema.Field(AdvanceRequestFormContract.DETAILS, "借款", FormSchema.FieldType.TEXT, true, null, null, null, null, null, null, null, true, Map.of("review", visibility)),
                new FormSchema.Field("amount", "金额", FormSchema.FieldType.NUMBER, true, null, null, null, null, null), new FormSchema.Field("currency", "币种", FormSchema.FieldType.TEXT, true, null, null, null, null, null)));
    }
    private static Graph reviewGraph() { return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("review", "审核", NodeType.USER_TASK, Map.of()), new Node("end", "结束", NodeType.END, Map.of())), List.of(edge("a", "start", "review"), edge("b", "review", "end"))); }
    private static Edge edge(String id, String source, String target) { return new Edge(id, source, target, "", false); }
    private static void fails(String code, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code)); }
}
