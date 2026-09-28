package io.agentflow.expense;

import io.agentflow.agent.AssistScheduling;
import io.agentflow.config.DatabaseConfig;
import io.agentflow.finance.BudgetOperationScheduling;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.scheduling.TaskScheduler;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同时启用模型和验票时，两类外部等待都不能占用审批的默认调度线程。
 * @author owlzhangfq@gmail.com
 */
class InvoiceVerificationSchedulingTest {
    @Test
    void financeAndModelRetainSeparateSchedulersAndIndependentDefault() {
        new ApplicationContextRunner().withUserConfiguration(DatabaseConfig.class, AssistScheduling.class, InvoiceVerificationScheduling.class, ExpensePrecheckScheduling.class, BudgetOperationScheduling.class)
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class, TaskSchedulingAutoConfiguration.class))
                .withPropertyValues("agentflow.assist.enabled=true", "agentflow.assist.worker-enabled=false",
                        "agentflow.finance-gateway.enabled=true", "agentflow.invoices.verification-worker-enabled=false", "agentflow.expenses.precheck-worker-enabled=false", "agentflow.budgets.worker-enabled=false")
                .run(context -> {
                    assertThat(context).hasBean("taskScheduler").hasBean("assistTaskScheduler").hasBean("invoiceVerificationTaskScheduler");
                    assertThat(context.getBean("invoiceVerificationTaskScheduler", TaskScheduler.class))
                            .isNotSameAs(context.getBean("taskScheduler", TaskScheduler.class)).isNotSameAs(context.getBean("assistTaskScheduler", TaskScheduler.class));
                    assertThat(context).doesNotHaveBean("invoiceVerificationPoller").doesNotHaveBean("expensePrecheckPoller");
                    assertThat(context.getBean("expensePrecheckTaskScheduler", TaskScheduler.class))
                            .isNotSameAs(context.getBean("taskScheduler", TaskScheduler.class)).isNotSameAs(context.getBean("invoiceVerificationTaskScheduler", TaskScheduler.class));
                    assertThat(context).hasBean("budgetOperationTaskScheduler").doesNotHaveBean("budgetOperationPoller");
                    assertThat(context.getBean("budgetOperationTaskScheduler", TaskScheduler.class))
                            .isNotSameAs(context.getBean("taskScheduler", TaskScheduler.class)).isNotSameAs(context.getBean("expensePrecheckTaskScheduler", TaskScheduler.class));
                });
    }
}
