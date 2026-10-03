package io.agentflow.definition;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 职责引用必须来自本图中已经可先完成的人工节点，不能引用未来或并行同级节点。
 * @author owlzhangfq@gmail.com
 */
class ApprovalResponsibilityPolicyTest {
    private final DefinitionValidator validator = new DefinitionValidator();

    @Test
    void invalidValuesAndRepeatedOrUnknownReferencesAreRejected() {
        for (String value : List.of("TRUE", "1", "", " true")) {
            assertThat(validator.validate(sequential(Map.of("excludeApplicant", value)))).contains("APPROVAL_RESPONSIBILITY_INVALID:review");
        }
        for (String value : List.of("", "first,first", " first", "first,")) {
            assertThat(validator.validate(sequential(Map.of("differentApproverFrom", value)))).contains("APPROVAL_RESPONSIBILITY_INVALID:review");
        }
        for (String reference : List.of("missing", "review", "end", "start")) {
            assertThat(validator.validate(sequential(Map.of("differentApproverFrom", reference)))).contains("APPROVAL_RESPONSIBILITY_REFERENCE_INVALID:review");
        }
    }

    @Test
    void validSequentialReferenceAndLegacyGraphRemainPublishable() {
        assertThat(validator.validate(sequential(Map.of("excludeApplicant", "true", "differentApproverFrom", "first")))).isEmpty();
        assertThat(validator.validate(sequential(Map.of()))).isEmpty();
    }

    @Test
    void futureAndParallelReferencesCannotCreateChangingResponsibility() {
        var graph = sequential(Map.of());
        var nodes = new java.util.ArrayList<>(graph.nodes());
        nodes.set(1, review("first", Map.of("differentApproverFrom", "review")));
        assertThat(validator.validate(new Graph(nodes, graph.edges()))).contains("APPROVAL_RESPONSIBILITY_REFERENCE_INVALID:first");
        var parallel = new Graph(List.of(start(), new Node("fork", "分支", NodeType.PARALLEL_GATEWAY, Map.of()),
                review("first", Map.of()), review("review", Map.of("differentApproverFrom", "first")),
                new Node("join", "汇合", NodeType.PARALLEL_GATEWAY, Map.of()), end()), List.of(
                edge("s", "start", "fork"), edge("a", "fork", "first"), edge("b", "fork", "review"),
                edge("c", "first", "join"), edge("d", "review", "join"), edge("e", "join", "end")));
        assertThat(validator.validate(parallel)).contains("APPROVAL_RESPONSIBILITY_REFERENCE_INVALID:review");
    }

    @Test
    void nonApprovalNodeCannotHideAnIgnoredPolicy() {
        var graph = sequential(Map.of());
        var nodes = new java.util.ArrayList<>(graph.nodes());
        nodes.set(0, new Node("start", "开始", NodeType.START, Map.of("excludeApplicant", "true")));
        assertThat(validator.validate(new Graph(nodes, graph.edges()))).contains("APPROVAL_RESPONSIBILITY_REQUIRES_USER_TASK:start");
    }

    private Graph sequential(Map<String, String> properties) {
        return new Graph(List.of(start(), review("first", Map.of()), review("review", properties), end()),
                List.of(edge("a", "start", "first"), edge("b", "first", "review"), edge("c", "review", "end")));
    }
    private Node review(String id, Map<String, String> properties) {
        var values = new java.util.HashMap<>(properties); values.put("assigneeRule", "role:APPROVER");
        return new Node(id, id, NodeType.USER_TASK, values);
    }
    private Node start() { return new Node("start", "开始", NodeType.START, Map.of()); }
    private Node end() { return new Node("end", "结束", NodeType.END, Map.of()); }
    private Edge edge(String id, String from, String to) { return new Edge(id, from, to, ""); }
}
