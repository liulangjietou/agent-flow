package io.agentflow.definition;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抄送不能绕过人工审批或执行自定义表达式，节点权限只绑定允许读取的步骤。
 * @author owlzhangfq@gmail.com
 */
class CopyNodeTest {
    @Test
    void rejectsCopyOnlyPathAndExecutableRecipientRule() {
        var graph = new Graph(List.of(new Node("s", "开始", NodeType.START, Map.of()),
                new Node("c", "抄送", NodeType.COPY, Map.of("recipientRule", "${service.run()}")),
                new Node("e", "结束", NodeType.END, Map.of())), List.of(new Edge("a", "s", "c", ""), new Edge("b", "c", "e", "")));
        assertThat(new DefinitionValidator().validate(graph)).contains("ASSIGNEE_RULE_INVALID:c", "COPY_REQUIRES_APPROVAL_PATH:e");
    }

    @Test
    void rejectsApprovalPropertiesOnCopyAndAcceptsSafeLiteralRecipient() {
        var copy = new Node("c", "抄送", NodeType.COPY, Map.of("recipientRule", "user:bob", "approvalMode", "ALL"));
        var graph = new Graph(List.of(new Node("s", "开始", NodeType.START, Map.of()), copy,
                new Node("r", "审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:manager")),
                new Node("e", "结束", NodeType.END, Map.of())), List.of(new Edge("a", "s", "c", ""), new Edge("b", "c", "r", ""), new Edge("d", "r", "e", "")));
        assertThat(new DefinitionValidator().validate(graph)).containsExactly("APPROVAL_MODE_REQUIRES_USER_TASK:c");
        var safe = new Graph(graph.nodes().stream().map(node -> node == copy ? new Node("c", "抄送", NodeType.COPY, Map.of("recipientRule", "user:bob")) : node).toList(), graph.edges());
        assertThat(new DefinitionValidator().validate(safe)).isEmpty();
        assertThat(new DefinitionSimulator().simulateDetailed(safe, null, new EvaluationContext(Map.of())).path()).containsExactly("s", "c", "r", "e");
    }
}
