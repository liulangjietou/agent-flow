package io.agentflow.definition;

import io.agentflow.common.DomainException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import static io.agentflow.definition.DefinitionModels.*;

/**
 * 检查是否存在没有人工或子审批依据的完成路径；子调用的真实依据另由发布依赖逐层证明。
 * @author owlzhangfq@gmail.com
 */
public final class DefinitionApprovalPaths {
    private DefinitionApprovalPaths() { }

    /** 并行汇合必须等全部分支，互斥汇合允许其中一条路径到达。调用方先完成图结构校验。 */
    public static List<String> endsWithoutApproval(Graph graph) {
        Map<String, Boolean> withoutApproval = new HashMap<>();
        var remaining = new LinkedHashSet<>(graph.nodes().stream().map(Node::id).toList());
        var ends = new ArrayList<String>();
        while (!remaining.isEmpty()) {
            int before = remaining.size();
            for (String id : List.copyOf(remaining)) {
                Node node = graph.node(id);
                var parents = graph.edges().stream().filter(edge -> edge.target().equals(id)).map(Edge::source).toList();
                if (!withoutApproval.keySet().containsAll(parents)) continue;
                boolean bypass = node.type() == NodeType.START || node.type() != NodeType.USER_TASK && node.type() != NodeType.SUB_PROCESS
                        && (node.type() == NodeType.PARALLEL_GATEWAY && parents.size() > 1
                            ? parents.stream().allMatch(withoutApproval::get) : parents.stream().anyMatch(withoutApproval::get));
                withoutApproval.put(id, bypass);
                remaining.remove(id);
                if (node.type() == NodeType.END && bypass) ends.add(id);
            }
            if (remaining.size() == before) throw new DomainException("INVALID_DEFINITION", "Approval path requires an acyclic connected graph");
        }
        return List.copyOf(ends);
    }
}
