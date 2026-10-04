package io.agentflow.organization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 同步命令隔离严格解析，拒绝重复键、尾随正文和标量替代，不改变存量接口的解析策略。
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class OrganizationSyncJsonConfiguration implements WebMvcConfigurer {
    /** 只绑定新命令类型，嵌套人工选择继承相同的严格规则。 */
    @SuppressWarnings("deprecation")
    @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (var converter : converters) if (converter instanceof MappingJackson2HttpMessageConverter jackson) {
            var strict = jackson.getObjectMapper().copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
            for (var type : List.of(OrganizationSyncController.QueueRequest.class, OrganizationSyncController.RetryRequest.class,
                    OrganizationSyncController.CancelRequest.class, OrganizationSyncController.PreflightRequest.class, OrganizationSyncController.ApplyRequest.class)) {
                jackson.registerObjectMappersForType(type, mappings -> {
                    mappings.put(MediaType.APPLICATION_JSON, strict); mappings.put(MediaType.valueOf("application/*+json"), strict);
                });
            }
        }
    }
}
