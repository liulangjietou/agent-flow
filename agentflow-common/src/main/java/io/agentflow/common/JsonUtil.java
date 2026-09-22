package io.agentflow.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 统一 JSON 编解码入口，领域层不依赖 JSON 库。
 * @author owlzhangfq@gmail.com
 */
@Component
public class JsonUtil {
    private final ObjectMapper objectMapper;

    /** 使用 Spring 管理的 ObjectMapper。 */
    public JsonUtil(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 将对象编码为 JSON。 */
    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new DomainException("JSON_SERIALIZATION_FAILED", "Unable to serialize JSON");
        }
    }

    /** 将 JSON 解码为指定类型。 */
    public <T> T read(String value, Class<T> targetType) {
        try {
            return objectMapper.readValue(value, targetType);
        } catch (JsonProcessingException exception) {
            throw new DomainException("JSON_DESERIALIZATION_FAILED", "Unable to deserialize JSON");
        }
    }

    /** 将 JSON 解码为通用对象映射。 */
    public Map<String, Object> map(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new DomainException("JSON_DESERIALIZATION_FAILED", "Unable to deserialize JSON map");
        }
    }

    /** 将 JSON 解码为带泛型信息的目标类型。 */
    public <T> T read(String value, TypeReference<T> targetType) {
        try {
            return objectMapper.readValue(value, targetType);
        } catch (JsonProcessingException exception) {
            throw new DomainException("JSON_DESERIALIZATION_FAILED", "Unable to deserialize JSON");
        }
    }
}
