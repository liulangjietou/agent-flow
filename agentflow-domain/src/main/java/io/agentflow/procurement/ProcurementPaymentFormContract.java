package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionModels.Graph;
import io.agentflow.form.FormSchema;
import io.agentflow.form.HumanReviewContract;
import java.util.Map;

/**
 * 采购使用敏感明细组与服务端派生路由，普通表单不能伪造供应商应付业务。
 * @author owlzhangfq@gmail.com
 */
public final class ProcurementPaymentFormContract {
    public static final String DETAILS = "procurementPaymentDetails";
    private static final String DETAILS_LINK = "供应商应付与验收明细见采购付款申请";
    private static final Map<String, FormSchema.FieldType> TYPES = Map.of(DETAILS, FormSchema.FieldType.TEXT,
            "amount", FormSchema.FieldType.NUMBER, "currency", FormSchema.FieldType.TEXT);

    private ProcurementPaymentFormContract() { }

    /** 专用保留字段用于识别结构化业务入口。 */
    public static boolean structured(FormSchema schema) { return schema != null && schema.fieldTypes().containsKey(DETAILS); }

    /** 金额只能派生，完整采购明细必须受敏感字段权限保护。 */
    public static void requireSchema(FormSchema schema) {
        if (schema == null || !schema.fieldTypes().keySet().equals(TYPES.keySet())
                || schema.fields().stream().anyMatch(field -> field.type() != TYPES.get(field.key()) || !field.required()
                || DETAILS.equals(field.key()) && !Boolean.TRUE.equals(field.sensitive()))) {
            throw new DomainException("PROCUREMENT_FORM_REQUIRED", "Procurement payment requires sensitive details and derived amount and currency");
        }
    }

    /** 所有结束路径都须人工审核，处理人必须完整读取本次拟付依据。 */
    public static void requireReview(Graph graph, FormSchema schema) {
        requireSchema(schema);
        var details = schema.fields().stream().filter(field -> DETAILS.equals(field.key())).findFirst().orElseThrow();
        HumanReviewContract.require(graph, details, "PROCUREMENT_REVIEW_FIELDS_REQUIRED", "PROCUREMENT_REVIEW_REQUIRED");
    }

    /** 草稿没有可用于绕过财务冻结的路由金额。 */
    public static Map<String, Object> draftPayload() { return Map.of(DETAILS, DETAILS_LINK); }

    /** 路由金额来自本轮实际申请，不是原应付全额或客户端自报通过标记。 */
    public static Map<String, Object> submittedPayload(ProcurementPaymentRound round) {
        return Map.of(DETAILS, DETAILS_LINK, "amount", round.content().amount().value().toPlainString(), "currency", round.content().amount().currency());
    }

    /** 管理员和历史审批人也需要保留完整敏感组才能读取明细。 */
    public static boolean detailsReadable(FormSchema original, FormSchema projected) {
        if (original == null || projected == null) return false;
        var field = original.fields().stream().filter(value -> DETAILS.equals(value.key())).findFirst();
        return field.isPresent() && field.equals(projected.fields().stream().filter(value -> DETAILS.equals(value.key())).findFirst());
    }
}
