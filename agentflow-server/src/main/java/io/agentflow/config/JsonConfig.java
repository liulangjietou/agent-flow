package io.agentflow.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 统一接口与持久化 JSON 的可空内容语义，表单显式清空字段不能被序列化器删除。
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class JsonConfig {
    /** 保留已有对象字段包含策略，同时保留 Map 和嵌套内容中的 null 值。 */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer preserveNullContainerValues() {
        return builder -> builder.postConfigurer(mapper -> mapper.setDefaultPropertyInclusion(
                mapper.getSerializationConfig().getDefaultPropertyInclusion()
                        .withContentInclusion(JsonInclude.Include.ALWAYS)));
    }
}
