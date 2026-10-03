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
