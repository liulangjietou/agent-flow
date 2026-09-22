package io.agentflow.definition;

import java.util.*;
import static io.agentflow.definition.DefinitionModels.*;

/**
 * 在内存中模拟受限流程图，供发布前预览使用。
 * @author owlzhangfq@gmail.com
 */
public final class DefinitionSimulator {
    /** 按条件选择路径，返回访问顺序；循环和超过步数时失败。 */
    public List<String> simulate(Graph graph, EvaluationContext context) {
        List<String> errors = new DefinitionValidator().validate(graph);
        if (!errors.isEmpty()) throw new io.agentflow.common.DomainException("INVALID_DEFINITION", String.join(",", errors));
        List<String> path = new ArrayList<>(); String current = graph.nodes().stream().filter(n -> n.type() == NodeType.START).findFirst().orElseThrow().id();
        Set<String> visited = new HashSet<>();
        for (int step = 0; step < graph.nodes().size() + 1; step++) {
            if (!visited.add(current)) throw new io.agentflow.common.DomainException("GRAPH_LOOP", "Process graph contains a loop");
            path.add(current); Node node = graph.node(current); if (node.type() == NodeType.END) return List.copyOf(path);
            String currentNode = current;
            List<Edge> outgoing = graph.edges().stream()
                    .filter(e -> e.source().equals(currentNode))
                    .toList();
            String next = outgoing.stream()
                    .filter(e -> !e.defaultBranch())
                    .filter(e -> new ConditionParser().parse(e.condition()).evaluate(context))
                    .map(Edge::target).findFirst()
                    .orElseGet(() -> outgoing.stream().filter(Edge::defaultBranch).map(Edge::target).findFirst()
                            .orElseThrow(() -> new io.agentflow.common.DomainException("NO_BRANCH_MATCHED", "No outgoing branch matched")));
            current = next;
        }
        throw new io.agentflow.common.DomainException("SIMULATION_LIMIT", "Simulation step limit exceeded");
    }
}
