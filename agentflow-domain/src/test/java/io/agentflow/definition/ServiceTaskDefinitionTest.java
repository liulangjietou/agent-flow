package io.agentflow.definition;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 服务节点的结构、安全和人工批准路径；可信目录及字段映射由应用层检查。
 * @author owlzhangfq@gmail.com
 */
class ServiceTaskDefinitionTest {
    private final DefinitionValidator validator = new DefinitionValidator();

    @Test
    void literalReferenceIsStructurallyValidAndSimulationDoesNotExecuteTheService() {
        var graph = graph(properties());
        assertThat(validator.validate(graph)).isEmpty();
        assertThat(new DefinitionSimulator().simulate(graph, new EvaluationContext(Map.of())))
                .containsExactly("start", "service", "review", "end");
    }

    @Test
    void canvasCoordinatesArePresentationOnlyAndCannotBecomeExecutableSettings() {
        var positioned = new HashMap<>(properties()); positioned.put("x", "180.5"); positioned.put("y", "240");
        assertThat(validator.validate(graph(positioned))).isEmpty();
        var policy = ServiceTaskPolicy.fromProperties(positioned);
        assertThat(policy.inputs()).isEmpty();
        for (String value : List.of("${bean.run()}", "NaN", "Infinity", "-1", "1e300", " ")) {
            var invalid = new HashMap<>(positioned); invalid.put("x", value);
            assertThat(validator.validate(graph(invalid))).as(value).contains("INVALID_SERVICE_TASK_POLICY:service");
        }
    }

    @Test
    void serviceConfigurationBelongsOnlyToServiceNodes() {
        var original = graph(properties()); var nodes = new ArrayList<>(original.nodes());
        var misplaced = new HashMap<>(properties()); misplaced.put("assigneeRule", "user:reviewer");
        nodes.replaceAll(node -> node.id().equals("review") ? new Node(node.id(), node.name(), node.type(), misplaced) : node);
        assertThat(validator.validate(new Graph(nodes, original.edges()))).contains("SERVICE_REQUIRES_SERVICE_NODE:review");
    }

    @Test
    void serviceNodesRejectUnsafePropertiesLabelsAndImplicitForks() {
        var unsafe = new HashMap<>(properties()); unsafe.put("url", "https://example.invalid");
        var original = graph(unsafe); var nodes = new ArrayList<>(original.nodes());
        nodes.replaceAll(node -> node.id().equals("service") ? new Node(node.id(), "${bean.run()}", node.type(), node.properties()) : node);
        var edges = new ArrayList<>(original.edges()); edges.add(new Edge("bypass", "service", "end", ""));
        assertThat(validator.validate(new Graph(nodes, edges))).contains("INVALID_SERVICE_TASK_POLICY:service",
                "TASK_NAME_EXPRESSION_FORBIDDEN:service", "SINGLE_OUTGOING_REQUIRED:service");
    }

    @Test
    void externalSuccessCannotCreateAPathWithoutHumanApproval() {
        var graph = graph(properties());
        var nodes = graph.nodes().stream().filter(node -> !node.id().equals("review")).toList();
        var edges = List.of(new Edge("a", "start", "service", ""), new Edge("b", "service", "end", ""));
        assertThat(validator.validate(new Graph(nodes, edges))).containsExactly("SERVICE_REQUIRES_APPROVAL_PATH:end");
    }

    private Map<String, String> properties() {
        return Map.of(ServiceTaskPolicy.KEY_PROPERTY, "receipt.register", ServiceTaskPolicy.VERSION_PROPERTY, "1", ServiceTaskPolicy.DIGEST_PROPERTY, "a".repeat(64));
    }
    private Graph graph(Map<String, String> properties) {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("service", "登记凭据", NodeType.SERVICE_TASK, properties),
                new Node("review", "审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:reviewer")), new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "service", ""), new Edge("b", "service", "review", ""), new Edge("c", "review", "end", "")));
    }
}
