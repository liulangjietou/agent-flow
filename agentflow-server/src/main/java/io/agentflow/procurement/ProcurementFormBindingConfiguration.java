package io.agentflow.procurement;

import io.agentflow.approval.BusinessFormBinding;
import io.agentflow.approval.model.BusinessReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 采购付款向审批核心登记表单契约。
 * @author owlzhangfq@gmail.com
 */
@Configuration(proxyBeanMethods = false)
public class ProcurementFormBindingConfiguration {
    /** 采购付款申请。 */
    @Bean
    BusinessFormBinding procurementPaymentFormBinding() {
        return new BusinessFormBinding(BusinessReference.Type.PROCUREMENT_PAYMENT, ProcurementPaymentFormContract::structured,
                ProcurementPaymentFormContract::requireSchema);
    }
}
