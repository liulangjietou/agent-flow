package io.agentflow.expense;

import io.agentflow.approval.service.ProcessRuntimePort.StartProcessCommand;
import io.agentflow.approval.service.TaskRecipientDirectory;
import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionModels.DefinitionDraft;
import io.agentflow.form.FormSchema;
import io.agentflow.organization.OrganizationAssigneeResolver;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 费用原项目来源与组织资格的连接；引擎和财务核减始终使用该轮已存依据。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseProjectApprovalBindings {
    private final JdbcExpenseProjectApprovalRepository projects;
    private final TaskRecipientDirectory directory;

    /** 财务来源归费用仓储，当前资格沿用组织目录。 */
    public ExpenseProjectApprovalBindings(JdbcExpenseProjectApprovalRepository projects, TaskRecipientDirectory directory) {
        this.projects = projects; this.directory = directory;
    }

    /** 调用方已持有组织锁；无项目只能返回明确验证过的空集合，不能任意省略其他节点。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public OrganizationAssigneeResolver.Selection select(StartProcessCommand command, DefinitionDraft definition, String nodeId, long revision) {
        var source = projects.findByApplication(command.tenantId(), command.applicationId(), command.roundNo()).orElseThrow(ExpenseProjectApprovalBindings::missing);
        if (!source.definitionId().equals(definition.id()) || source.definitionVersion() != definition.version()
                || !source.processKey().equals(definition.key()) || !Objects.equals(source.nodeId(), nodeId)
                || !source.precheck().initiator().equals(command.initiatorContext())
                || !Objects.equals(command.payload().get(ExpenseFormContract.HAS_PROJECT_ALLOCATION), source.hasProjects())
                || !nodeId.equals(ExpenseProjectApprovalPolicy.require(definition.graph(), command.formSchema(), source.hasProjects()))) throw missing();
        var subjects = source.owners().subjects();
        if (subjects.stream().anyMatch(subject -> !directory.eligible(command.tenantId(), subject))) {
            throw new DomainException("EXPENSE_PROJECT_OWNER_UNAVAILABLE", "Every original project owner must be currently eligible to approve");
        }
        return new OrganizationAssigneeResolver.Selection(revision, ExpenseProjectApprovalPolicy.ASSIGNEE_RULE, subjects);
    }

    /** 跨单路由核验和财务核减均沿用本轮项目集合；旧定义不增加额外字段。 */
    public Boolean routingFlag(ExpenseReport report, FormSchema schema) {
        if (!ExpenseFormContract.hasProjectControl(schema)) return null;
        return projects.find(report.tenantId(), report.id(), report.currentRound().roundNo()).orElseThrow(ExpenseProjectApprovalBindings::missing).hasProjects();
    }

    private static DomainException missing() {
        return new DomainException("EXPENSE_PROJECT_SNAPSHOT_MISSING", "The expense round requires its original project owner and routing evidence");
    }
}
