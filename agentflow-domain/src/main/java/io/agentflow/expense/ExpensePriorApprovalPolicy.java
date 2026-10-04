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
 * 超容差必须先经独立额度审核；静态证明只固定服务端布尔值，其余条件保守保留可能路径。
 * @author owlzhangfq@gmail.com
 */
public final class ExpensePriorApprovalPolicy {
    private ExpensePriorApprovalPolicy() { }

    /** 新定义显式配置字段或节点时校验完整约束，旧四字段图继续原发布规则。 */
    public static void validate(Graph graph, FormSchema schema) {
        if (ExpenseFormContract.hasPriorControl(schema) || graph.nodes().stream()
                .anyMatch(node -> "PRIOR_REQUEST_REVIEW".equals(node.properties().get(ExpenseProcessPolicy.PROPERTY)))) require(graph, schema, true);
    }

    /** 只有真实累计超容差才强制新审批契约，缺字段或缺必经节点在提交事务内阻断。 */
    public static void require(Graph graph, FormSchema schema, boolean overTolerance) {
        if (!overTolerance) return;
        if (!ExpenseFormContract.hasPriorControl(schema)) throw invalid();
        ExpenseFormContract.requireSchema(schema);
        var nodes = graph.nodes().stream().collect(Collectors.toMap(Node::id, node -> node, (first, second) -> first));
        var reviews = graph.nodes().stream().filter(node -> ExpenseProcessPolicy.stage(node) == ExpenseProcessPolicy.Stage.PRIOR_REQUEST_REVIEW)
                .map(Node::id).collect(Collectors.toSet());
        if (reviews.isEmpty()) throw invalid();
        var details = schema.fields().stream().filter(field -> field.key().equals(ExpenseFormContract.DETAILS)).findFirst().orElseThrow();
        if (reviews.stream().anyMatch(id -> details.visibility(Set.of(id)) != FieldVisibility.READ_ONLY)) throw invalid();
        var outgoing = new HashMap<String, List<Edge>>();
        for (var edge : graph.edges()) outgoing.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge);
        var pending = new ArrayDeque<String>(); graph.nodes().stream().filter(node -> node.type() == NodeType.START).forEach(node -> pending.add(node.id()));
        if (pending.size() != 1) throw invalid();
        var visited = new HashSet<String>();
        while (!pending.isEmpty()) {
            String id = pending.removeFirst(); if (reviews.contains(id) || !visited.add(id)) continue;
            var node = nodes.get(id);
            if (node == null || node.type() == NodeType.END || ExpenseProcessPolicy.stage(node).finance()) throw invalid();
            for (var edge : possible(node, outgoing.getOrDefault(id, List.of()), graph.conditionLanguageVersion())) pending.add(edge.target());
        }
        // 审核节点不能配置在任何财务节点之后，即使某个金额分支暂时不会走到。
        visited.clear(); graph.nodes().stream().filter(node -> ExpenseProcessPolicy.stage(node).finance()).forEach(node -> pending.add(node.id()));
        while (!pending.isEmpty()) {
            String id = pending.removeFirst(); if (!visited.add(id)) continue;
            if (reviews.contains(id)) throw invalid();
            for (var edge : outgoing.getOrDefault(id, List.of())) pending.add(edge.target());
        }
    }

    private static List<Edge> possible(Node node, List<Edge> edges, int language) {
        if (node.type() != NodeType.EXCLUSIVE_GATEWAY) return edges;
        var candidates = new ArrayList<Edge>(); Edge fallback = null; boolean certain = false;
        for (var edge : edges) {
            if (edge.defaultBranch()) { fallback = edge; continue; }
            Boolean condition = edge.condition().isBlank() ? Boolean.TRUE : known(new ConditionParser().parse(edge.condition(), language));
            if (!Boolean.FALSE.equals(condition)) candidates.add(edge);
            if (Boolean.TRUE.equals(condition)) { certain = true; break; }
        }
        if (!certain && fallback != null) candidates.add(fallback);
        return candidates;
    }

    private static Boolean known(ConditionAst condition) {
        if (condition instanceof Negation negation) { var value = known(negation.term()); return value == null ? null : !value; }
        if (condition instanceof Logical logical) {
            boolean unknown = false; boolean and = logical.kind() == Kind.AND;
            for (var term : logical.terms()) {
                var value = known(term); if (value == null) unknown = true;
                else if (and && !value || !and && value) return !and;
            }
            return unknown ? null : and;
        }
        String field = condition instanceof Comparison comparison ? comparison.field() : ((Membership) condition).field();
        if (!ExpenseFormContract.PRIOR_OVER_TOLERANCE.equals(field)) return null;
        return condition.evaluate(new EvaluationContext(Map.of(field, true), Map.of(field, FormSchema.FieldType.BOOLEAN.name())));
    }

    private static DomainException invalid() {
        return new DomainException("EXPENSE_PRIOR_APPROVAL_REQUIRED", "Prior tolerance exceptions require an independent readable review before every financial path");
    }
}
