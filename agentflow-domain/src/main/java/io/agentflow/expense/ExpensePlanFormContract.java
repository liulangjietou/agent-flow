package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionModels.Graph;
import io.agentflow.definition.DefinitionModels.NodeType;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 事前申请的敏感明细组和审批边界；路由值只能从冻结计划派生。
 * @author owlzhangfq@gmail.com
 */
public final class ExpensePlanFormContract {
    public static final String DETAILS = "expensePlanDetails";
    private static final String DETAILS_LINK = "计划明细见事前申请";
    private static final Map<String, FormSchema.FieldType> TYPES = Map.of(DETAILS, FormSchema.FieldType.TEXT,
            "amount", FormSchema.FieldType.NUMBER, "currency", FormSchema.FieldType.TEXT);

    private ExpensePlanFormContract() { }

    /** 独立保留字段防止普通表单冒充可产生额度的事前申请。 */
    public static boolean structured(FormSchema schema) {
        return schema != null && schema.fieldTypes().containsKey(DETAILS);
    }

    /** 明细必须敏感，管理员不能绕过节点字段规则。 */
    public static void requireSchema(FormSchema schema) {
        if (schema == null || !schema.fieldTypes().keySet().equals(TYPES.keySet())
                || schema.fields().stream().anyMatch(field -> field.type() != TYPES.get(field.key()) || !field.required()
                    || DETAILS.equals(field.key()) && !Boolean.TRUE.equals(field.sensitive()))) {
            throw new DomainException("EXPENSE_PLAN_FORM_REQUIRED", "An expense plan requires sensitive details and derived amount and currency fields");
        }
    }

    /** 每条结束路径必须经人工审核，所有人工节点均能原样读取拟授权金额的依据。 */
    public static void requireReview(Graph graph, FormSchema schema) {
        requireSchema(schema);
        var details = schema.fields().stream().filter(field -> DETAILS.equals(field.key())).findFirst().orElseThrow();
        var reviews = new HashSet<String>();
        for (var node : graph.nodes()) {
            if (node.type() != NodeType.USER_TASK) continue;
            if (details.visibility(Set.of(node.id())) != FieldVisibility.READ_ONLY) {
                throw new DomainException("EXPENSE_PLAN_REVIEW_FIELDS_REQUIRED", "Expense plan reviewers must be allowed to read planned details");
            }
            reviews.add(node.id());
        }
        var pending = new ArrayDeque<String>(); var visited = new HashSet<String>();
        graph.nodes().stream().filter(node -> node.type() == NodeType.START).forEach(node -> pending.add(node.id()));
        if (pending.size() != 1 || reviews.isEmpty()) throw missingReview();
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (reviews.contains(id) || !visited.add(id)) continue;
            var node = graph.nodes().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow(ExpensePlanFormContract::missingReview);
            if (node.type() == NodeType.END) throw missingReview();
            graph.edges().stream().filter(edge -> edge.source().equals(id)).forEach(edge -> pending.add(edge.target()));
        }
    }

    /** 草稿还没有汇率依据，不填虚构总额。 */
    public static Map<String, Object> draftPayload() { return Map.of(DETAILS, DETAILS_LINK); }

    /** 审批路由与最终产生的额度逐行使用同一份本位币金额。 */
    public static Map<String, Object> submittedPayload(ExpensePlanRound round) {
        return Map.of(DETAILS, DETAILS_LINK, "amount", round.total().value().toPlainString(), "currency", round.total().currency());
    }

    /** 只有完整保留敏感组的投影可以进一步读取业务明细。 */
    public static boolean detailsReadable(FormSchema original, FormSchema projected) {
        if (original == null || projected == null) return false;
        var field = original.fields().stream().filter(value -> DETAILS.equals(value.key())).findFirst();
        return field.isPresent() && field.equals(projected.fields().stream().filter(value -> DETAILS.equals(value.key())).findFirst());
    }

    private static DomainException missingReview() {
        return new DomainException("EXPENSE_PLAN_REVIEW_REQUIRED", "Every expense plan completion path must pass human review");
    }
}
