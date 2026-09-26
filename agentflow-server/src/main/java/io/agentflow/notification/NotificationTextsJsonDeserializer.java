package io.agentflow.notification;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.common.DomainException;
import org.springframework.boot.jackson.JsonComponent;
import java.io.IOException;
import java.util.Set;

/**
 * 通知配置入口拒绝未知事件和非文本值，避免拼写错误被静默忽略。
 * @author owlzhangfq@gmail.com
 */
@JsonComponent
public class NotificationTextsJsonDeserializer extends JsonDeserializer<NotificationTexts> {
    private static final Set<String> EVENTS = Set.of("submitted", "returned", "approved");

    /** 对象为空表示恢复统一提示；省略的单项文案使用统一提示。 */
    @Override
    public NotificationTexts deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode root = parser.getCodec().readTree(parser);
        if (!root.isObject()) throw invalid();
        var fields = root.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!EVENTS.contains(field.getKey()) || !field.getValue().isTextual()) throw invalid();
        }
        return new NotificationTexts(root.path("submitted").asText(""), root.path("returned").asText(""),
                root.path("approved").asText(""));
    }

    private static DomainException invalid() {
        return new DomainException("INVALID_NOTIFICATION_TEXT", "Notification texts require supported event names and string values");
    }
}
