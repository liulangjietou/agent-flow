package io.agentflow.form;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.common.DomainException;
import org.springframework.boot.jackson.JsonComponent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 在 JSON 边界禁止默认类型强转，避免数字上下限和字段标记被静默改写。
 * @author owlzhangfq@gmail.com
 */
@JsonComponent
public class FormSchemaJsonDeserializer extends JsonDeserializer<FormSchema> {
    private static final Set<String> SCHEMA_PROPERTIES = Set.of("schemaVersion", "fields");
    private static final Set<String> FIELD_PROPERTIES = Set.of("key", "label", "type", "required", "helpText", "maxLength", "minimum", "maximum", "options");
    private static final Set<String> OPTION_PROPERTIES = Set.of("value", "label");
    /** 仅解码六种有限字段，具体结构和业务规则由领域值对象校验。 */
    @Override
    public FormSchema deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode root = parser.getCodec().readTree(parser);
        if (!root.isObject() || !root.path("schemaVersion").isIntegralNumber() || !root.path("schemaVersion").canConvertToInt()
                || !root.path("fields").isArray()) throw invalid();
        requireKnownProperties(root, SCHEMA_PROPERTIES);
        List<FormSchema.Field> fields = new ArrayList<>();
        for (JsonNode field : root.path("fields")) {
            if (!field.isObject() || !field.path("required").isBoolean()) throw invalid();
            requireKnownProperties(field, FIELD_PROPERTIES);
            FormSchema.FieldType type;
            try { type = FormSchema.FieldType.valueOf(text(field, "type", true)); }
            catch (IllegalArgumentException exception) { throw invalid(); }
            Integer maxLength = null;
            if (field.hasNonNull("maxLength")) {
                if (!field.get("maxLength").isIntegralNumber() || !field.get("maxLength").canConvertToInt()) throw invalid();
                maxLength = field.get("maxLength").intValue();
            }
            List<FormSchema.Option> options = null;
            if (field.hasNonNull("options")) {
                if (!field.get("options").isArray()) throw invalid();
                options = new ArrayList<>();
                for (JsonNode option : field.get("options")) {
                    requireKnownProperties(option, OPTION_PROPERTIES);
                    options.add(new FormSchema.Option(text(option, "value", true), text(option, "label", true)));
                }
            }
            fields.add(new FormSchema.Field(text(field, "key", true), text(field, "label", true), type,
                    field.get("required").booleanValue(), text(field, "helpText", false), maxLength,
                    text(field, "minimum", false), text(field, "maximum", false), options));
        }
        return new FormSchema(root.get("schemaVersion").intValue(), fields);
    }

    private void requireKnownProperties(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) throw invalid();
        node.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw invalid(); });
    }

    private String text(JsonNode node, String key, boolean required) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            if (required) throw invalid();
            return null;
        }
        if (!value.isTextual()) throw invalid();
        return value.textValue();
    }

    private DomainException invalid() { return new DomainException("INVALID_FORM_SCHEMA", "Form schema JSON has an invalid structure or field type"); }
}
