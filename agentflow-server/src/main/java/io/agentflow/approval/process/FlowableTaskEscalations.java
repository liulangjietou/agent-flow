package io.agentflow.approval.process;

import io.agentflow.approval.SubprocessExecutionLocks;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.JsonUtil;
import io.agentflow.notification.ApprovalNotificationService;
import io.agentflow.organization.LocalOrganizationDirectory;
import io.agentflow.organization.OrganizationAssigneeResolver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.flowable.engine.TaskService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 升级只投递原名单的协调通知，与原任务及父子轮次共用事务和锁。
 * @author owlzhangfq@gmail.com
 */
@Service
public class FlowableTaskEscalations {
    public static final int BATCH_SIZE = 100;
    private final JdbcTemplate jdbc;
    private final TaskService tasks;
    private final ApplicationRepository applications;
    private final SubprocessExecutionLocks locks;
    private final LocalOrganizationDirectory directory;
    private final ApprovalNotificationService notifications;
    private final JsonUtil json;

    /** 当前人员状态可以阻止投递，但不能把原名单替换成新组织成员。 */
    public FlowableTaskEscalations(JdbcTemplate jdbc, TaskService tasks, ApplicationRepository applications,
            SubprocessExecutionLocks locks, LocalOrganizationDirectory directory, ApprovalNotificationService notifications, JsonUtil json) {
        this.jdbc = jdbc; this.tasks = tasks; this.applications = applications; this.locks = locks;
        this.directory = directory; this.notifications = notifications; this.json = json;
    }

    /** 使用日期变量的毫秒列扫描，不按字符串时间比较，也不扫描暂停或已经升级的任务。 */
    @Transactional(readOnly = true)
    public List<Candidate> candidates(Instant now, Candidate after) {
        var parameters = new ArrayList<Object>(List.of(TaskEscalationBindings.DUE_AT, now.toEpochMilli(), TaskEscalationBindings.ESCALATED_AT));
        var sql = new StringBuilder("""
                SELECT t.ID_,v.LONG_ FROM ACT_RU_TASK t JOIN ACT_RU_VARIABLE v ON v.TASK_ID_=t.ID_
                WHERE t.SUSPENSION_STATE_=1 AND v.NAME_=? AND v.TYPE_='date' AND v.LONG_<=?
                AND NOT EXISTS (SELECT 1 FROM ACT_RU_VARIABLE marker WHERE marker.TASK_ID_=t.ID_ AND marker.NAME_=?)
                """);
        if (after != null) {
            sql.append(" AND (v.LONG_>? OR (v.LONG_=? AND t.ID_>?))");
            parameters.add(after.dueAt().toEpochMilli()); parameters.add(after.dueAt().toEpochMilli()); parameters.add(after.taskId());
        }
        sql.append(" ORDER BY v.LONG_,t.ID_ LIMIT ?"); parameters.add(BATCH_SIZE);
        return jdbc.query(sql.toString(), (row, index) -> new Candidate(row.getString("ID_"), Instant.ofEpochMilli(row.getLong("LONG_"))), parameters.toArray());
    }

    /** 申请与原任务锁后重查；完成、撤回、终止及父子暂停不能靠先前扫描结果继续投递。 */
    @Transactional
    public boolean escalate(String taskId, Instant now) {
        var initial = tasks.createTaskQuery().taskId(taskId).includeProcessVariables().singleResult();
        if (initial == null) return false;
        var variables = initial.getProcessVariables();
        if (!(variables.get("tenantId") instanceof String tenant) || !(variables.get("applicationId") instanceof String id)) return false;
        var found = applications.findById(tenant, UUID.fromString(id)).orElse(null);
        if (found == null) return false;
        var locked = locks.lockPath(found);
        if (locked.ancestors() != SubprocessExecutionLocks.AncestorState.ACTIVE) return false;
        var application = locked.application();
        if (jdbc.queryForList("SELECT ID_ FROM ACT_RU_TASK WHERE ID_=? FOR UPDATE", String.class, taskId).isEmpty()) return false;
        var task = tasks.createTaskQuery().taskId(taskId).active().includeProcessVariables().includeTaskLocalVariables().singleResult();
        if (task == null || application.status() != ApplicationStatus.IN_APPROVAL
                || !Integer.valueOf(application.roundNo()).equals(task.getProcessVariables().get("roundNo"))) return false;
        var values = task.getTaskLocalVariables();
        if (!(values.get(TaskEscalationBindings.DUE_AT) instanceof Date due) || due.toInstant().isAfter(now)
                || values.containsKey(TaskEscalationBindings.ESCALATED_AT)) return false;
        var audience = json.read((String) values.get(TaskEscalationBindings.AUDIENCE), OrganizationAssigneeResolver.Selection.class);
        var active = audience.subjects().stream().filter(subject -> directory.activeRecipient(tenant, subject)).toList();
        if (active.isEmpty()) return false;
        notifications.escalated(application, taskId, task.getName(), active, now);
        tasks.setVariableLocal(taskId, TaskEscalationBindings.NOTIFIED_RECIPIENTS, json.write(active));
        tasks.setVariableLocal(taskId, TaskEscalationBindings.ESCALATED_AT, Date.from(now));
        return true;
    }

    /** 有界游标只记录原生任务事实，不提供任务操作权限。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String taskId, Instant dueAt) { }
}
