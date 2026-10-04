package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionModels.Graph;
import io.agentflow.definition.DefinitionModels.Node;
import io.agentflow.definition.DefinitionModels.NodeType;
import io.agentflow.form.FormSchema;
import io.agentflow.form.FieldVisibility;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 费用流程的明确节点职责与必经审核边界，不按显示名称猜测财务权限。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseProcessPolicy {
    public static final String PROPERTY = "expenseStage";
    private ExpenseProcessPolicy() { }

    /** 发布校验只解释显式节点职责；未配置节点仍是普通业务审批。 */
    public static Stage stage(Node node) {
        String configured = node.properties().get(PROPERTY);
        if (configured == null) return Stage.BUSINESS;
        if (node.type() != NodeType.USER_TASK) throw invalid("EXPENSE_STAGE_REQUIRES_USER_TASK", "Expense stages require a user task");
        try { return Stage.valueOf(configured); }
        catch (IllegalArgumentException unknown) { throw invalid("INVALID_EXPENSE_STAGE", "Expense task stage is invalid"); }
    }

    /**
     * 正式提交要求每条到结束的路径经过财务节点，需纸件时每个财务节点前必须经过签收。
     * 必经控制放在并行分支的共同路径，不能借其中一条并行支路隐式替代整单控制。
     */
    public static Map<String, Stage> requireSubmittable(Graph graph, FormSchema schema, boolean paperRequired) {
        ExpenseFormContract.requireSchema(schema);
        var stages = new TreeMap<String, Stage>(); var financial = new HashSet<String>(); var receipts = new HashSet<String>();
        var details = schema.fields().stream().filter(field -> field.key().equals(ExpenseFormContract.DETAILS)).findFirst().orElseThrow();
        for (var node : graph.nodes()) {
            var stage = stage(node);
            if (node.type() == NodeType.USER_TASK) stages.put(node.id(), stage);
            if (stage == Stage.BUSINESS) continue;
            if (details.visibility(Set.of(node.id())) != FieldVisibility.READ_ONLY) {
                throw invalid("EXPENSE_REVIEW_FIELDS_REQUIRED", "Expense receipt and finance tasks must be allowed to read expense details");
            }
            if (stage.finance()) financial.add(node.id());
            if (stage == Stage.RECEIPT) receipts.add(node.id());
        }
        if (financial.isEmpty() || reachableWithout(graph, financial, node -> node.type() == NodeType.END)) {
            throw invalid("EXPENSE_FINANCE_PATH_REQUIRED", "Every expense completion path must pass financial review");
        }
        if (paperRequired && (receipts.isEmpty() || reachableWithout(graph, receipts, node -> financial.contains(node.id())))) {
            throw invalid("EXPENSE_RECEIPT_PATH_REQUIRED", "Required paper receipt must precede every finance task");
        }
        return java.util.Collections.unmodifiableMap(stages);
    }

    private static boolean reachableWithout(Graph graph, Set<String> barriers, Predicate<Node> target) {
        var nodes = graph.nodes().stream().collect(Collectors.toMap(Node::id, node -> node));
        var outgoing = new HashMap<String, List<String>>();
        for (var edge : graph.edges()) outgoing.computeIfAbsent(edge.source(), ignored -> new java.util.ArrayList<>()).add(edge.target());
        var pending = new ArrayDeque<String>();
        graph.nodes().stream().filter(node -> node.type() == NodeType.START).forEach(node -> pending.add(node.id()));
        if (pending.size() != 1) throw invalid("EXPENSE_PROCESS_REQUIRED", "A valid published expense process is required");
        var visited = new HashSet<String>();
        while (!pending.isEmpty()) {
            String id = pending.removeFirst(); if (barriers.contains(id) || !visited.add(id)) continue;
            var node = nodes.get(id); if (node == null) throw invalid("EXPENSE_PROCESS_REQUIRED", "Expense process references an unknown node");
            if (target.test(node)) return true;
            pending.addAll(outgoing.getOrDefault(id, List.of()));
        }
        return false;
    }
    private static DomainException invalid(String code, String message) { return new DomainException(code, message); }

    /**
     * 财务核减和纸件确认只对明确职责的当前任务开放。
     * @author owlzhangfq@gmail.com
     */
    public enum Stage {
        BUSINESS, PRIOR_REQUEST_REVIEW, BUDGET_REVIEW, RECEIPT, FINANCE_REVIEW, FINANCE_RECHECK;
        /** 额度和预算例外属于业务审批责任，但不能被普通业务节点的重复审批策略跳过。 */
        public boolean businessApproval() { return this == BUSINESS || this == PRIOR_REQUEST_REVIEW || this == BUDGET_REVIEW; }
        /** 两类财务节点都必须复核预算及纸件事实。 */
        public boolean finance() { return this == FINANCE_REVIEW || this == FINANCE_RECHECK; }
    }
}
