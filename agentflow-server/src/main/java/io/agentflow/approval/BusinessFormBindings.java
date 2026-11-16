package io.agentflow.approval;

import io.agentflow.approval.model.BusinessReference;
import io.agentflow.form.FormSchema;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 汇总各业务模块登记的表单契约。启动时校验每个业务类型恰好登记一次，缺失或重复都无法启动，
 * 避免运行期才发现某类业务申请可以绕过业务入口。
 * @author owlzhangfq@gmail.com
 */
@Component
public class BusinessFormBindings {
    private final Map<BusinessReference.Type, BusinessFormBinding> bindings = new EnumMap<>(BusinessReference.Type.class);

    /** 由 Spring 注入全部登记项。 */
    public BusinessFormBindings(List<BusinessFormBinding> registered) {
        for (BusinessFormBinding binding : registered) {
            if (bindings.putIfAbsent(binding.type(), binding) != null) {
                throw new IllegalStateException("Duplicate business form binding: " + binding.type());
            }
        }
        for (BusinessReference.Type type : BusinessReference.Type.values()) {
            if (!bindings.containsKey(type)) throw new IllegalStateException("Missing business form binding: " + type);
        }
    }

    /** 表单是否属于任一结构化业务；这类申请必须走各自业务入口创建。 */
    public boolean anyStructured(FormSchema schema) {
        return bindings.values().stream().anyMatch(binding -> binding.structured().test(schema));
    }

    /** 业务入口创建申请时，校验流程表单满足该业务的契约。 */
    public void requireSchema(BusinessReference.Type type, FormSchema schema) {
        bindings.get(type).requireSchema().accept(schema);
    }
}
