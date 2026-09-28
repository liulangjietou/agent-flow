package io.agentflow.expense;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.io.IOException;
import java.math.BigDecimal;

/**
 * 金额在 HTTP 与持久 JSON 中统一使用十进制字符串，避免浏览器浮点数损失整分。
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class FinanceJsonConfiguration {
    /** 只定制金额值对象，不改变已有表单数值或版本号的 JSON 契约。 */
    @Bean
    public com.fasterxml.jackson.databind.Module financeMoneyModule() {
        var module = new SimpleModule("finance-money");
        module.addSerializer(Money.class, new MoneySerializer());
        module.addDeserializer(Money.class, new MoneyDeserializer());
        return module;
    }

    /**
     * 对外金额始终带币种，值始终保留两位小数。
     * @author owlzhangfq@gmail.com
     */
    private static final class MoneySerializer extends JsonSerializer<Money> {
        /** 按固定结构输出精确金额。 */
        @Override public void serialize(Money money, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeStartObject(); generator.writeStringField("value", money.value().toPlainString());
            generator.writeStringField("currency", money.currency()); generator.writeEndObject();
        }
    }

    /**
     * 拒绝 JSON 浮点数、科学计数法及未知金额字段，不进行隐式舍入。
     * @author owlzhangfq@gmail.com
     */
    private static final class MoneyDeserializer extends JsonDeserializer<Money> {
        /** 在 JSON 边界拒绝可能已经丢失精度的数值输入。 */
        @Override public Money deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            // 在当前字段上下文读取子树；重新进入根读取会把后续正常字段误判成多余 JSON。
            var value = context.readTree(parser);
            if (!value.isObject() || value.size() != 2 || value.get("value") == null || !value.get("value").isValueNode()
                    || value.get("currency") == null || !value.get("currency").isValueNode()) throw invalid();
            var node = (com.fasterxml.jackson.databind.JsonNode) value;
            if (!node.get("value").isTextual() || !node.get("currency").isTextual()
                    || !node.get("value").textValue().matches("(?:0|[1-9][0-9]{0,14})(?:\\.[0-9]{1,2})?")) throw invalid();
            return new Money(new BigDecimal(node.get("value").textValue()), node.get("currency").textValue());
        }

        private static DomainException invalid() { return new DomainException("INVALID_MONEY", "Money requires a decimal string and currency"); }
    }
}
