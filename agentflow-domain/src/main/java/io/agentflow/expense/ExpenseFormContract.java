package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import java.util.Map;

/**
 * 审批只接收从财务事实派生的路由值；完整费用内容由独立业务入口维护。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseFormContract {
    public static final String DETAILS = "expenseDetails";
    public static final String AMOUNT = "amount";
    public static final String CURRENCY = "currency";
    public static final String OVER_POLICY = "overPolicy";
    public static final String PRIOR_OVER_TOLERANCE = "priorRequestOverTolerance";
    private static final String DETAILS_LINK = "费用明细见报销单";
    private static final Map<String, FormSchema.FieldType> TYPES = Map.of(DETAILS, FormSchema.FieldType.TEXT,
            AMOUNT, FormSchema.FieldType.NUMBER, CURRENCY, FormSchema.FieldType.TEXT, OVER_POLICY, FormSchema.FieldType.BOOLEAN);

    private ExpenseFormContract() { }

    /** 完整费用组必须显式受敏感字段约束，管理员身份不会因此获得明细权限。 */
    public static void requireSchema(FormSchema schema) {
        if (schema == null || !schema.fieldTypes().keySet().containsAll(TYPES.keySet())
                || schema.fieldTypes().size() != TYPES.size() + (hasPriorControl(schema) ? 1 : 0)) throw invalid();
        for (var field : schema.fields()) {
            if (field.type() != (PRIOR_OVER_TOLERANCE.equals(field.key()) ? FormSchema.FieldType.BOOLEAN : TYPES.get(field.key())) || !field.required()
                    || DETAILS.equals(field.key()) && !Boolean.TRUE.equals(field.sensitive())) throw invalid();
        }
    }

    /** 只有明确采用新控制字段的定义才接收第五项服务端路由值，旧定义保持原载荷。 */
    public static boolean hasPriorControl(FormSchema schema) { return schema != null && schema.fieldTypes().containsKey(PRIOR_OVER_TOLERANCE); }

    /** 保留字段标识防止通用表单创建伪造的结构化申请。 */
    public static boolean structured(FormSchema schema) {
        return schema != null && schema.fields().stream().anyMatch(field -> DETAILS.equals(field.key()));
    }

    /** 草稿没有汇率和制度结果，不填写猜测的路由金额。 */
    public static Map<String, Object> draftPayload() { return Map.of(DETAILS, DETAILS_LINK); }

    /** 路由金额和超标标识只从冻结轮次派生，不读取客户端同名值。 */
    public static Map<String, Object> submittedPayload(ExpenseRound round) {
        return Map.of(DETAILS, DETAILS_LINK, AMOUNT, round.approvedGross().value().toPlainString(), CURRENCY, round.baseCurrency(),
                OVER_POLICY, round.originalLines().stream().anyMatch(line -> line.assessment().policy().decision() == ExpensePolicySnapshot.Decision.REQUIRES_EXCEPTION));
    }

    /** 可空标志保留旧定义的原四字段；非空值由提交时冻结的额度依据派生。 */
    public static Map<String, Object> submittedPayload(ExpenseRound round, Boolean priorOverTolerance) {
        if (priorOverTolerance == null) return submittedPayload(round);
        var values = new java.util.HashMap<>(submittedPayload(round)); values.put(PRIOR_OVER_TOLERANCE, priorOverTolerance); return Map.copyOf(values);
    }

    /** 读取完整明细需要该敏感组原样可读，脱敏占位文本不能当作授权。 */
    public static boolean detailsReadable(FormSchema original, FormSchema projected) {
        if (original == null || projected == null) return false;
        var source = original.fields().stream().filter(field -> DETAILS.equals(field.key())).findFirst();
        var visible = projected.fields().stream().filter(field -> DETAILS.equals(field.key())).findFirst();
        return source.isPresent() && source.equals(visible);
    }

    private static DomainException invalid() {
        return new DomainException("EXPENSE_FORM_CONTRACT_REQUIRED", "A structured expense definition with sensitive details and derived route fields is required");
    }
}
