package io.agentflow.expense;

import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.service.ProcessRuntimePort.StartProcessCommand;
import io.agentflow.approval.service.TaskRecipientDirectory;
import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionModels.DefinitionDraft;
import io.agentflow.definition.DefinitionModels.NodeType;
import io.agentflow.definition.FormAssigneePolicy;
import io.agentflow.organization.FormAssigneeBindings;
import io.agentflow.organization.LocalOrganizationDirectory;
import io.agentflow.organization.OrganizationAssigneeResolver;
import io.agentflow.organization.OrganizationRepository;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在正式提交事务中连接已发布费用策略、原申请和组织事实，空上级必须在流程启动前失败。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseSelfApprovalBindings {
    public static final String VARIABLE = "agentflowExpenseSelfApproval";
    private static final int DIRECT_SUPERVISOR_LEVEL = 1;
    private final ApplicationRepository applications;
    private final OrganizationRepository organizations;
    private final OrganizationAssigneeResolver resolver;
    private final TaskRecipientDirectory directory;
    private final FormAssigneeBindings formAssignees;

    /** 选人仍使用既有权威目录，费用层只编排本轮冻结和自审批替换。 */
    public ExpenseSelfApprovalBindings(ApplicationRepository applications, OrganizationRepository organizations,
            OrganizationAssigneeResolver resolver, TaskRecipientDirectory directory, FormAssigneeBindings formAssignees) {
        this.applications = applications; this.organizations = organizations; this.resolver = resolver;
        this.directory = directory; this.formAssignees = formAssignees;
    }

    /** 所有声明路径都预先检查，未命中的分支不能潜伏一个无法上溯的申请人。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExpenseSelfApprovalSnapshot freeze(StartProcessCommand command, DefinitionDraft definition,
            String runtimeDefinitionId, FormAssigneeBindings.Snapshot fields) {
        if (!ExpenseSelfApprovalPolicy.enabled(definition.graph())) return null;
        var application = applications.findById(command.tenantId(), command.applicationId()).orElseThrow(ExpenseSelfApprovalBindings::invalid);
        if (application.businessReference() == null || application.businessReference().type() != BusinessReference.Type.EXPENSE
                || command.initiatorContext() == null || !application.createdBy().equals(command.initiatorContext().subject())) throw invalid();
        long revision = organizations.lock(command.tenantId());
        var selections = new TreeMap<String, ExpenseSelfApprovalSnapshot.Selection>();
        ExpenseSelfApprovalSnapshot.Escalation escalation = null;
        for (var node : definition.graph().nodes()) {
            if (node.type() != NodeType.USER_TASK) continue;
            String rule = node.properties().get("assigneeRule");
            var selected = original(command, fields, node.id(), rule, revision);
            var original = selected.subjects().stream().distinct().sorted().toList();
            var stage = ExpenseProcessPolicy.stage(node);
            var candidates = original;
            ExpenseSelfApprovalSnapshot.Escalation replacement = null;
            if (original.contains(application.createdBy()) && stage.businessApproval()) {
                if (escalation == null) escalation = superior(command);
                replacement = escalation;
                String superior = escalation.replacementSubject();
                candidates = original.stream().map(subject -> subject.equals(application.createdBy()) ? superior : subject).distinct().sorted().toList();
            } else if (!stage.businessApproval()) {
                // 财务节点只排除冲突人员，绝不能借自审批策略改派主管或自动通过。
                candidates = original.stream().filter(subject -> !subject.equals(application.createdBy())).toList();
            }
            if (candidates.isEmpty()) throw new DomainException("APPROVAL_RESPONSIBILITY_NO_MEMBERS", "No approvers remain after applying expense responsibilities");
            selections.put(node.id(), new ExpenseSelfApprovalSnapshot.Selection(node.name(), stage, rule,
                    selected.directoryRevision(), original, candidates, replacement));
        }
        return new ExpenseSelfApprovalSnapshot(command.tenantId(), application.id(), command.roundNo(), definition.id(),
                definition.version(), runtimeDefinitionId, ExpenseSelfApprovalPolicy.RULE_VERSION, command.initiatorContext(), selections);
    }

    private OrganizationAssigneeResolver.Selection original(StartProcessCommand command, FormAssigneeBindings.Snapshot fields,
            String nodeId, String rule, long revision) {
        if (FormAssigneePolicy.isFieldRule(rule)) return formAssignees.requireActive(command.tenantId(), fields, nodeId, rule);
        if (LocalOrganizationDirectory.isLocalRule(rule)) return resolver.resolve(command.tenantId(), rule, command.initiatorContext());
        List<String> subjects = directory.members(command.tenantId(), rule.startsWith("user:") ? Set.of(rule.substring("user:".length())) : Set.of(),
                rule.startsWith("role:") ? Set.of(rule.substring("role:".length())) : Set.of());
        return new OrganizationAssigneeResolver.Selection(revision, rule, subjects);
    }

    private ExpenseSelfApprovalSnapshot.Escalation superior(StartProcessCommand command) {
        try {
            var selected = resolver.resolve(command.tenantId(), LocalOrganizationDirectory.SUPERVISOR_RULE + DIRECT_SUPERVISOR_LEVEL, command.initiatorContext());
            var origin = organizations.appointment(command.tenantId(), command.initiatorContext().appointmentId()).orElseThrow(ExpenseSelfApprovalBindings::invalid);
            return new ExpenseSelfApprovalSnapshot.Escalation(origin.supervisorAppointmentId(), command.initiatorContext().subject(),
                    selected.subjects().get(0), selected.directoryRevision());
        } catch (DomainException unavailable) {
            if (!"ORGANIZATION_NO_APPROVERS".equals(unavailable.code())) throw unavailable;
            throw new DomainException("APPROVER_NOT_FOUND", "The selected initiator appointment has no eligible direct supervisor");
        }
    }

    private static DomainException invalid() {
        return new DomainException("EXPENSE_APPROVAL_CONTEXT_INVALID", "Expense approval selection requires its original applicant and selected appointment");
    }
}
