package io.agentflow.definition;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单路径模拟不能接受隐式并行与循环，发布校验必须守住相同的受限图语义。
 * @author owlzhangfq@gmail.com
 */
class SimulationStructureTest {
    @Test
    void ordinaryNodeCannotSilentlyForkParallelBranches() {
        var graph = new Graph(List.of(node("s", NodeType.START), node("a", NodeType.USER_TASK),
                node("b", NodeType.USER_TASK), node("e", NodeType.END)), List.of(
                new Edge("sa", "s", "a", ""), new Edge("sb", "s", "b", ""),
                new Edge("ae", "a", "e", ""), new Edge("be", "b", "e", "")));
        assertThat(new DefinitionValidator().validate(graph)).contains("SINGLE_OUTGOING_REQUIRED:s");
    }

    @Test
    void loopIsInvalidEvenWhenTheCurrentSampleWouldTakeTheExit() {
        var graph = new Graph(List.of(node("s", NodeType.START), node("g", NodeType.EXCLUSIVE_GATEWAY),
                node("a", NodeType.USER_TASK), node("e", NodeType.END)), List.of(
                new Edge("sg", "s", "g", ""), new Edge("ga", "g", "a", "amount > 10"),
                new Edge("ag", "a", "g", ""), new Edge("ge", "g", "e", "", true)));
        assertThat(new DefinitionValidator().validate(graph)).contains("GRAPH_LOOP");
    }

    @Test
    void startAndEndMustKeepTheirTerminalResponsibilities() {
        var graph = new Graph(List.of(node("s", NodeType.START), node("e", NodeType.END)),
                List.of(new Edge("se", "s", "e", ""), new Edge("es", "e", "s", "")));
        assertThat(new DefinitionValidator().validate(graph)).contains("START_MUST_HAVE_NO_INCOMING:s", "END_MUST_HAVE_NO_OUTGOING:e");
    }

    private Node node(String id, NodeType type) {
        return new Node(id, id, type, type == NodeType.USER_TASK ? Map.of("assigneeRule", "role:MANAGER") : Map.of());
    }
}
