package io.agentflow.definition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.agentflow.definition.DefinitionModels.Edge;
import static io.agentflow.definition.DefinitionModels.Graph;
import static io.agentflow.definition.DefinitionModels.Node;
import static io.agentflow.definition.DefinitionModels.NodeType;

/**
 * 验证成对、可嵌套的并行区域，阻止缺失令牌的汇合和普通节点的隐式并发合流。
 * @author owlzhangfq@gmail.com
 */
final class ParallelStructureValidator {
    /** 基础结构及无环性已由定义校验器检查；沿拓扑顺序传播分支归属。 */
    List<String> validate(Graph graph) {
        if (graph.nodes().stream().noneMatch(node -> node.type() == NodeType.PARALLEL_GATEWAY)) return List.of();
        Map<String, List<Edge>> incoming = new HashMap<>();
        Map<String, List<Edge>> outgoing = new HashMap<>();
        graph.nodes().forEach(node -> {
            incoming.put(node.id(), new ArrayList<>());
            outgoing.put(node.id(), new ArrayList<>());
        });
        graph.edges().forEach(edge -> {
            incoming.get(edge.target()).add(edge);
            outgoing.get(edge.source()).add(edge);
        });
        Map<String, Integer> degrees = new HashMap<>();
        List<Node> ready = new ArrayList<>();
        graph.nodes().forEach(node -> {
            degrees.put(node.id(), incoming.get(node.id()).size());
            if (incoming.get(node.id()).isEmpty()) ready.add(node);
        });
        Map<String, List<BranchFrame>> edgeScopes = new HashMap<>();
        Map<String, String> joins = new HashMap<>();
        for (int index = 0; index < ready.size(); index++) {
            Node node = ready.get(index);
            List<Edge> entrances = incoming.get(node.id());
            List<Edge> exits = outgoing.get(node.id());
            List<BranchFrame> scope = entrances.isEmpty() ? List.of() : edgeScopes.get(entrances.get(0).id());
            if (node.type() == NodeType.PARALLEL_GATEWAY) {
                if (entrances.size() == 1 && exits.size() == 1) return List.of("PARALLEL_BRANCH_REQUIRED:" + node.id());
                if (entrances.size() > 1) {
                    if (scope.isEmpty()) return List.of("PARALLEL_JOIN_MISMATCH:" + node.id());
                    BranchFrame branch = scope.get(scope.size() - 1);
                    List<BranchFrame> parent = scope.subList(0, scope.size() - 1);
                    Set<String> joinedBranches = new HashSet<>();
                    for (Edge entrance : entrances) {
                        List<BranchFrame> other = edgeScopes.get(entrance.id());
                        if (other.size() != scope.size() || !other.subList(0, other.size() - 1).equals(parent)) {
                            return List.of("PARALLEL_JOIN_MISMATCH:" + node.id());
                        }
                        BranchFrame last = other.get(other.size() - 1);
                        if (!last.forkId().equals(branch.forkId()) || !joinedBranches.add(last.branchId())) {
                            return List.of("PARALLEL_JOIN_MISMATCH:" + node.id());
                        }
                    }
                    Set<String> expected = new HashSet<>();
                    outgoing.get(branch.forkId()).forEach(edge -> expected.add(edge.id()));
                    String previousJoin = joins.putIfAbsent(branch.forkId(), node.id());
                    if (!joinedBranches.equals(expected) || previousJoin != null && !previousJoin.equals(node.id())) {
                        return List.of("PARALLEL_JOIN_MISMATCH:" + node.id());
                    }
                    scope = List.copyOf(parent);
                }
            } else {
                for (Edge entrance : entrances) {
                    if (!edgeScopes.get(entrance.id()).equals(scope)) {
                        return List.of("PARALLEL_MERGE_REQUIRES_GATEWAY:" + node.id());
                    }
                }
                if (node.type() == NodeType.END && !scope.isEmpty()) return List.of("PARALLEL_JOIN_REQUIRED:" + node.id());
            }
            for (Edge exit : exits) {
                // 并行区域不能通过普通节点的条件线静默丢失令牌；条件判断必须使用排他网关。
                if (!scope.isEmpty() && node.type() != NodeType.EXCLUSIVE_GATEWAY && !exit.condition().isBlank()) {
                    return List.of("PARALLEL_BRANCH_CONDITION_REQUIRES_GATEWAY:" + exit.id());
                }
                List<BranchFrame> next = scope;
                if (node.type() == NodeType.PARALLEL_GATEWAY && exits.size() > 1) {
                    next = new ArrayList<>(scope);
                    next.add(new BranchFrame(node.id(), exit.id()));
                    next = List.copyOf(next);
                }
                edgeScopes.put(exit.id(), next);
                if (degrees.merge(exit.target(), -1, Integer::sum) == 0) ready.add(graph.node(exit.target()));
            }
        }
        return List.of();
    }

    /**
     * 一层并行区域与其原始出线；排他路径合流保持相同归属。
     * @author owlzhangfq@gmail.com
     */
    private record BranchFrame(String forkId, String branchId) { }
}
