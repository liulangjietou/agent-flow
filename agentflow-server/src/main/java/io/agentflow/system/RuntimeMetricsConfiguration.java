package io.agentflow.system;

import io.agentflow.config.PollingSchedulingConfiguration;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 部署期望副本数与后台调度容量属于运行观测，不携带租户或业务标识。
 * @author owlzhangfq@gmail.com
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentflow.monitoring.enabled", havingValue = "true")
public class RuntimeMetricsConfiguration {
    /** 为 DNS 移除故障容器后的副本缺口提供期望值，避免只看到剩余健康目标。 */
    @Bean
    public MeterBinder expectedInstances(@Value("${agentflow.monitoring.expected-instances:1}") int expected) {
        if (expected < 1) throw new IllegalArgumentException("Expected instance count must be positive");
        return registry -> Gauge.builder("agentflow.runtime.expected.instances", () -> expected)
                .description("Configured number of application instances expected by the deployment")
                .register(registry);
    }

    /** 暴露当前实例已登记的后台轮询任务数，辅助排查空闲连接池占用与调度线程规模。 */
    @Bean
    public MeterBinder scheduledTasks(ObjectProvider<PollingSchedulingConfiguration> schedulingProvider) {
        return registry -> Gauge.builder("agentflow.runtime.scheduled.tasks", () -> {
            var scheduling = schedulingProvider.getIfAvailable();
            return scheduling == null ? 0 : scheduling.scheduledTaskCount();
        }).description("Number of registered background polling tasks on this instance").register(registry);
    }
}
