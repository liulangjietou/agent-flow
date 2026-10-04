package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionModels.*;
import io.agentflow.definition.DefinitionValidator;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 服务端项目布尔值必须同时证明有项目必经、无项目不可达，其他金额条件不能削弱责任。
 * @author owlzhangfq@gmail.com
 */
class ExpenseProjectApprovalPolicyTest {
    @Test void explicitTrueAndNegationRoutesRequireAllOwnersAndSkipTheEmptySet() {
        for (var condition : List.of("hasProjectAllocation == true", "!(hasProjectAllocation == false)")) {
            var graph = graph(condition);
            assertThat(new DefinitionValidator().validate(graph, schema(FieldVisibility.READ_ONLY))).isEmpty();
            assertThat(ExpenseProjectApprovalPolicy.require(graph, schema(FieldVisibility.READ_ONLY), true)).isEqualTo("projects");
            assertThat(ExpenseProjectApprovalPolicy.require(graph, schema(FieldVisibility.READ_ONLY), false)).isEqualTo("projects");
        }
    }

    @Test void unknownConditionsCannotBypassRealProjectsOrActivateEmptyProjectReview() {
        for (var condition : List.of("hasProjectAllocation == false", "amount > 100", "hasProjectAllocation == true && amount > 100",
                "hasProjectAllocation == true || amount > 100")) fails(graph(condition), schema(FieldVisibility.READ_ONLY));
        var graph = graph("hasProjectAllocation == true");
        var direct = new Graph(graph.nodes().stream().filter(node -> !node.id().equals("gate")).toList(), List.of(
                new Edge("a", "start", "projects", ""), new Edge("b", "projects", "finance", ""), new Edge("c", "finance", "end", "")), 2);
        fails(direct, schema(FieldVisibility.READ_ONLY));
        var parallel = new Graph(graph.nodes().stream().map(node -> node.id().equals("gate") ? new Node(node.id(), node.name(), NodeType.PARALLEL_GATEWAY, Map.of()) : node).toList(), graph.edges(), 2);
        fails(parallel, schema(FieldVisibility.READ_ONLY));
    }

    @Test void ruleStageModeAndSelfApprovalPolicyMustDescribeOneFixedProjectNode() {
        var graph = graph("hasProjectAllocation == true");
        for (var properties : List.of(Map.of("expenseStage", "PROJECT_REVIEW", "assigneeRule", "user:manager"),
                Map.of("expenseStage", "PROJECT_REVIEW", "assigneeRule", "expense:projectOwners", "approvalMode", "ANY"),
                Map.of("expenseStage", "BUSINESS", "assigneeRule", "expense:projectOwners", "approvalMode", "ALL"))) {
            fails(new Graph(graph.nodes().stream().map(node -> node.id().equals("projects") ? new Node(node.id(), node.name(), node.type(), properties) : node).toList(), graph.edges(), 2), schema(FieldVisibility.READ_ONLY));
        }
        fails(new Graph(graph.nodes().stream().map(node -> node.type() == NodeType.START ? new Node(node.id(), node.name(), node.type(), Map.of()) : node).toList(), graph.edges(), 2), schema(FieldVisibility.READ_ONLY));
        var two = new ArrayList<>(graph.nodes()); two.add(new Node("extra", "另一个项目节点", NodeType.USER_TASK, graph.nodes().get(2).properties()));
        fails(new Graph(two, graph.edges(), 2), schema(FieldVisibility.READ_ONLY));
        for (var visibility : List.of(FieldVisibility.HIDDEN, FieldVisibility.MASKED)) fails(graph, schema(visibility));
    }

    @Test void financialReviewBeforeProjectApprovalCannotBeAuthorizedByAnEventualProjectNode() {
        var graph = graph("hasProjectAllocation == true");
        var later = new Graph(graph.nodes(), List.of(new Edge("a", "start", "finance", ""), new Edge("b", "finance", "gate", ""),
                new Edge("c", "gate", "projects", "hasProjectAllocation == true"), new Edge("d", "gate", "end", "", true),
                new Edge("e", "projects", "end", "")), 2);
        fails(later, schema(FieldVisibility.READ_ONLY));
    }

    @Test void oldNoProjectDefinitionsKeepTheirOriginalSchemaAndPayload() {
        var old = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "end", "")));
        var schema = new FormSchema(2, schema(FieldVisibility.READ_ONLY).fields().stream().filter(field -> !field.key().equals("hasProjectAllocation")).toList());
        assertThat(ExpenseProjectApprovalPolicy.require(old, schema, false)).isNull();
        assertThatCode(() -> ExpenseProjectApprovalPolicy.validate(old, schema)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ExpenseProjectApprovalPolicy.require(old, schema, true)).isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).code()).isEqualTo("EXPENSE_PROJECT_APPROVAL_REQUIRED");
    }

    private Graph graph(String condition) {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of(ExpenseSelfApprovalPolicy.PROPERTY, ExpenseSelfApprovalPolicy.ESCALATE_SUPERVISOR)),
                new Node("gate", "项目条件", NodeType.EXCLUSIVE_GATEWAY, Map.of()),
                new Node("projects", "项目负责人", NodeType.USER_TASK, Map.of("expenseStage", "PROJECT_REVIEW", "assigneeRule", "expense:projectOwners", "approvalMode", "ALL")),
                new Node("finance", "财务复核", NodeType.USER_TASK, Map.of("expenseStage", "FINANCE_REVIEW", "assigneeRule", "user:finance")),
                new Node("end", "结束", NodeType.END, Map.of())), List.of(new Edge("a", "start", "gate", ""), new Edge("b", "gate", "projects", condition),
                new Edge("c", "gate", "finance", "", true), new Edge("d", "projects", "finance", ""), new Edge("e", "finance", "end", "")), 2);
    }
    private FormSchema schema(FieldVisibility access) {
        return new FormSchema(2, List.of(new FormSchema.Field("expenseDetails", "明细", FormSchema.FieldType.TEXT, true, null,
                null, null, null, null, null, null, true, Map.of("projects", access, "finance", FieldVisibility.READ_ONLY)),
                field("amount", FormSchema.FieldType.NUMBER), field("currency", FormSchema.FieldType.TEXT), field("overPolicy", FormSchema.FieldType.BOOLEAN),
                field("hasProjectAllocation", FormSchema.FieldType.BOOLEAN)));
    }
    private FormSchema.Field field(String key, FormSchema.FieldType type) { return new FormSchema.Field(key, key, type, true, null, null, null, null, null); }
    private void fails(Graph graph, FormSchema schema) {
        assertThatThrownBy(() -> ExpenseProjectApprovalPolicy.validate(graph, schema)).isInstanceOf(DomainException.class);
    }
}
