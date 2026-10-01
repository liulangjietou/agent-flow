package io.agentflow.definition;

import io.agentflow.common.DomainException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.agentflow.definition.DefinitionModels.Graph;
import static io.agentflow.definition.DefinitionModels.NodeType;

/**
 * 节点职责分离规则；前序批准人与申请人是不同的排除依据，缺省不改变历史语义。
 * @author owlzhangfq@gmail.com
 */
public record ApprovalResponsibilityPolicy(boolean excludeApplicant, List<String> differentApproverFrom) {
    public static final String EXCLUDE_APPLICANT = "excludeApplicant";
    public static final String DIFFERENT_APPROVER_FROM = "differentApproverFrom";
    public static final Set<String> PROPERTY_KEYS = Set.of(EXCLUDE_APPLICANT, DIFFERENT_APPROVER_FROM);
    private static final int MAX_REFERENCES = 20;
    private static final int MAX_NODE_ID_LENGTH = 128;

    /** 引用集合有界且不允许空项、重复项或隐式修剪，以免发布含义不明确。 */
    public ApprovalResponsibilityPolicy {
        differentApproverFrom = List.copyOf(differentApproverFrom);
        if (differentApproverFrom.size() > MAX_REFERENCES
                || new HashSet<>(differentApproverFrom).size() != differentApproverFrom.size()
                || differentApproverFrom.stream().anyMatch(id -> id.isBlank() || !id.equals(id.strip())
                    || id.length() > MAX_NODE_ID_LENGTH || id.contains(","))) throw invalid();
    }

    /** 只接受规范布尔值；空引用属性不等同于未配置。 */
    public static ApprovalResponsibilityPolicy fromProperties(Map<String, String> properties) {
        String exclude = properties.getOrDefault(EXCLUDE_APPLICANT, "false");
        if (!exclude.equals("true") && !exclude.equals("false")) throw invalid();
        String references = properties.get(DIFFERENT_APPROVER_FROM);
        return new ApprovalResponsibilityPolicy(Boolean.parseBoolean(exclude),
                references == null ? List.of() : List.of(references.split(",", -1)));
    }

    /** 未启用的节点继续使用旧发布器生成的候选语义。 */
    public boolean enabled() { return excludeApplicant || !differentApproverFrom.isEmpty(); }

    /** 图已通过无环和并行结构校验；引用必须能沿顺序流到达当前节点。 */
    public boolean referencesValid(Graph graph, String nodeId) {
        return differentApproverFrom.stream().allMatch(id -> !id.equals(nodeId) && graph.node(id) != null
                && graph.node(id).type() == NodeType.USER_TASK && precedes(graph, id, nodeId));
    }

    private boolean precedes(Graph graph, String source, String target) {
        var pending = new ArrayDeque<String>(); pending.add(source);
        var visited = new HashSet<String>();
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (current.equals(target)) return true;
            if (!visited.add(current)) continue;
            graph.edges().stream().filter(edge -> edge.source().equals(current)).forEach(edge -> pending.addLast(edge.target()));
        }
        return false;
    }

    private static DomainException invalid() {
        return new DomainException("APPROVAL_RESPONSIBILITY_INVALID", "Approval responsibility policy is invalid");
    }
}
