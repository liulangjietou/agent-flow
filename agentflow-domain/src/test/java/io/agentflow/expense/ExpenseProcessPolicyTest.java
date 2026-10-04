package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionModels.*;
import io.agentflow.definition.DefinitionValidator;
import io.agentflow.form.FormSchema;
import io.agentflow.form.FieldVisibility;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 财务和签收必须是明确的必经职责，名称、旧签收和脱敏访问不能替代业务事实。
 * @author owlzhangfq@gmail.com
 */
class ExpenseProcessPolicyTest {
    @Test
    void taskNameCannotGrantFinanceRoleAndAllCompletionPathsMustCrossFinance() {
        var unmarked = chain(task("finance", null));
        assertThatThrownBy(() -> ExpenseProcessPolicy.requireSubmittable(unmarked, schema(Map.of("finance", FieldVisibility.READ_ONLY)), false))
                .isInstanceOf(DomainException.class).hasMessageContaining("financial review");
        for (var type : List.of(NodeType.EXCLUSIVE_GATEWAY, NodeType.PARALLEL_GATEWAY)) {
            var graph = new Graph(List.of(node("start", NodeType.START), node("branch", type), task("finance", "FINANCE_REVIEW"), task("bypass", null), node("end", NodeType.END)),
                    List.of(edge("a", "start", "branch"), edge("b", "branch", "finance"), edge("c", "branch", "bypass"), edge("d", "finance", "end"), edge("e", "bypass", "end")));
            assertThatThrownBy(() -> ExpenseProcessPolicy.requireSubmittable(graph, schema(Map.of("finance", FieldVisibility.READ_ONLY)), false))
                    .isInstanceOf(DomainException.class).hasMessageContaining("financial review");
        }
    }

    @Test
    void legalEntityPaperRequirementNeedsReceiptBeforeEveryFinancialTask() {
        var schema = schema(Map.of("receipt", FieldVisibility.READ_ONLY, "finance", FieldVisibility.READ_ONLY));
        var noReceipt = chain(task("finance", "FINANCE_RECHECK"));
        assertThat(ExpenseProcessPolicy.requireSubmittable(noReceipt, schema, false)).containsEntry("finance", ExpenseProcessPolicy.Stage.FINANCE_RECHECK);
        assertThatThrownBy(() -> ExpenseProcessPolicy.requireSubmittable(noReceipt, schema, true)).isInstanceOf(DomainException.class).hasMessageContaining("receipt");
        assertThatThrownBy(() -> ExpenseProcessPolicy.requireSubmittable(chain(task("finance", "FINANCE_REVIEW"), task("receipt", "RECEIPT")), schema, true))
                .isInstanceOf(DomainException.class).hasMessageContaining("receipt");
        var valid = chain(task("business", null), task("receipt", "RECEIPT"), task("finance", "FINANCE_REVIEW"));
        assertThat(ExpenseProcessPolicy.requireSubmittable(valid, schema, true)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "business", ExpenseProcessPolicy.Stage.BUSINESS, "receipt", ExpenseProcessPolicy.Stage.RECEIPT, "finance", ExpenseProcessPolicy.Stage.FINANCE_REVIEW));
    }

    @Test
    void maskedDetailsAndUnknownStageCannotBeUsedForFinancialReview() {
        var graph = chain(task("finance", "FINANCE_REVIEW"));
        for (var access : List.of(FieldVisibility.HIDDEN, FieldVisibility.MASKED)) {
            assertThatThrownBy(() -> ExpenseProcessPolicy.requireSubmittable(graph, schema(Map.of("finance", access)), false))
                    .isInstanceOf(DomainException.class).hasMessageContaining("read expense details");
        }
        var invalid = chain(task("finance", "FINANCE"));
        assertThat(new DefinitionValidator().validate(invalid, schema(Map.of()))).contains("INVALID_EXPENSE_STAGE:finance");
        assertThat(new DefinitionValidator().validate(graph, null)).contains("EXPENSE_STAGE_REQUIRES_EXPENSE_FORM:finance");
        var start = new Node("start", "开始", NodeType.START, Map.of("expenseStage", "RECEIPT"));
        assertThatThrownBy(() -> ExpenseProcessPolicy.stage(start)).isInstanceOf(DomainException.class).hasMessageContaining("user task");
    }

    @Test
    void budgetCheckpointMustBeSingleCommonReadableAndBeforeEveryFinancePath() {
        var schema = schema(Map.of("budget", FieldVisibility.READ_ONLY, "finance", FieldVisibility.READ_ONLY));
        var afterFinance = chain(task("finance", "FINANCE_REVIEW"), task("budget", "BUDGET_REVIEW"));
        assertThat(new DefinitionValidator().validate(afterFinance, schema)).contains("EXPENSE_BUDGET_APPROVAL_REQUIRED");
        var parallel = new Node("budget", "预算审批", NodeType.USER_TASK,
                Map.of("assigneeRule", "user:manager", "expenseStage", "BUDGET_REVIEW", "approvalMode", "ALL"));
        assertThat(new DefinitionValidator().validate(chain(parallel, task("finance", "FINANCE_REVIEW")), schema)).contains("EXPENSE_BUDGET_APPROVAL_REQUIRED");
        var graph = new Graph(List.of(node("start", NodeType.START), node("branch", NodeType.EXCLUSIVE_GATEWAY), task("budget", "BUDGET_REVIEW"),
                task("finance", "FINANCE_REVIEW"), node("end", NodeType.END)), List.of(edge("a", "start", "branch"),
                new Edge("b", "branch", "budget", "amount > 50"), new Edge("c", "branch", "finance", "", true), edge("d", "budget", "finance"), edge("e", "finance", "end")));
        assertThat(new DefinitionValidator().validate(graph, schema)).contains("EXPENSE_BUDGET_APPROVAL_REQUIRED");
    }

    @Test
    void paperReceiptIsAnExplicitOnceOnlyFactForTheCurrentFrozenRound() {
        Instant now = Instant.parse("2026-09-28T16:00:00Z"); var first = ExpenseSubmissionControl.submitted(input(1, true), now);
        assertThat(first.paperReady()).isFalse();
        assertThatThrownBy(() -> first.receive("task", "finance", "finance", "已收到", now)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> first.receive("task", "receipt", "clerk", " ", now)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> first.receive("task", "receipt", "clerk", "已收到", now.minusSeconds(1))).isInstanceOf(DomainException.class);
        var received = first.receive("task", "receipt", "clerk", "已核对收到本轮纸质原件", now.plusSeconds(1));
        assertThat(received.paperReady()).isTrue(); assertThat(received.version()).isEqualTo(2);
        assertThat(received.receipt().receivedBy()).isEqualTo("clerk"); assertThat(received.input()).isEqualTo(first.input());
        assertThatThrownBy(() -> received.receive("task", "receipt", "clerk", "重复确认", now.plusSeconds(2))).isInstanceOf(DomainException.class);
        assertThat(ExpenseSubmissionControl.submitted(input(2, true), now.plusSeconds(3)).paperReady()).isFalse();
        assertThat(ExpenseSubmissionControl.submitted(input(1, false), now).paperReady()).isTrue();
        assertThatThrownBy(() -> first.stage("unknown")).isInstanceOf(DomainException.class);
    }

    private ExpenseSubmissionControl.Input input(int round, boolean paper) {
        return new ExpenseSubmissionControl.Input("tenant", UUID.randomUUID(), UUID.randomUUID(), "alice", round, 2, UUID.randomUUID(), LocalDate.of(2026, 9, 28), paper,
                Map.of("receipt", ExpenseProcessPolicy.Stage.RECEIPT, "finance", ExpenseProcessPolicy.Stage.FINANCE_REVIEW));
    }
    private Graph chain(Node... tasks) {
        var nodes = new ArrayList<Node>(); var edges = new ArrayList<Edge>(); nodes.add(node("start", NodeType.START));
        for (var task : tasks) { edges.add(edge("to_" + task.id(), nodes.get(nodes.size() - 1).id(), task.id())); nodes.add(task); }
        edges.add(edge("finish", nodes.get(nodes.size() - 1).id(), "end")); nodes.add(node("end", NodeType.END)); return new Graph(nodes, edges);
    }
    private Node task(String id, String stage) { return new Node(id, "财务显示名称", NodeType.USER_TASK, stage == null ? Map.of("assigneeRule", "user:manager") : Map.of("assigneeRule", "user:manager", "expenseStage", stage)); }
    private Node node(String id, NodeType type) { return new Node(id, id, type, Map.of()); }
    private Edge edge(String id, String from, String to) { return new Edge(id, from, to, ""); }
    private FormSchema schema(Map<String, FieldVisibility> visibility) {
        return new FormSchema(2, List.of(
                new FormSchema.Field("expenseDetails", "费用", FormSchema.FieldType.TEXT, true, null, null, null, null, null, null, null, true, visibility),
                new FormSchema.Field("amount", "金额", FormSchema.FieldType.NUMBER, true, null, null, null, null, null),
                new FormSchema.Field("currency", "币种", FormSchema.FieldType.TEXT, true, null, null, null, null, null),
                new FormSchema.Field("overPolicy", "超标", FormSchema.FieldType.BOOLEAN, true, null, null, null, null, null)));
    }
}
