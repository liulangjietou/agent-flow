package io.agentflow.approval.process;

import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.service.TaskRecipientDirectory;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.ApprovalResponsibilityPolicy;
import io.agentflow.organization.LocalOrganizationDirectory;
import org.flowable.engine.TaskService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.task.service.delegate.DelegateTask;
import org.flowable.task.service.delegate.TaskListener;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 从原申请及本轮实际批准历史冻结职责约束；业务规则由领域对象表示，引擎事实在此读取。
 * @author owlzhangfq@gmail.com
 */
@Component("flowableApprovalResponsibilities")
public class FlowableApprovalResponsibilities implements TaskListener {
    public static final String SNAPSHOT_PREFIX = "agentflowApprovalResponsibilities_";
    public static final String DECISIONS_PREFIX = "agentflowApprovedSubjects_";
    private final ApplicationRepository applications;
    private final TaskRecipientDirectory directory;
    private final FlowableOrganizationMembers organization;
    private final TaskService tasks;
    private final RuntimeService runtime;
    private final JsonUtil json;

    /** 共用原目录解析，避免职责规则复制主管链或任职资格的判断。 */
    public FlowableApprovalResponsibilities(ApplicationRepository applications, TaskRecipientDirectory directory,
            FlowableOrganizationMembers organization, TaskService tasks, RuntimeService runtime, JsonUtil json) {
        this.applications = applications; this.directory = directory; this.organization = organization;
        this.tasks = tasks; this.runtime = runtime; this.json = json;
    }

    /**
     * 只由发布器给被引用的人工节点安装完成监听；引擎取消任务不会触发完成事件。
     * 历史查询在当前命令内尚未刷新，因此在推进之前把实际批准人同事务保存到本实例。
     */
    @Override
    public void notify(DelegateTask task) {
        if (!TaskListener.EVENTNAME_COMPLETE.equals(task.getEventName()) || task.getAssignee() == null) throw invalidScope();
        if (task.getTenantId() == null || !task.getTenantId().equals(task.getVariable("tenantId"))) throw invalidScope();
        String variable = DECISIONS_PREFIX + task.getTaskDefinitionKey();
        var subjects = new TreeSet<String>();
        if (runtime.getVariableLocal(task.getProcessInstanceId(), variable) instanceof String stored) subjects.addAll(json.read(stored, Decisions.class).subjects());
        subjects.add(task.getAssignee());
        runtime.setVariableLocal(task.getProcessInstanceId(), variable, json.write(new Decisions(List.copyOf(subjects))));
    }

    /** 受控发布表达式的参数均为规范布尔值和 Base64 字面量，不接收表单表达式。 */
    public List<String> resolve(DelegateExecution execution, String encodedRule, boolean excludeApplicant, String encodedReferences) {
        String tenant = execution.getTenantId();
        if (tenant == null || !tenant.equals(execution.getVariable("tenantId"))) throw invalidScope();
        String variable = SNAPSHOT_PREFIX + execution.getCurrentActivityId();
        if (execution.getVariableLocal(variable) instanceof String stored) return json.read(stored, Snapshot.class).candidateSubjects();
        String references = decode(encodedReferences);
        var policy = new ApprovalResponsibilityPolicy(excludeApplicant, references.isEmpty() ? List.of() : List.of(references.split(",", -1)));
        var excluded = new TreeSet<String>();
        if (policy.excludeApplicant()) {
            var application = applications.findById(tenant, UUID.fromString((String) execution.getVariable("applicationId")))
                    .orElseThrow(FlowableApprovalResponsibilities::invalidScope);
            excluded.add(application.createdBy());
        }
        for (String nodeId : policy.differentApproverFrom()) {
            Object decisions = execution.getVariable(DECISIONS_PREFIX + nodeId);
            if (decisions instanceof String stored) excluded.addAll(json.read(stored, Decisions.class).subjects());
        }
        String rule = decode(encodedRule);
        var original = (io.agentflow.definition.FormAssigneePolicy.isFieldRule(rule) || LocalOrganizationDirectory.isLocalRule(rule))
                ? organization.resolve(execution, encodedRule)
                : directory.members(tenant, rule.startsWith("user:") ? Set.of(rule.substring(5)) : Set.of(),
                    rule.startsWith("role:") ? Set.of(rule.substring(5)) : Set.of());
        var members = original.stream().filter(subject -> !excluded.contains(subject)).distinct().sorted().toList();
        if (members.isEmpty()) throw new DomainException("APPROVAL_RESPONSIBILITY_NO_MEMBERS", "No approvers remain after applying responsibility constraints");
        execution.setVariableLocal(variable, json.write(new Snapshot(policy, List.copyOf(excluded), members)));
        return members;
    }

    /** 实际办理和变更责任人共享冻结约束，不因新增任职或后续发布而重新解释。 */
    public boolean allows(Task task, String subject) {
        var snapshot = snapshot(task);
        return snapshot == null || !snapshot.excludedSubjects().contains(subject);
    }

    /** 选择器一次读取本节点快照，避免逐个候选重复读取引擎变量。 */
    public List<String> allowedTargets(Task task, List<String> subjects) {
        var snapshot = snapshot(task);
        return snapshot == null ? subjects : subjects.stream().filter(subject -> !snapshot.excludedSubjects().contains(subject)).toList();
    }

    /** 冲突不能通过转交、委派和加签成为新的合法责任。 */
    public void requireAllowed(Task task, String subject) {
        if (!allows(task, subject)) throw new DomainException("APPROVAL_RESPONSIBILITY_CONFLICT", "The selected user conflicts with the frozen approval responsibilities");
    }

    private Snapshot snapshot(Task task) {
        Object value = tasks.getVariable(task.getId(), SNAPSHOT_PREFIX + task.getTaskDefinitionKey());
        return value == null ? null : json.read((String) value, Snapshot.class);
    }

    private static String decode(String value) { return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }
    private static DomainException invalidScope() { return new DomainException("APPROVAL_RESPONSIBILITY_SCOPE_INVALID", "Approval responsibility scope is invalid"); }

    /**
     * 保留原规则、排除依据的人员集合与实际候选，供当前授权和历史责任核对。
     * @author owlzhangfq@gmail.com
     */
    public record Snapshot(ApprovalResponsibilityPolicy policy, List<String> excludedSubjects, List<String> candidateSubjects) {
        public Snapshot { excludedSubjects = List.copyOf(excludedSubjects); candidateSubjects = List.copyOf(candidateSubjects); }
    }

    /**
     * 一轮内某个人工节点的实际批准人员；失败回滚、取消和委派回交不产生此事实。
     * @author owlzhangfq@gmail.com
     */
    public record Decisions(List<String> subjects) {
        public Decisions { subjects = List.copyOf(subjects); }
    }
}
