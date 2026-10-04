package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.definition.ConditionParser;
import io.agentflow.definition.DefinitionModels.*;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 项目责任使用固定 ALL，并证明有项目必经、无项目不可达，不能借其他条件改变原责任。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseProjectApprovalPolicy {
    public static final String ASSIGNEE_RULE = "expense:projectOwners";
    public static final String MEMBERS_FIXED = "EXPENSE_PROJECT_MEMBERS_FIXED";
    private ExpenseProjectApprovalPolicy() { }

    /** 只有该专用规则可读取已提交的项目来源，不接受任意 expense 前缀。 */
    public static boolean isRule(String rule) { return ASSIGNEE_RULE.equals(rule); }

    /** 发布时显式字段、节点或规则任一出现就要求完整契约。 */
    public static void validate(Graph graph, FormSchema schema) { require(graph, schema, false); }

    /** 新含项目提交要求安全节点；旧无项目定义保持原协议，已配置节点始终检查双向路径。 */
    public static String require(Graph graph, FormSchema schema, boolean hasProjects) {
        var reviews = graph.nodes().stream().filter(node -> ExpenseProcessPolicy.Stage.PROJECT_REVIEW.name().equals(node.properties().get(ExpenseProcessPolicy.PROPERTY))
                || isRule(node.properties().get("assigneeRule"))).toList();
        if (reviews.isEmpty() && !hasProjects && !ExpenseFormContract.hasProjectControl(schema)) return null;
        if (reviews.size() != 1 || !ExpenseFormContract.hasProjectControl(schema) || !ExpenseSelfApprovalPolicy.enabled(graph)) throw invalid();
        ExpenseFormContract.requireSchema(schema); var review = reviews.get(0);
        if (review.type() != NodeType.USER_TASK || ExpenseProcessPolicy.stage(review) != ExpenseProcessPolicy.Stage.PROJECT_REVIEW
                || !isRule(review.properties().get("assigneeRule")) || !ApprovalMode.ALL.name().equals(review.properties().get("approvalMode"))) throw invalid();
        var details = schema.fields().stream().filter(field -> field.key().equals(ExpenseFormContract.DETAILS)).findFirst().orElseThrow();
        if (details.visibility(Set.of(review.id())) != FieldVisibility.READ_ONLY) throw invalid();
        var nodes = graph.nodes().stream().collect(Collectors.toMap(Node::id, node -> node, (first, second) -> first));
        var outgoing = new HashMap<String, List<Edge>>();
        for (var edge : graph.edges()) outgoing.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge);
        var starts = graph.nodes().stream().filter(node -> node.type() == NodeType.START).map(Node::id).toList();
        var finance = graph.nodes().stream().filter(node -> ExpenseProcessPolicy.stage(node).finance()).map(Node::id).toList();
        if (starts.size() != 1 || finance.isEmpty()) throw invalid();
        for (boolean allocated : List.of(true, false)) {
            var pending = new ArrayDeque<>(starts); var visited = new HashSet<String>(); boolean reached = false;
            while (!pending.isEmpty()) {
                String id = pending.removeFirst(); if (!visited.add(id)) continue;
                if (id.equals(review.id())) { if (!allocated) throw invalid(); reached = true; continue; }
                var node = nodes.get(id);
                if (node == null || allocated && (node.type() == NodeType.END || finance.contains(id))) throw invalid();
                var edges = possible(node, outgoing.getOrDefault(id, List.of()), graph.conditionLanguageVersion(), allocated);
                if (allocated && edges.isEmpty()) throw invalid();
                for (var edge : edges) pending.add(edge.target());
            }
            if (allocated && !reached) throw invalid();
        }
        // 任何财务节点之后都不得再安排项目审核，即使该分支当前没有项目。
        var pending = new ArrayDeque<>(finance); var visited = new HashSet<String>();
        while (!pending.isEmpty()) {
            String id = pending.removeFirst(); if (!visited.add(id)) continue;
            if (id.equals(review.id())) throw invalid();
            for (var edge : outgoing.getOrDefault(id, List.of())) pending.add(edge.target());
        }
        return review.id();
    }

    private static List<Edge> possible(Node node, List<Edge> edges, int language, boolean allocated) {
        if (node.type() != NodeType.EXCLUSIVE_GATEWAY) return edges;
        var candidates = new ArrayList<Edge>(); Edge fallback = null; boolean certain = false;
        for (var edge : edges) {
            if (edge.defaultBranch()) { fallback = edge; continue; }
            Boolean value = edge.condition().isBlank() ? Boolean.TRUE : known(new ConditionParser().parse(edge.condition(), language), allocated);
            if (!Boolean.FALSE.equals(value)) candidates.add(edge);
            if (Boolean.TRUE.equals(value)) { certain = true; break; }
        }
        if (!certain && fallback != null) candidates.add(fallback);
        return candidates;
    }
    private static Boolean known(ConditionAst condition, boolean allocated) {
        if (condition instanceof Negation negation) { var value = known(negation.term(), allocated); return value == null ? null : !value; }
        if (condition instanceof Logical logical) {
            boolean unknown = false; boolean and = logical.kind() == Kind.AND;
            for (var term : logical.terms()) {
                var value = known(term, allocated); if (value == null) unknown = true;
                else if (and && !value || !and && value) return !and;
            }
            return unknown ? null : and;
        }
        String field = condition instanceof Comparison comparison ? comparison.field() : ((Membership) condition).field();
        if (!ExpenseFormContract.HAS_PROJECT_ALLOCATION.equals(field)) return null;
        return condition.evaluate(new EvaluationContext(Map.of(field, allocated), Map.of(field, FormSchema.FieldType.BOOLEAN.name())));
    }
    private static DomainException invalid() {
        return new DomainException("EXPENSE_PROJECT_APPROVAL_REQUIRED", "Project allocations require one readable fixed ALL review on every financial path; use a compatible published expense definition");
    }
}
