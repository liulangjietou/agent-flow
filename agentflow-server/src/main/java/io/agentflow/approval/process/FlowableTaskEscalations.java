package io.agentflow.approval.process;


import io.agentflow.approval.SubprocessExecutionLocks;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.process.mapper.FlowableTaskEscalationsMapper;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.JsonUtil;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.notification.ApprovalNotificationService;
import io.agentflow.organization.LocalOrganizationDirectory;
import io.agentflow.organization.OrganizationAssigneeResolver;

import org.flowable.engine.TaskService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 升级只投递原名单的协调通知，与原任务及父子轮次共用事务和锁。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class FlowableTaskEscalations {
    public static final int BATCH_SIZE = 100;
    private final FlowableTaskEscalationsMapper sqlMapper;
    private final TaskService tasks;
    private final ApplicationRepository applications;
    private final SubprocessExecutionLocks locks;
    private final LocalOrganizationDirectory directory;
    private final ApprovalNotificationService notifications;
    private final JsonUtil json;

    /** 当前人员状态可以阻止投递，但不能把原名单替换成新组织成员。 */
    public FlowableTaskEscalations(
            FlowableTaskEscalationsMapper sqlMapper,
            TaskService tasks,
            ApplicationRepository applications,
            SubprocessExecutionLocks locks,
            LocalOrganizationDirectory directory,
            ApprovalNotificationService notifications,
            JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.tasks = tasks;
        this.applications = applications;
        this.locks = locks;
        this.directory = directory;
        this.notifications = notifications;
        this.json = json;
    }

    /** 使用日期变量的毫秒列扫描，不按字符串时间比较，也不扫描暂停或已经升级的任务。 */
    @Transactional(readOnly = true)
    public List<Candidate> candidates(Instant now, Candidate after) {
        var parameters =
                new ArrayList<Object>(
                        List.of(
                                TaskEscalationBindings.DUE_AT,
                                now.toEpochMilli(),
                                TaskEscalationBindings.ESCALATED_AT));

        if (after != null) {

            parameters.add(after.dueAt().toEpochMilli());
            parameters.add(after.dueAt().toEpochMilli());
            parameters.add(after.taskId());
        }
        parameters.add(BATCH_SIZE);
        return SqlRows.map(
                sqlMapper.candidatesQuery((after != null), parameters.toArray()),
                row ->
                        new Candidate(
                                row.getString("ID_"),
                                Instant.ofEpochMilli(row.getLong("LONG_")),
                                row.getString("TENANT_ID_"),
                                row.getString("trace_id"),
                                row.getString("business_no"),
                                row.getString("process_instance_id")));
    }

    /** 申请与原任务锁后重查；完成、撤回、终止及父子暂停不能靠先前扫描结果继续投递。 */
    @Transactional
    public boolean escalate(String taskId, Instant now) {
        var initial =
                tasks.createTaskQuery().taskId(taskId).includeProcessVariables().singleResult();
        if (initial == null) return false;
        var variables = initial.getProcessVariables();
        if (!(variables.get("tenantId") instanceof String tenant)
                || !(variables.get("applicationId") instanceof String id)) return false;
        var found = applications.findById(tenant, UUID.fromString(id)).orElse(null);
        if (found == null) return false;
        var locked = locks.lockPath(found);
        if (locked.ancestors() != SubprocessExecutionLocks.AncestorState.ACTIVE) return false;
        var application = locked.application();
        if (sqlMapper.escalate(taskId).isEmpty()) return false;
        var task =
                tasks.createTaskQuery()
                        .taskId(taskId)
                        .active()
                        .includeProcessVariables()
                        .includeTaskLocalVariables()
                        .singleResult();
        if (task == null
                || application.status() != ApplicationStatus.IN_APPROVAL
                || !Integer.valueOf(application.roundNo())
                        .equals(task.getProcessVariables().get("roundNo"))) return false;
        var values = task.getTaskLocalVariables();
        if (!(values.get(TaskEscalationBindings.DUE_AT) instanceof Date due)
                || due.toInstant().isAfter(now)
                || values.containsKey(TaskEscalationBindings.ESCALATED_AT)) return false;
        var audience =
                json.read(
                        (String) values.get(TaskEscalationBindings.AUDIENCE),
                        OrganizationAssigneeResolver.Selection.class);
        var active =
                audience.subjects().stream()
                        .filter(subject -> directory.activeRecipient(tenant, subject))
                        .toList();
        if (active.isEmpty()) return false;
        notifications.escalated(application, taskId, task.getName(), active, now);
        tasks.setVariableLocal(
                taskId, TaskEscalationBindings.NOTIFIED_RECIPIENTS, json.write(active));
        tasks.setVariableLocal(taskId, TaskEscalationBindings.ESCALATED_AT, Date.from(now));
        return true;
    }

    /**
     * 有界游标只记录原生任务事实，不提供任务操作权限。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(
            String taskId,
            Instant dueAt,
            String tenantId,
            String traceId,
            String businessNo,
            String processInstanceId) {
        /** 旧扫描不推测业务关联；已知的原生任务编号仍可用于诊断。 */
        public Candidate(String taskId, Instant dueAt, String tenantId, String traceId) { this(taskId, dueAt, tenantId, traceId, null, null); }

        /** 旧扫描候选不从当前请求推测归属或来源。 */
        public Candidate(String taskId, Instant dueAt) { this(taskId, dueAt, null, null); }
    }
}
