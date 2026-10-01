package io.agentflow.organization;

import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;

/**
 * 节点激活时解析有效成员并记录当时目录修订；单人候选和全员会签共用这个解析边界。
 * @author owlzhangfq@gmail.com
 */
@Service
public class OrganizationAssigneeResolver {
    private final OrganizationRepository repository;
    private final LocalOrganizationDirectory directory;

    /** 在实际审批事务中锁定目录，成员与目录修订保持一致。 */
    public OrganizationAssigneeResolver(OrganizationRepository repository, LocalOrganizationDirectory directory) {
        this.repository = repository; this.directory = directory;
    }

    /** 运行时无人匹配必须回滚节点推进，不能跳过人工审批。 */
    @Transactional
    public Selection resolve(String tenantId, String rule) {
        return resolve(tenantId, rule, null);
    }

    /** 动态规则沿服务端冻结的任职起点读取当前组织关系，候选由调用方在节点激活时冻结。 */
    @Transactional
    public Selection resolve(String tenantId, String rule, InitiatorContext context) {
        long revision = repository.lock(tenantId);
        List<String> members = LocalOrganizationDirectory.isContextualRule(rule)
                ? contextualMembers(tenantId, rule, context, true)
                : directory.roleMembers(tenantId, rule.substring("role:".length()));
        if (members.isEmpty()) throw new DomainException("ORGANIZATION_NO_APPROVERS", "No active approvers match the organization rule");
        return new Selection(revision, rule, members);
    }

    /** 抄送与审批共用组织关系检查，但不要求收件人具有审批资格。 */
    @Transactional
    public Selection resolveCopy(String tenantId, String rule, InitiatorContext context) {
        return resolveRecipients(tenantId, rule, context, io.agentflow.approval.copy.CopyRecipient.MAX_RECIPIENTS, "COPY_RECIPIENT_UNAVAILABLE");
    }

    /** 升级只需要有效接收账号，名单在任务创建时冻结，不授予审批或字段读取权。 */
    @Transactional
    public Selection resolveEscalation(String tenantId, String rule, InitiatorContext context) {
        return resolveRecipients(tenantId, rule, context, io.agentflow.definition.TaskEscalationPolicy.MAX_RECIPIENTS, "ESCALATION_RECIPIENT_UNAVAILABLE");
    }

    private Selection resolveRecipients(String tenantId, String rule, InitiatorContext context, int maximum, String errorCode) {
        long revision = repository.initialized(tenantId) ? repository.lock(tenantId) : 0;
        var members = LocalOrganizationDirectory.isContextualRule(rule)
                ? contextualMembers(tenantId, rule, context, false) : directory.copyMembers(tenantId, rule);
        if (members.isEmpty() || members.size() > maximum) {
            throw new DomainException(errorCode, "Recipient rule must match a supported number of active recipients");
        }
        return new Selection(revision, rule, members);
    }

    private List<String> contextualMembers(String tenant, String rule, InitiatorContext context, boolean approvalRequired) {
        if (context == null) throw new DomainException("INITIATOR_APPOINTMENT_REQUIRED", "Select an initiator appointment for this process");
        var origin = appointment(tenant, context.appointmentId());
        if (!origin.personId().equals(context.personId()) || !origin.departmentId().equals(context.departmentId())) throw unavailable();
        var source = repository.person(tenant, origin.personId()).orElseThrow(OrganizationAssigneeResolver::unavailable);
        if (!source.subject().equals(context.subject()) || !source.active()) throw unavailable();
        requireActive(tenant, origin);
        OrganizationAppointment target;
        if (LocalOrganizationDirectory.DEPARTMENT_HEAD_RULE.equals(rule)) {
            var department = repository.unit(tenant, context.departmentId()).orElseThrow(OrganizationAssigneeResolver::unavailable);
            target = appointment(tenant, department.headAppointmentId());
            if (!department.id().equals(target.departmentId())) throw unavailable();
        } else {
            int level;
            try { level = Integer.parseInt(rule.substring(LocalOrganizationDirectory.SUPERVISOR_RULE.length())); }
            catch (NumberFormatException exception) { throw new DomainException("INVALID_ORGANIZATION_RULE", "Supervisor level is invalid"); }
            if (level < 1 || level > LocalOrganizationDirectory.MAX_SUPERVISOR_LEVEL) throw new DomainException("INVALID_ORGANIZATION_RULE", "Supervisor level is outside the supported range");
            target = origin;
            var visited = new HashSet<UUID>(); visited.add(origin.personId());
            for (int index = 0; index < level; index++) {
                target = appointment(tenant, target.supervisorAppointmentId());
                if (!visited.add(target.personId())) throw unavailable();
                requireActive(tenant, target);
                var department = repository.unit(tenant, target.departmentId()).orElseThrow(OrganizationAssigneeResolver::unavailable);
                if (!context.legalEntityId().equals(department.legalEntityId())) throw unavailable();
            }
        }
        requireActive(tenant, target);
        return repository.person(tenant, target.personId()).filter(person -> person.active() && (!approvalRequired || person.approvalEligible()))
                .map(person -> List.of(person.subject())).orElseThrow(OrganizationAssigneeResolver::unavailable);
    }

    private OrganizationAppointment appointment(String tenant, UUID id) {
        if (id == null) throw unavailable();
        return repository.appointment(tenant, id).orElseThrow(OrganizationAssigneeResolver::unavailable);
    }

    private void requireActive(String tenant, OrganizationAppointment value) {
        if (!value.active() || repository.person(tenant, value.personId()).filter(OrganizationPerson::active).isEmpty()) throw unavailable();
        var department = repository.unit(tenant, value.departmentId()).filter(OrganizationUnit::active).orElseThrow(OrganizationAssigneeResolver::unavailable);
        if (repository.unit(tenant, value.positionId()).filter(OrganizationUnit::active).isEmpty()
                || repository.unit(tenant, department.legalEntityId()).filter(OrganizationUnit::active).isEmpty()) throw unavailable();
    }

    private static DomainException unavailable() { return new DomainException("ORGANIZATION_NO_APPROVERS", "Dynamic organization relationship has no active approver"); }

    /**
     * 当时的目录修订、规则和实际成员均保留，不从当前目录补写历史。
     * @author owlzhangfq@gmail.com
     */
    public record Selection(long directoryRevision, String rule, List<String> subjects) {
        public Selection { subjects = List.copyOf(subjects); }
    }
}
