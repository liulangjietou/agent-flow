package io.agentflow.expense;

import io.agentflow.definition.DefinitionModels.*;
import io.agentflow.definition.DefinitionValidator;
import io.agentflow.form.FormSchema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 已走分支、财务边界和并行前驱共同决定是否存在唯一相邻业务责任。
 * @author owlzhangfq@gmail.com
 */
class ExpenseDuplicateApprovalPolicyTest {
    @Test
    void oldGraphsRemainManualAndNewPolicyRequiresExpenseDuties() {
        var graph = branch();
        assertThat(ExpenseDuplicateApprovalPolicy.enabled(graph)).isFalse();
        var enabled = new Graph(graph.nodes().stream().map(node -> node.id().equals("start")
                ? new Node(node.id(), node.name(), node.type(), Map.of(ExpenseDuplicateApprovalPolicy.PROPERTY,
                    ExpenseDuplicateApprovalPolicy.AUTO_PASS_ADJACENT)) : node).toList(), graph.edges());
        assertThat(new DefinitionValidator().validate(enabled, null)).contains("EXPENSE_DUPLICATE_REQUIRES_EXPENSE_FORM");
        assertThat(new DefinitionValidator().validate(enabled, schema())).contains("EXPENSE_DUPLICATE_REQUIRES_FROZEN_DUTIES");
    }

    @Test
    void onlyActuallySelectedAmountBranchDeterminesThePredecessor() {
        assertThat(previous(branch(), Set.of("a", "b", "skip"))).isEqualTo("first");
        assertThat(previous(branch(), Set.of("a", "b", "extra", "next"))).isEqualTo("second");
    }

    @Test
    void unsupportedValuesAndTaskLevelPolicyCannotBePublished() {
        for (String value : List.of("", "true", "AUTO_PASS_ADJACENT ", "auto_pass_adjacent")) {
            var graph = branch();
            var invalid = new Graph(graph.nodes().stream().map(node -> node.id().equals("start")
                    ? new Node(node.id(), node.name(), node.type(), Map.of(ExpenseDuplicateApprovalPolicy.PROPERTY, value)) : node).toList(), graph.edges());
            assertThat(new DefinitionValidator().validate(invalid, schema())).contains("EXPENSE_DUPLICATE_POLICY_INVALID");
        }
        var graph = branch();
        var invalid = new Graph(graph.nodes().stream().map(node -> node.id().equals("target")
                ? new Node(node.id(), node.name(), node.type(), Map.of("assigneeRule", "user:manager",
                    ExpenseDuplicateApprovalPolicy.PROPERTY, ExpenseDuplicateApprovalPolicy.AUTO_PASS_ADJACENT)) : node).toList(), graph.edges());
        assertThat(new DefinitionValidator().validate(invalid, schema())).contains("EXPENSE_DUPLICATE_POLICY_INVALID");
    }

    @Test
    void financialNodesCannotBeAutomaticTargetsOrSupplyDuplicateBusinessApproval() {
        var graph = branch();
        var financial = new Graph(graph.nodes().stream().map(node -> node.id().equals("second")
                ? new Node(node.id(), node.name(), node.type(), Map.of("expenseStage", "FINANCE_REVIEW")) : node).toList(), graph.edges());
        assertThat(previous(financial, Set.of("a", "b", "extra", "next"))).isNull();
        assertThat(ExpenseDuplicateApprovalPolicy.previousBusinessNode(financial, "second", Set.of("a", "b", "extra"))).isNull();
    }

    @Test
    void parallelDifferentHumanPredecessorsRequireManualDecision() {
        var graph = branch();
        assertThat(previous(graph, Set.of("a", "b", "skip", "extra", "next"))).isNull();
    }

    @Test
    void waitResumeKeepsTheOriginalPredecessorButSubprocessIsItsOwnApprovalBoundary() {
        var graph = branch();
        for (var type : List.of(NodeType.TIMER_WAIT, NodeType.EVENT_WAIT, NodeType.SERVICE_TASK, NodeType.COPY)) {
            var waiting = new Graph(graph.nodes().stream().map(node -> node.id().equals("gate")
                    ? new Node(node.id(), node.name(), type, Map.of()) : node).toList(), graph.edges());
            assertThat(previous(waiting, Set.of("a", "b", "skip"))).isEqualTo("first");
        }
        var subprocess = new Graph(graph.nodes().stream().map(node -> node.id().equals("gate")
                ? new Node(node.id(), node.name(), NodeType.SUB_PROCESS, Map.of()) : node).toList(), graph.edges());
        assertThat(previous(subprocess, Set.of("a", "b", "skip"))).isNull();
    }

    @Test
    void anUntakenPathDoesNotEstablishPriorApproval() {
        assertThat(previous(branch(), Set.of("a", "b"))).isNull();
        assertThat(ExpenseDuplicateApprovalPolicy.previousBusinessNode(branch(), "first", Set.of("a"))).isNull();
    }

    private String previous(Graph graph, Set<String> edges) {
        return ExpenseDuplicateApprovalPolicy.previousBusinessNode(graph, "target", edges);
    }

    private Graph branch() {
        return new Graph(List.of(node("start", NodeType.START), node("first", NodeType.USER_TASK), node("gate", NodeType.EXCLUSIVE_GATEWAY),
                node("second", NodeType.USER_TASK), node("target", NodeType.USER_TASK), node("end", NodeType.END)),
                List.of(new Edge("a", "start", "first", ""), new Edge("b", "first", "gate", ""),
                        new Edge("skip", "gate", "target", ""), new Edge("extra", "gate", "second", "amount > 50"),
                        new Edge("next", "second", "target", ""), new Edge("last", "target", "end", "")));
    }

    private Node node(String id, NodeType type) {
        return new Node(id, id, type, type == NodeType.USER_TASK ? Map.of("assigneeRule", "user:manager") : Map.of());
    }

    private FormSchema schema() {
        return new FormSchema(2, List.of(
                new FormSchema.Field("expenseDetails", "费用明细", FormSchema.FieldType.TEXT, true, null, null, null, null, null),
                new FormSchema.Field("amount", "本币金额", FormSchema.FieldType.NUMBER, true, null, null, null, null, null),
                new FormSchema.Field("currency", "币种", FormSchema.FieldType.TEXT, true, null, null, null, null, null),
                new FormSchema.Field("overPolicy", "超标", FormSchema.FieldType.BOOLEAN, true, null, null, null, null, null)));
    }
}
