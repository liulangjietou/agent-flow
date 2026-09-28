package io.agentflow.form;

import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionModels.Graph;
import io.agentflow.definition.DefinitionModels.NodeType;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * 事前申请和借款共同要求的人工审核边界；具体保留字段仍归各业务契约负责。
 * @author owlzhangfq@gmail.com
 */
public final class HumanReviewContract {
    private HumanReviewContract() { }

    /** 检查所有人工节点可读明细，且从开始到结束没有绕过人工审核的路径。 */
    public static void require(Graph graph, FormSchema.Field details, String fieldsErrorCode, String reviewErrorCode) {
        var reviews = new HashSet<String>();
        for (var node : graph.nodes()) {
            if (node.type() != NodeType.USER_TASK) continue;
            if (details.visibility(Set.of(node.id())) != FieldVisibility.READ_ONLY) {
                throw new DomainException(fieldsErrorCode, "Business reviewers must be allowed to read submitted details");
            }
            reviews.add(node.id());
        }
        var pending = new ArrayDeque<String>(); var visited = new HashSet<String>();
        graph.nodes().stream().filter(node -> node.type() == NodeType.START).forEach(node -> pending.add(node.id()));
        if (pending.size() != 1 || reviews.isEmpty()) throw missingReview(reviewErrorCode);
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (reviews.contains(id) || !visited.add(id)) continue;
            var node = graph.nodes().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow(() -> missingReview(reviewErrorCode));
            if (node.type() == NodeType.END) throw missingReview(reviewErrorCode);
            graph.edges().stream().filter(edge -> edge.source().equals(id)).forEach(edge -> pending.add(edge.target()));
        }
    }

    private static DomainException missingReview(String code) {
        return new DomainException(code, "Every business completion path must pass human review");
    }
}
