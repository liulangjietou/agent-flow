package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseSplitRiskEvidence.Assessment;
import io.agentflow.expense.ExpenseSplitRiskEvidence.Document;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 原提交轮次的服务端路由依据；未配置和明确关闭也保存实际选择，不冒充检查通过。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseSplitRoutingSnapshot(int ruleVersion, UUID definitionId, String processKey, long definitionVersion,
        ExpenseSplitRiskPolicy.Configuration configuration, Document primary, Assessment assessment) {
    /** 计算字段必须能由同一批冻结来源复算，存储恢复不能把篡改的路由额变成有效快照。 */
    public ExpenseSplitRoutingSnapshot {
        if (ruleVersion != ExpenseSplitRiskPolicy.RULE_VERSION || definitionId == null || StringUtils.isBlank(processKey)
                || processKey.length() > 128 || definitionVersion < 1 || configuration == null || primary == null
                || (configuration.mode() == ExpenseSplitRiskPolicy.Mode.ENABLED) != (assessment != null)) throw invalid();
        if (assessment != null && (assessment.sources().isEmpty() || !primary.equals(assessment.sources().get(0))
                || !assessment.equals(ExpenseSplitRiskEvidence.assess(configuration.rule(), primary,
                assessment.sources().subList(1, assessment.sources().size()), primary.submittedAt())))) throw invalid();
    }

    public String tenantId() { return primary.scope().tenantId(); }
    public UUID reportId() { return primary.reportId(); }
    public UUID applicationId() { return primary.applicationId(); }
    public int roundNo() { return primary.roundNo(); }

    /** 关闭和未配置不读取其他单据，只保存本次提交自身的绑定。 */
    public List<Document> sources() { return assessment == null ? List.of(primary) : assessment.sources(); }

    private static DomainException invalid() {
        return new DomainException("EXPENSE_SPLIT_SNAPSHOT_INVALID", "Split routing snapshot must retain its original rule, source order and calculation");
    }
}
