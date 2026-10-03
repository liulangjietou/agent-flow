package io.agentflow.definition;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事件节点只绑定明确契约，不能引入表达式或形成绕过全部人工审批的完成路径。
 * @author owlzhangfq@gmail.com
 */
class EventDefinitionTest {
    private final DefinitionValidator validator = new DefinitionValidator();

    @Test
    void acceptsExplicitVersionsButRejectsMissingAndAmbiguousReferences() {
        assertThat(validator.validate(graph(Map.of("eventContractKey", "accepted", "eventContractVersion", "1")))).isEmpty();
        assertThat(validator.validate(graph(Map.of()))).contains("EVENT_CONTRACT_REFERENCE_REQUIRED:wait");
        for (String version : List.of("0", "01", "latest", "-1", "1.5", "9223372036854775808", "${revision}")) {
            assertThat(validator.validate(graph(Map.of("eventContractKey", "accepted", "eventContractVersion", version))))
                    .contains("EVENT_CONTRACT_REFERENCE_INVALID:wait");
        }
        assertThat(validator.validate(graph(Map.of("eventContractKey", "${source}", "eventContractVersion", "1"))))
                .contains("EVENT_CONTRACT_REFERENCE_INVALID:wait");
    }

    @Test
    void eventContractReferencesBelongOnlyToEventWaitNodes() {
        var graph = graph(Map.of("eventContractKey", "accepted", "eventContractVersion", "1"));
        var nodes = new ArrayList<>(graph.nodes());
        nodes.replaceAll(n -> n.id().equals("review") ? new Node(n.id(), n.name(), n.type(), Map.of("assigneeRule", "user:finance", "eventContractKey", "accepted")) : n);
        assertThat(validator.validate(new Graph(nodes, graph.edges()))).contains("EVENT_REQUIRES_WAIT_NODE:review");
    }

    @Test
    void aMatchingEventCannotSubstituteForHumanApproval() {
        var graph = graph(Map.of("eventContractKey", "accepted", "eventContractVersion", "1"));
        var nodes = graph.nodes().stream().filter(n -> !n.id().equals("review")).toList();
        assertThat(validator.validate(new Graph(nodes, List.of(new Edge("a", "start", "wait", ""), new Edge("b", "wait", "end", "")))))
                .contains("EVENT_REQUIRES_APPROVAL_PATH:end");
    }

    @Test
    void waitNamesAndOutgoingFlowsAreNotExpressionOrImplicitForkEntrypoints() {
        var graph = graph(Map.of("eventContractKey", "accepted", "eventContractVersion", "1"));
        var nodes = new ArrayList<>(graph.nodes()); nodes.replaceAll(n -> n.id().equals("wait") ? new Node(n.id(), "${evil}", n.type(), n.properties()) : n);
        var edges = new ArrayList<>(graph.edges()); edges.add(new Edge("bypass", "wait", "end", ""));
        assertThat(validator.validate(new Graph(nodes, edges))).contains("TASK_NAME_EXPRESSION_FORBIDDEN:wait", "SINGLE_OUTGOING_REQUIRED:wait");
    }

    private Graph graph(Map<String, String> properties) {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("wait", "等待验收通知", NodeType.EVENT_WAIT, properties),
                new Node("review", "审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:finance")), new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "wait", ""), new Edge("b", "wait", "review", ""), new Edge("c", "review", "end", "")));
    }
}
