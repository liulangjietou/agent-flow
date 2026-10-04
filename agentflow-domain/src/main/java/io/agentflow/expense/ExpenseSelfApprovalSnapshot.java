package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.organization.InitiatorContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 提交轮次的原候选与上溯依据，后续组织换版不替换已固定的责任人。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseSelfApprovalSnapshot(String tenantId, UUID applicationId, int roundNo, UUID definitionId,
        long definitionVersion, String runtimeDefinitionId, int ruleVersion, InitiatorContext initiator,
        Map<String, Selection> nodes) {
    public ExpenseSelfApprovalSnapshot { nodes = Map.copyOf(nodes); }

    /** 节点必须来自本轮已发布图，不能在缺失快照时回退到另一套选人规则。 */
    public Selection node(String id) {
        var selected = nodes.get(id);
        if (selected == null) throw new DomainException("EXPENSE_APPROVAL_SNAPSHOT_MISSING", "The expense round has no frozen selection for this node");
        return selected;
    }

    /** 业务与财务责任双向互斥，兼容并行激活及财务之后的业务节点。 */
    public List<String> conflictingNodes(String nodeId) {
        boolean business = node(nodeId).stage() == ExpenseProcessPolicy.Stage.BUSINESS;
        return nodes.entrySet().stream().filter(entry ->
                (entry.getValue().stage() == ExpenseProcessPolicy.Stage.BUSINESS) != business)
                .map(Map.Entry::getKey).sorted().toList();
    }

    /**
     * 原始名单和替换后的名单分别保存，会签去重不能丢失原选人依据。
     * @author owlzhangfq@gmail.com
     */
    public record Selection(String nodeName, ExpenseProcessPolicy.Stage stage, String rule, long directoryRevision,
            List<String> originalSubjects, List<String> candidateSubjects, Escalation escalation) {
        public Selection {
            originalSubjects = List.copyOf(originalSubjects);
            candidateSubjects = List.copyOf(candidateSubjects);
        }
    }

    /**
     * 只沿本次发起任职上溯一级，不读取人员的其他兼任或推测主任职。
     * @author owlzhangfq@gmail.com
     */
    public record Escalation(UUID supervisorAppointmentId, String originalSubject, String replacementSubject,
            long directoryRevision) { }
}
