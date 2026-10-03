package io.agentflow.expense;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 财务配置命令拒绝重复键和尾随 JSON，避免保存值与管理员核对的原始请求存在歧义。
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class ExpenseConfigurationJsonConfiguration implements WebMvcConfigurer {
    /** 只为三个新配置输入隔离解析策略，保留统一金额模块及既有接口兼容行为。 */
    @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (var converter : converters) {
            if (!(converter instanceof MappingJackson2HttpMessageConverter jackson)) continue;
            var strict = jackson.getObjectMapper().copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            for (var input : List.of(ExpenseConfigurationInput.Categories.class, ExpenseConfigurationInput.Draft.class, ExpenseConfigurationInput.Publish.class)) {
                jackson.registerObjectMappersForType(input, types -> {
                    types.put(MediaType.APPLICATION_JSON, strict);
                    types.put(MediaType.valueOf("application/*+json"), strict);
                });
            }
        }
    }
}
