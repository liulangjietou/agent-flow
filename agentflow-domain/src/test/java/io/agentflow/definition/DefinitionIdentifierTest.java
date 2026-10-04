package io.agentflow.definition;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.agentflow.definition.DefinitionModels.Edge;
import static io.agentflow.definition.DefinitionModels.Graph;
import static io.agentflow.definition.DefinitionModels.Node;
import static io.agentflow.definition.DefinitionModels.NodeType;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流程图中的节点与连线共享标识空间，同类重复错误仍保留原契约。
 * @author owlzhangfq@gmail.com
 */
class DefinitionIdentifierTest {
    private final DefinitionValidator validator = new DefinitionValidator();

    @ParameterizedTest
    @ValueSource(strings = {"start", "approve", "end"})
    void rejectsAnEdgeNamedAfterAnyNode(String id) {
        assertThat(validator.validate(graph(id))).containsExactly("NODE_EDGE_ID_CONFLICT:" + id);
    }

    @Test
    void keepsSameKindDuplicateDiagnosticsAndCaseSensitiveIdentity() {
        Graph original = graph("first");
        var duplicateNodes = new Graph(List.of(original.nodes().get(0), original.nodes().get(1),
                original.nodes().get(1), original.nodes().get(2)), original.edges());
        assertThat(validator.validate(duplicateNodes)).contains("DUPLICATE_NODE:approve");
        var duplicateEdges = new Graph(original.nodes(), List.of(original.edges().get(0), new Edge("first", "approve", "end", "")));
        assertThat(validator.validate(duplicateEdges)).contains("DUPLICATE_EDGE:first");
        assertThat(validator.validate(graph("Approve"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "2review", "-edge", ".edge", "edge:part", "with space", " edge", "edge ", "a\nb", "a\tb", "a/b", "a&b", "a\u0001b", "\u0301start"})
    void rejectsUnpublishableIdentifiersInAllThreeNamespaces(String id) {
        assertThat(validator.validate(graph(id, "first"))).containsExactly("INVALID_NODE_ID:" + id);
        assertThat(validator.validate(graph(id))).containsExactly("INVALID_EDGE_ID:" + id);
        assertThat(validator.validate(graph("first"), null, id)).containsExactly("INVALID_PROCESS_KEY:" + id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"_review", "review-1.2", "审批节点", "équipe", "a\u0301", "a\u00b7b", "xml", "xmlns"})
    void preservesValidUnicodeAndCaseSensitiveIdentifiers(String id) {
        assertThat(validator.validate(graph(id, "first"))).isEmpty();
        assertThat(validator.validate(graph(id))).isEmpty();
        assertThat(validator.validate(graph("first"), null, id)).isEmpty();
    }

    private Graph graph(String firstEdgeId) {
        return graph("approve", firstEdgeId);
    }

    private Graph graph(String nodeId, String firstEdgeId) {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node(nodeId, "审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:manager")),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge(firstEdgeId, "start", nodeId, ""), new Edge("last", nodeId, "end", "")));
    }
}
