package io.agentflow.expense;

import io.agentflow.agent.AssistScheduling;
import io.agentflow.config.DatabaseConfig;
import io.agentflow.config.PollingSchedulingConfiguration;
import io.agentflow.finance.BudgetOperationScheduling;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关闭消费时财务与模型配置不登记任何定时任务；统一调度器只为真实登记的任务分配线程。
 * @author owlzhangfq@gmail.com
 */
class InvoiceVerificationSchedulingTest {
    @Test
    void disabledWorkersRegisterNoPollersAndNoScheduledTasks() {
        new ApplicationContextRunner().withUserConfiguration(DatabaseConfig.class, PollingSchedulingConfiguration.class,
                        AssistScheduling.class, InvoiceVerificationScheduling.class, ExpensePrecheckScheduling.class, BudgetOperationScheduling.class)
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class, TaskSchedulingAutoConfiguration.class))
                .withPropertyValues("agentflow.assist.enabled=true", "agentflow.assist.worker-enabled=false",
                        "agentflow.finance-gateway.enabled=true", "agentflow.invoices.verification-worker-enabled=false",
                        "agentflow.expenses.precheck-worker-enabled=false", "agentflow.budgets.worker-enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("invoiceVerificationPoller").doesNotHaveBean("expensePrecheckPoller")
                            .doesNotHaveBean("budgetOperationPoller").doesNotHaveBean("assistPoller");
                    assertThat(context.getBeansOfType(ScheduledTaskHolder.class).values())
                            .allSatisfy(holder -> assertThat(holder.getScheduledTasks()).isEmpty());
                });
    }
}
