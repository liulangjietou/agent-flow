package io.agentflow.agent;

import io.agentflow.config.DatabaseConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.scheduling.TaskScheduler;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型网络等待不得占用期限提醒和集成投递的默认调度线程。
 * @author owlzhangfq@gmail.com
 */
class AssistSchedulingTest {
    @Test
    void enabledPollerActuallySchedulesExpenseDraftWork() {
        var summaries = org.mockito.Mockito.mock(AssistWorker.class);
        var ordinaryDrafts = org.mockito.Mockito.mock(DraftAssistWorker.class);
        var explanations = org.mockito.Mockito.mock(PrecheckExplanationWorker.class);
        var expenseDrafts = org.mockito.Mockito.mock(ExpenseDraftAssistWorker.class);
        new ApplicationContextRunner().withUserConfiguration(DatabaseConfig.class, AssistScheduling.class)
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class, TaskSchedulingAutoConfiguration.class))
                .withBean(AssistWorker.class, () -> summaries).withBean(DraftAssistWorker.class, () -> ordinaryDrafts)
                .withBean(PrecheckExplanationWorker.class, () -> explanations).withBean(ExpenseDraftAssistWorker.class, () -> expenseDrafts)
                .withPropertyValues("agentflow.assist.enabled=true", "agentflow.assist.poll-delay-ms=10")
                .run(context -> {
                    assertThat(context).hasBean("assistPoller");
                    org.mockito.Mockito.verify(expenseDrafts, org.mockito.Mockito.timeout(5000).atLeastOnce()).poll();
                    org.mockito.Mockito.verify(summaries, org.mockito.Mockito.atLeastOnce()).poll();
                    org.mockito.Mockito.verify(ordinaryDrafts, org.mockito.Mockito.atLeastOnce()).poll();
                    org.mockito.Mockito.verify(explanations, org.mockito.Mockito.atLeastOnce()).poll();
                });
    }

    @Test
    void enabledModelRetainsAnIndependentDefaultScheduler() {
        new ApplicationContextRunner().withUserConfiguration(DatabaseConfig.class, AssistScheduling.class)
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class, TaskSchedulingAutoConfiguration.class))
                .withPropertyValues("agentflow.assist.enabled=true", "agentflow.assist.worker-enabled=false")
                .run(context -> {
                    assertThat(context).hasBean("taskScheduler").hasBean("assistTaskScheduler").hasBean("applicationTaskExecutor");
                    assertThat(context.getBean("taskScheduler", TaskScheduler.class))
                            .isNotSameAs(context.getBean("assistTaskScheduler", TaskScheduler.class));
                });
    }
}
