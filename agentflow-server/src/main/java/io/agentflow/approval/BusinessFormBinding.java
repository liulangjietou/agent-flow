package io.agentflow.approval;

import io.agentflow.approval.model.BusinessReference;
import io.agentflow.form.FormSchema;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 业务模块向审批核心登记的表单契约：审批核心只通过它判断“这是否是某个业务的结构化表单”，
 * 不再直接依赖报销、采购、预算等业务包。新增业务类型只需在业务模块内注册一个 Bean。
 *
 * @param type          绑定的业务类型
 * @param structured    表单是否声明了该业务的结构化明细字段
 * @param requireSchema 业务入口创建申请时校验表单满足契约，不满足直接抛出领域异常
 * @author owlzhangfq@gmail.com
 */
public record BusinessFormBinding(BusinessReference.Type type, Predicate<FormSchema> structured, Consumer<FormSchema> requireSchema) {
    /** 三项均为必填，缺失属于装配错误。 */
    public BusinessFormBinding {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(structured, "structured");
        Objects.requireNonNull(requireSchema, "requireSchema");
    }
}
