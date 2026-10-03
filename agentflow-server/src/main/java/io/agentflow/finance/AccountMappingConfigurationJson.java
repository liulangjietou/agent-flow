package io.agentflow.finance;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 新科目配置输入隔离严格 JSON 策略，不改变历史财务接口和领域恢复行为。
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class AccountMappingConfigurationJson implements WebMvcConfigurer {
    /** 管理员确认的文本与修订不能被隐式转换、重复键或尾随正文改变。 */
    @Override
    @SuppressWarnings("deprecation")
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (var converter : converters) {
            if (!(converter instanceof MappingJackson2HttpMessageConverter jackson)) continue;
            var strict = jackson.getObjectMapper().copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            for (var shape : List.of(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean)) {
                strict.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
            }
            for (var input : List.of(AccountMappingConfigurationInput.Draft.class, AccountMappingConfigurationInput.Publish.class)) {
                jackson.registerObjectMappersForType(input, types -> {
                    types.put(MediaType.APPLICATION_JSON, strict);
                    types.put(MediaType.valueOf("application/*+json"), strict);
                });
            }
        }
    }
}
