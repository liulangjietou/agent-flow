package io.agentflow.definition;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.definition.DefinitionSimulator.Outcome.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 验证分支依据、默认分支和实际选择顺序使用同一次求值。
 * @author owlzhangfq@gmail.com
 */
class DefinitionSimulationTraceTest {
    @Test
    void firstMatchStopsEvaluationAndReportsSkippedBranchesHonestly() {
        var result = new DefinitionSimulator().simulateDetailed(graph(true), null, new EvaluationContext(Map.of("amount", "20")));
        assertThat(result.path()).containsExactly("s", "g", "a", "e");
        assertThat(result.edgeIds()).containsExactly("sg", "ga", "ae");
        assertThat(result.decisions().get(0).branches()).extracting(DefinitionSimulator.Branch::outcome)
                .containsExactly(MATCHED, SKIPPED, DEFAULT_SKIPPED);
        assertThat(result.decisions().get(0).selectedEdgeId()).isEqualTo("ga");
    }

    @Test
    void defaultListedBeforeConditionsIsOnlyFallback() {
        var result = new DefinitionSimulator().simulateDetailed(graph(true), null, new EvaluationContext(Map.of("amount", "0")));
        assertThat(result.path()).containsExactly("s", "g", "e");
        assertThat(result.decisions().get(0).branches()).extracting(DefinitionSimulator.Branch::outcome)
                .containsExactly(NOT_MATCHED, NOT_MATCHED, DEFAULT_SELECTED);
    }

    @Test
    void unmatchedWithoutDefaultFailsInsteadOfInventingAPath() {
        assertThatThrownBy(() -> new DefinitionSimulator().simulateDetailed(graph(false), null, new EvaluationContext(Map.of("amount", "0"))))
                .isInstanceOf(DomainException.class).hasMessageContaining("No outgoing branch matched at node: g");
    }

    private Graph graph(boolean fallback) {
        var edges = new java.util.ArrayList<Edge>();
        edges.add(new Edge("sg", "s", "g", ""));
        if (fallback) edges.add(new Edge("ge", "g", "e", "", true));
        edges.add(new Edge("ga", "g", "a", "amount > 10"));
        edges.add(new Edge("gb", "g", "b", "amount > 5"));
        edges.add(new Edge("ae", "a", "e", ""));
        edges.add(new Edge("be", "b", "e", ""));
        return new Graph(List.of(new Node("s", "开始", NodeType.START, Map.of()),
                new Node("g", "分支", NodeType.EXCLUSIVE_GATEWAY, Map.of()),
                new Node("a", "审批A", NodeType.USER_TASK, Map.of("assigneeRule", "role:MANAGER")),
                new Node("b", "审批B", NodeType.USER_TASK, Map.of("assigneeRule", "role:MANAGER")),
                new Node("e", "结束", NodeType.END, Map.of())), edges);
    }
}
