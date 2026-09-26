package io.agentflow.approval.process;

import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;

/**
 * 将期限适配器接入真实任务创建事件，兼容已部署的版本化流程。
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class TaskDeadlineConfiguration {
    /** 保留其他监听器，只追加期限处理。 */
    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> taskDeadlineConfigurer(FlowableTaskDeadlineListener listener) {
        return configuration -> {
            var listeners = new ArrayList<FlowableEventListener>();
            if (configuration.getEventListeners() != null) listeners.addAll(configuration.getEventListeners());
            listeners.add(listener);
            configuration.setEventListeners(listeners);
        };
    }
}
