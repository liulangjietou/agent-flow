package io.agentflow.approval;

import io.agentflow.approval.model.BusinessReference;
import io.agentflow.budget.BudgetFormBindingConfiguration;
import io.agentflow.expense.ExpenseFormBindingConfiguration;
import io.agentflow.form.FormSchema;
import io.agentflow.procurement.ProcurementFormBindingConfiguration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审批核心通过业务模块登记的契约识别结构化表单；每个业务类型必须恰好登记一次。
 * @author owlzhangfq@gmail.com
 */
class BusinessFormBindingsTest {
    @Test
    void everyBusinessTypeIsRegisteredByItsOwnModule() {
        try (var context = new AnnotationConfigApplicationContext(ExpenseFormBindingConfiguration.class,
                ProcurementFormBindingConfiguration.class, BudgetFormBindingConfiguration.class, BusinessFormBindings.class)) {
            var types = context.getBeansOfType(BusinessFormBinding.class).values().stream().map(BusinessFormBinding::type).toList();
            assertThat(types).containsExactlyInAnyOrder(BusinessReference.Type.values());
            assertThat(context.getBean(BusinessFormBindings.class).anyStructured(null)).isFalse();
        }
    }

    @Test
    void missingOrDuplicateRegistrationFailsAtStartup() {
        var all = new ArrayList<>(Arrays.stream(BusinessReference.Type.values()).map(BusinessFormBindingsTest::binding).toList());
        var missing = List.copyOf(all.subList(1, all.size()));
        assertThatThrownBy(() -> new BusinessFormBindings(missing)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Missing");
        all.add(binding(BusinessReference.Type.EXPENSE));
        assertThatThrownBy(() -> new BusinessFormBindings(all)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate");
    }

    @Test
    void schemaChecksAreRoutedToTheRegisteredBusinessContract() {
        var checked = new AtomicReference<BusinessReference.Type>();
        var bindings = new BusinessFormBindings(Arrays.stream(BusinessReference.Type.values())
                .map(type -> new BusinessFormBinding(type, schema -> type == BusinessReference.Type.BUDGET_ADJUSTMENT, schema -> checked.set(type))).toList());
        assertThat(bindings.anyStructured(null)).isTrue();
        bindings.requireSchema(BusinessReference.Type.PROCUREMENT_PAYMENT, null);
        assertThat(checked.get()).isEqualTo(BusinessReference.Type.PROCUREMENT_PAYMENT);
    }

    private static BusinessFormBinding binding(BusinessReference.Type type) {
        return new BusinessFormBinding(type, schema -> false, (FormSchema schema) -> { });
    }
}
