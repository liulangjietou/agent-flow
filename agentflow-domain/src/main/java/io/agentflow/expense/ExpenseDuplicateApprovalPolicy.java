package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionModels.Graph;
import io.agentflow.definition.DefinitionModels.NodeType;
import io.agentflow.form.FormSchema;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * 费用重复审批只沿已执行路径识别相邻业务节点，不能以相同角色或未经过的分支代替实际责任。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseDuplicateApprovalPolicy {
    public static final String PROPERTY = "expenseDuplicateApproval";
    public static final String AUTO_PASS_ADJACENT = "AUTO_PASS_ADJACENT";
    public static final String ACTION = "AUTO_PASSED_DUPLICATE";
    public static final int RULE_VERSION = 1;

    private ExpenseDuplicateApprovalPolicy() { }

    /** 缺省保持历史版本的人工审批；新策略只能在开始节点显式发布。 */
    public static boolean enabled(Graph graph) {
        boolean enabled = false;
        for (var node : graph.nodes()) {
            if (!node.properties().containsKey(PROPERTY)) continue;
            if (node.type() != NodeType.START || !AUTO_PASS_ADJACENT.equals(node.properties().get(PROPERTY))) {
                throw new DomainException("EXPENSE_DUPLICATE_POLICY_INVALID", "Expense duplicate approval policy requires the supported start-node setting");
            }
            enabled = true;
        }
        return enabled;
    }

    /** 本版本依赖费用轮次候选冻结及强制职责分离，不为普通申请隐式增加自动审批。 */
    public static void validate(Graph graph, FormSchema schema) {
        if (!enabled(graph)) return;
        if (!ExpenseFormContract.structured(schema)) {
            throw new DomainException("EXPENSE_DUPLICATE_REQUIRES_EXPENSE_FORM", "Expense duplicate approval policy requires an expense form");
        }
        if (!ExpenseSelfApprovalPolicy.enabled(graph)) {
            throw new DomainException("EXPENSE_DUPLICATE_REQUIRES_FROZEN_DUTIES", "Expense duplicate approval policy requires frozen expense responsibilities");
        }
    }

    /**
     * 图已通过无环校验；跨金额网关、抄送和等待保留相邻关系，只使用实际走过的边。
     * 财务、子审批边界和多个不同的并行前驱不能证明唯一相邻业务责任，继续人工办理。
     */
    public static String previousBusinessNode(Graph graph, String target, Set<String> takenEdges) {
        var current = graph.node(target);
        if (current == null || current.type() != NodeType.USER_TASK
                || ExpenseProcessPolicy.stage(current) != ExpenseProcessPolicy.Stage.BUSINESS) return null;
        var queue = new ArrayDeque<String>(); queue.add(target);
        var visited = new HashSet<String>(); var predecessors = new HashSet<String>();
        while (!queue.isEmpty()) {
            String id = queue.removeFirst(); if (!visited.add(id)) continue;
            var incoming = graph.edges().stream().filter(edge -> edge.target().equals(id) && takenEdges.contains(edge.id())).toList();
            if (incoming.isEmpty()) return null;
            for (var edge : incoming) {
                var node = graph.node(edge.source());
                if (node.type() == NodeType.USER_TASK) {
                    if (ExpenseProcessPolicy.stage(node) != ExpenseProcessPolicy.Stage.BUSINESS) return null;
                    predecessors.add(node.id());
                } else if (node.type() == NodeType.START || node.type() == NodeType.SUB_PROCESS) return null;
                else queue.add(node.id());
            }
        }
        return predecessors.size() == 1 ? predecessors.iterator().next() : null;
    }
}
