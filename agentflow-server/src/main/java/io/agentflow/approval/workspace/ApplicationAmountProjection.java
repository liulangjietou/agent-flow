package io.agentflow.approval.workspace;

import io.agentflow.form.FormSchema;
import java.math.BigDecimal;
import java.util.Map;

/**
 * 从申请内容提取可查询的金额；这是读索引，不拥有金额核算规则。
 * @author owlzhangfq@gmail.com
 */
public final class ApplicationAmountProjection {
    private ApplicationAmountProjection() { }

    /** 仅识别 amount 数值字段；未声明、文本、空值和无效旧值均保留为未知。 */
    public static BigDecimal extract(Map<String, Object> payload, FormSchema schema) {
        if (schema != null && !"NUMBER".equals(schema.fieldTypes().get("amount"))) return null;
        Object raw = payload.get("amount");
        if (!(raw instanceof String) && !(raw instanceof Number)) return null;
        try { return FormSchema.decimal(raw.toString()); }
        catch (IllegalArgumentException invalidLegacyValue) { return null; }
    }
}
