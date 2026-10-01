package io.agentflow.definition;

import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 子调用结构与发布开放状态分开；纯设计检查不查询子目录，也不启动或批准申请。
 * @author owlzhangfq@gmail.com
 */
class SubprocessDefinitionValidationTest {
    private final DefinitionValidator validator = new DefinitionValidator();

    @Test
    void validatesAnExplicitCallAndItsSensitiveInputPermission() {
        var field = new FormSchema.Field("amount", "金额", FormSchema.FieldType.NUMBER, true,
                null, null, null, null, null, null, null, true, Map.of("call", FieldVisibility.READ_ONLY));
        assertThat(validator.validate(graph("子审批", policy()), new FormSchema(2, List.of(field)))).isEmpty();
    }

    @Test
    void reportsInvalidReferencesAndExecutableNamesAtTheCallNode() {
        assertThat(validator.validate(graph("${unsafe.run()}", Map.of("subprocessKey", "child", "subprocessVersion", "latest"))))
                .contains("SUBPROCESS_REFERENCE_INVALID:call", "TASK_NAME_EXPRESSION_FORBIDDEN:call");
        assertThat(validator.validate(graph("子审批", Map.of()))).contains("SUBPROCESS_REFERENCE_REQUIRED:call");
    }

    @Test
    void rejectsOversizedLabelsAndImplicitParallelCalls() {
        assertThat(validator.validate(graph("长".repeat(200), policy()))).isEmpty();
        assertThat(validator.validate(graph("长".repeat(201), policy()))).contains("SUBPROCESS_NODE_LIMIT_EXCEEDED:call");
        var value = graph("子审批", policy());
        var nodes = new java.util.ArrayList<>(value.nodes()); nodes.add(new Node("other", "另一结束", NodeType.END, Map.of()));
        var edges = new java.util.ArrayList<>(value.edges()); edges.add(new Edge("extra", "call", "other", ""));
        assertThat(validator.validate(new Graph(nodes, edges))).contains("SINGLE_OUTGOING_REQUIRED:call");
    }

    @Test
    void simulationRecordsTheCallButDoesNotInventChildNodesOrChangeParentValues() {
        var result = new DefinitionSimulator().simulateDetailed(graph("子审批", policy()), null, new EvaluationContext(Map.of("amount", "5")));
        assertThat(result.path()).containsExactly("start", "call", "end");
        assertThat(result.edgeIds()).containsExactly("a", "b");
    }

    @Test
    void aPathThatBypassesEveryCallAndHumanApprovalCannotFinishAutomatically() {
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("choice", "分支", NodeType.EXCLUSIVE_GATEWAY, Map.of()),
                new Node("call", "子审批", NodeType.SUB_PROCESS, policy()), new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "choice", ""), new Edge("yes", "choice", "call", "amount > 0"),
                        new Edge("skip", "choice", "end", "", true), new Edge("b", "call", "end", "")));
        assertThat(validator.validate(graph)).contains("SUBPROCESS_REQUIRES_APPROVAL_PATH:end");
    }

    private Map<String, String> policy() { return new SubprocessPolicy("child", 1, Map.of("total", "amount")).properties(); }
    private Graph graph(String name, Map<String, String> properties) {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("call", name, NodeType.SUB_PROCESS, properties),
                new Node("end", "结束", NodeType.END, Map.of())), List.of(new Edge("a", "start", "call", ""), new Edge("b", "call", "end", "")));
    }
}
