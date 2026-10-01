package io.agentflow.approval.process;

import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 在引擎初始化时登记唯一平台子流程启动拦截器，不替换原生调用活动实现。
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class SubprocessStartConfiguration {
    /** 冲突配置明确失败，避免静默覆盖另一个负责启动完整性的拦截器。 */
    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> subprocessStartConfigurer(FlowableSubprocessStartInterceptor interceptor) {
        return configuration -> {
            if (configuration.getStartProcessInstanceInterceptor() != null) {
                throw new IllegalStateException("A process start interceptor is already configured");
            }
            configuration.setStartProcessInstanceInterceptor(interceptor);
        };
    }
}
