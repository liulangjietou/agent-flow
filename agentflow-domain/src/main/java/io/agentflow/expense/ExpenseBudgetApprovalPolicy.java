package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionModels.*;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 柔性预算依赖一个共同必经的单人节点，异步预算结果不写成可编辑的路由标志。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseBudgetApprovalPolicy {
    public static final String AUTOMATIC_ACTION = "AUTO_PASSED_BUDGET";
    public static final String SYSTEM_ACTOR = "system:budget";
    private ExpenseBudgetApprovalPolicy() { }

    /** 发布只检查显式预算节点，缺省旧定义保持原规则。 */
    public static void validate(Graph graph, FormSchema schema) {
        if (graph.nodes().stream().anyMatch(node -> ExpenseProcessPolicy.Stage.BUDGET_REVIEW.name().equals(node.properties().get(ExpenseProcessPolicy.PROPERTY)))) {
            require(graph, schema, true);
        }
    }

    /** 柔性预检必须有安全节点；返回原发布节点标识供本轮冻结，刚性旧定义可以没有该节点。 */
    public static String require(Graph graph, FormSchema schema, boolean flexible) {
        var reviews = graph.nodes().stream().filter(node -> ExpenseProcessPolicy.Stage.BUDGET_REVIEW.name().equals(node.properties().get(ExpenseProcessPolicy.PROPERTY))).toList();
        if (reviews.isEmpty() && !flexible) return null;
        if (reviews.size()!=1) throw invalid();
        ExpenseFormContract.requireSchema(schema); var review = reviews.get(0);
        if (review.type()!=NodeType.USER_TASK || !ApprovalMode.SINGLE.name().equals(review.properties().getOrDefault("approvalMode", ApprovalMode.SINGLE.name()))) throw invalid();
        var details = schema.fields().stream().filter(field -> field.key().equals(ExpenseFormContract.DETAILS)).findFirst().orElseThrow();
        if (details.visibility(Set.of(review.id()))!=FieldVisibility.READ_ONLY) throw invalid();
        var nodes = graph.nodes().stream().collect(Collectors.toMap(Node::id, node -> node, (first, second) -> first));
        var outgoing = new HashMap<String, java.util.ArrayList<String>>();
        for (var edge : graph.edges()) outgoing.computeIfAbsent(edge.source(), ignored -> new java.util.ArrayList<>()).add(edge.target());
        var finance = graph.nodes().stream().filter(node -> ExpenseProcessPolicy.stage(node).finance()).map(Node::id).toList();
        var starts = graph.nodes().stream().filter(node -> node.type()==NodeType.START).map(Node::id).toList();
        if (starts.size()!=1 || finance.isEmpty()) throw invalid();
        var pending = new ArrayDeque<>(starts); var visited = new HashSet<String>();
        while (!pending.isEmpty()) {
            String id = pending.removeFirst(); if (id.equals(review.id()) || !visited.add(id)) continue;
            var node = nodes.get(id);
            if (node==null || node.type()==NodeType.END || finance.contains(id)) throw invalid();
            pending.addAll(outgoing.getOrDefault(id, new java.util.ArrayList<>()));
        }
        pending.addAll(finance); visited.clear();
        while (!pending.isEmpty()) {
            String id = pending.removeFirst(); if (!visited.add(id)) continue;
            if (id.equals(review.id())) throw invalid();
            pending.addAll(outgoing.getOrDefault(id, new java.util.ArrayList<>()));
        }
        return review.id();
    }

    private static DomainException invalid() {
        return new DomainException("EXPENSE_BUDGET_APPROVAL_REQUIRED", "Budget exceptions require one readable single-decision checkpoint before every financial path");
    }
}
