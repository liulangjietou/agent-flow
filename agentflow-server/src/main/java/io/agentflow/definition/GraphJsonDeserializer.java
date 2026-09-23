package io.agentflow.definition;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.common.DomainException;
import org.springframework.boot.jackson.JsonComponent;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import static io.agentflow.definition.DefinitionModels.*;

/**
 * 图版本是运行语义边界，禁止将小数、字符串或未知版本强制转换为已知版本。
 * @author owlzhangfq@gmail.com
 */
@JsonComponent
public class GraphJsonDeserializer extends JsonDeserializer<Graph> {
    private static final Set<String> FIELDS = Set.of("nodes", "edges", "conditionLanguageVersion");

    /** 兼容没有版本字段的旧 JSON，同时严格读取新版本。 */
    @Override
    public Graph deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode root = parser.getCodec().readTree(parser);
        if (!root.isObject()) throw new DomainException("INVALID_DEFINITION", "Graph must be an object");
        root.fieldNames().forEachRemaining(name -> {
            if (!FIELDS.contains(name)) throw new DomainException("INVALID_DEFINITION", "Unknown graph property");
        });
        JsonNode version = root.get("conditionLanguageVersion");
        if (version != null && (!version.isIntegralNumber() || !version.canConvertToInt())) {
            throw new DomainException("INVALID_CONDITION_VERSION", "Condition language version must be an integer");
        }
        List<Node> nodes = root.path("nodes").isMissingNode() || root.path("nodes").isNull() ? List.of()
                : Arrays.asList(parser.getCodec().treeToValue(root.get("nodes"), Node[].class));
        List<Edge> edges = root.path("edges").isMissingNode() || root.path("edges").isNull() ? List.of()
                : Arrays.asList(parser.getCodec().treeToValue(root.get("edges"), Edge[].class));
        return new Graph(nodes, edges, version == null ? 1 : version.intValue());
    }
}
