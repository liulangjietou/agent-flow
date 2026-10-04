package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionModels.Graph;
import io.agentflow.definition.DefinitionModels.NodeType;
import io.agentflow.form.FormSchema;

/**
 * 费用自审批上溯须在发布版本中明确启用，缺省保留已有版本的选人语义。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseSelfApprovalPolicy {
    public static final String PROPERTY = "expenseSelfApproval";
    public static final String ESCALATE_SUPERVISOR = "ESCALATE_SUPERVISOR";
    public static final int RULE_VERSION = 1;

    private ExpenseSelfApprovalPolicy() { }

    /** 只解释开始节点上的受限策略，不允许节点各自放宽财务职责分离。 */
    public static boolean enabled(Graph graph) {
        boolean enabled = false;
        for (var node : graph.nodes()) {
            if (!node.properties().containsKey(PROPERTY)) continue;
            if (node.type() != NodeType.START || !ESCALATE_SUPERVISOR.equals(node.properties().get(PROPERTY))) {
                throw new DomainException("EXPENSE_SELF_APPROVAL_POLICY_INVALID", "Expense self approval policy requires the supported start-node setting");
            }
            enabled = true;
        }
        return enabled;
    }

    /** 发布时限定结构化费用表单，不能给普通申请或其他财务业务隐式增加上溯规则。 */
    public static void validate(Graph graph, FormSchema schema) {
        if (enabled(graph) && !ExpenseFormContract.structured(schema)) {
            throw new DomainException("EXPENSE_SELF_APPROVAL_REQUIRES_EXPENSE_FORM", "Expense self approval policy requires an expense form");
        }
    }
}
