package io.agentflow.approval.process;

import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.SubprocessExecutionLocks;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.notification.ApprovalNotificationService;
import org.flowable.engine.TaskService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Flowable 7.2 期限提醒防腐层；任务行锁串行化多实例投递，消息与引擎标记共同提交。
 * @author owlzhangfq@gmail.com
 */
@Service
public class FlowableTaskDeadlineReminders {
    public static final int BATCH_SIZE = 100;
    private final JdbcTemplate jdbc;
    private final TaskService tasks;
    private final ApplicationRepository applications;
    private final ApprovalNotificationService notifications;
    private final SubprocessExecutionLocks executionLocks;

    /** 复用原审批事务和站内通知，不创建第二套任务状态。 */
    public FlowableTaskDeadlineReminders(JdbcTemplate jdbc, TaskService tasks, ApplicationRepository applications,
                                         ApprovalNotificationService notifications, SubprocessExecutionLocks executionLocks) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.applications = applications;
        this.notifications = notifications;
        this.executionLocks = executionLocks;
    }

    /** 游标按到期时刻和任务标识推进，无接收人的任务不会阻塞其他任务。 */
    @Transactional(readOnly = true)
    public List<Candidate> candidates(Instant now, Candidate after) {
        var parameters = new ArrayList<Object>(List.of(Timestamp.from(now),
                FlowableTaskDeadlineListener.CALENDAR_ID, FlowableTaskDeadlineListener.REMINDED_AT));
        var sql = new StringBuilder("""
                SELECT t.ID_, t.DUE_DATE_ FROM ACT_RU_TASK t
                WHERE t.SUSPENSION_STATE_=1 AND t.DUE_DATE_<=?
                AND EXISTS (SELECT 1 FROM ACT_RU_VARIABLE v WHERE v.TASK_ID_=t.ID_ AND v.NAME_=?)
                AND NOT EXISTS (SELECT 1 FROM ACT_RU_VARIABLE v WHERE v.TASK_ID_=t.ID_ AND v.NAME_=?)
                """);
        if (after != null) {
            sql.append(" AND (t.DUE_DATE_>? OR (t.DUE_DATE_=? AND t.ID_>?))");
            parameters.add(Timestamp.from(after.dueAt()));
            parameters.add(Timestamp.from(after.dueAt()));
            parameters.add(after.taskId());
        }
        sql.append(" ORDER BY t.DUE_DATE_,t.ID_ LIMIT ?");
        parameters.add(BATCH_SIZE);
        return jdbc.query(sql.toString(), (row, index) -> new Candidate(row.getString("ID_"),
                row.getTimestamp("DUE_DATE_").toInstant()), parameters.toArray());
    }

    /** 锁后重查真实任务与当前轮次，完成、撤回及旧轮次不再投递。 */
    @Transactional
    public boolean remind(String taskId, Instant now) {
        var initial = tasks.createTaskQuery().taskId(taskId).includeProcessVariables().singleResult();
        if (initial == null) return false;
        var variables = initial.getProcessVariables();
        String tenantId = (String) variables.get("tenantId");
        if (tenantId == null || !(variables.get("applicationId") instanceof String id)) return false;
        // 与审批、暂停和恢复共用申请优先的锁顺序，锁后重新检查任务及其期限。
        var found = applications.findById(tenantId, UUID.fromString(id)).orElse(null);
        if (found == null) return false;
        var locked = executionLocks.lockPath(found);
        if (locked.ancestors() != SubprocessExecutionLocks.AncestorState.ACTIVE) return false;
        var application = locked.application();
        if (jdbc.queryForList("SELECT ID_ FROM ACT_RU_TASK WHERE ID_=? FOR UPDATE", String.class, taskId).isEmpty()) return false;
        var task = tasks.createTaskQuery().taskId(taskId).active().includeProcessVariables().includeTaskLocalVariables().singleResult();
        if (task == null || task.getDueDate() == null || task.getDueDate().toInstant().isAfter(now)
                || !task.getTaskLocalVariables().containsKey(FlowableTaskDeadlineListener.CALENDAR_ID)
                || task.getTaskLocalVariables().containsKey(FlowableTaskDeadlineListener.REMINDED_AT)) return false;
        if (application.status() != ApplicationStatus.IN_APPROVAL
                || application.roundNo() != ((Number) task.getProcessVariables().get("roundNo")).intValue()) return false;
        if (!notifications.overdue(application, taskId, now)) return false;
        tasks.setVariableLocal(taskId, FlowableTaskDeadlineListener.REMINDED_AT, now.toString());
        return true;
    }

    /**
     * 有界扫描游标只描述引擎事实，不提供审批权限。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String taskId, Instant dueAt) { }
}
