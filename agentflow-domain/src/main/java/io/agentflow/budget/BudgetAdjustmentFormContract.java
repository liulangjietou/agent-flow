package io.agentflow.budget;

import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionModels.Graph;
import io.agentflow.form.FormSchema;
import io.agentflow.form.HumanReviewContract;
import java.util.Map;

/**
 * 预算调整使用敏感明细组与服务端派生路由，普通表单不能伪造预算台账和批准依据。
 * @author owlzhangfq@gmail.com
 */
public final class BudgetAdjustmentFormContract {
    public static final String DETAILS = "budgetAdjustmentDetails";
    private static final String DETAILS_LINK = "预算额度与占用明细见预算调整申请";
    private static final Map<String, FormSchema.FieldType> TYPES = Map.of(DETAILS, FormSchema.FieldType.TEXT,
            "amount", FormSchema.FieldType.NUMBER, "currency", FormSchema.FieldType.TEXT);

    private BudgetAdjustmentFormContract() { }

    /** 专用保留字段用于识别结构化业务入口。 */
    public static boolean structured(FormSchema schema) { return schema != null && schema.fieldTypes().containsKey(DETAILS); }

    /** 金额只能派生，完整预算调整明细必须受敏感字段权限保护。 */
    public static void requireSchema(FormSchema schema) {
        if (schema == null || !schema.fieldTypes().keySet().equals(TYPES.keySet())
                || schema.fields().stream().anyMatch(field -> field.type() != TYPES.get(field.key()) || !field.required()
                || DETAILS.equals(field.key()) && !Boolean.TRUE.equals(field.sensitive()))) {
            throw new DomainException("BUDGET_ADJUSTMENT_FORM_REQUIRED", "Budget adjustment requires sensitive details and derived amount and currency");
        }
    }

    /** 所有结束路径都须人工审核，处理人必须完整读取本次额度变动依据。 */
    public static void requireReview(Graph graph, FormSchema schema) {
        requireSchema(schema);
        var details = schema.fields().stream().filter(field -> DETAILS.equals(field.key())).findFirst().orElseThrow();
        HumanReviewContract.require(graph, details, "BUDGET_ADJUSTMENT_REVIEW_FIELDS_REQUIRED", "BUDGET_ADJUSTMENT_REVIEW_REQUIRED");
    }

    /** 草稿没有可用于绕过财务冻结的路由金额。 */
    public static Map<String, Object> draftPayload() { return Map.of(DETAILS, DETAILS_LINK); }

    /** 路由金额来自本轮实际申请，不是原预算全额或客户端自报通过标记。 */
    public static Map<String, Object> submittedPayload(BudgetAdjustmentRound round) {
        return Map.of(DETAILS, DETAILS_LINK, "amount", round.content().amount().value().toPlainString(), "currency", round.content().amount().currency());
    }

    /** 管理员和历史审批人也需要保留完整敏感组才能读取明细。 */
    public static boolean detailsReadable(FormSchema original, FormSchema projected) {
        if (original == null || projected == null) return false;
        var field = original.fields().stream().filter(value -> DETAILS.equals(value.key())).findFirst();
        return field.isPresent() && field.equals(projected.fields().stream().filter(value -> DETAILS.equals(value.key())).findFirst());
    }
}
