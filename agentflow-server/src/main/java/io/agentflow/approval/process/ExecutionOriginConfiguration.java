package io.agentflow.approval.process;

import java.util.ArrayList;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 在原生创建事务中接入来源适配，保留其他业务监听器。
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class ExecutionOriginConfiguration {
    /** 与期限和子流程配置组合，不依赖其他监听器的执行顺序。 */
    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> executionOriginConfigurer(FlowableExecutionOriginListener listener) {
        return configuration -> {
            var listeners = new ArrayList<FlowableEventListener>();
            if (configuration.getEventListeners() != null) listeners.addAll(configuration.getEventListeners());
            listeners.add(listener);
            configuration.setEventListeners(listeners);
        };
    }
}
