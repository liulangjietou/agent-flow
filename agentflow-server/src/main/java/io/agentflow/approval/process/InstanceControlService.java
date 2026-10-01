package io.agentflow.approval.process;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.SubprocessExecutionLocks;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.approval.service.ApplicationAuditPort;
import io.agentflow.calendar.BusinessCalendarRepository;
import io.agentflow.calendar.BusinessDeadline;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.notification.ApprovalNotificationService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 当前审批轮次的具名暂停与恢复；引擎状态、期限、申请版本和审计在同一事务保存。
 * @author owlzhangfq@gmail.com
 */
@Service
public class InstanceControlService {
    public static final String PAUSED_AT = "agentflowInstancePausedAt";
    public static final String PAUSED_DUE_AT = "agentflowDeadlinePauseDueAt";
    private final ApplicationRepository applications;
    private final SubmissionRoundRepository rounds;
    private final ApprovalApplicationFacade reads;
    private final RuntimeService runtime;
    private final TaskService tasks;
    private final BusinessCalendarRepository calendars;
    private final ApplicationAuditPort audit;
    private final ApprovalNotificationService notifications;
    private final CurrentActor actors;
    private final ProcessEngine engine;
    private final SubprocessExecutionLocks executionLocks;

    /** 只协调已绑定的原轮次，不提供替换审批人、跳节点或形成业务结论的能力。 */
    public InstanceControlService(ApplicationRepository applications, SubmissionRoundRepository rounds,
            ApprovalApplicationFacade reads, RuntimeService runtime, TaskService tasks,
            BusinessCalendarRepository calendars, ApplicationAuditPort audit, ApprovalNotificationService notifications,
            CurrentActor actors, ProcessEngine engine, SubprocessExecutionLocks executionLocks) {
        this.applications = applications; this.rounds = rounds; this.reads = reads; this.runtime = runtime;
        this.tasks = tasks; this.calendars = calendars; this.audit = audit; this.notifications = notifications;
        this.actors = actors; this.engine = engine;
        this.executionLocks = executionLocks;
    }

    /** 幂等回放仍检查当前管理员身份，不能借旧回执绕过角色撤销。 */
    public Actor requireAdministrator() { var actor = actors.actor(); actor.requireRole("ADMIN"); return actor; }

    /** 读取继承申请权限；未绑定实例或旧轮次不提供运维操作。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View read(UUID id, int roundNo) {
        var application = reads.get(id);
        var round = rounds.findByRound(application.tenantId(), id, roundNo).orElseThrow(InstanceControlService::unavailable);
        if (round.status() != SubmissionRound.Status.IN_APPROVAL) return view(application, roundNo, State.ENDED, null, false);
        var instance = instance(round.processInstanceId());
        if (!bound(application, round, instance)) return view(application, roundNo, State.UNAVAILABLE, null, false);
        Instant pausedAt = pauseTime(instance);
        return view(application, roundNo, instance.isSuspended() ? State.PAUSED : State.RUNNING, pausedAt,
                application.status() == ApplicationStatus.IN_APPROVAL && roundNo == application.roundNo() && actors.actor().hasRole("ADMIN")
                        && executionLocks.ancestry(application.tenantId(), id).isEmpty());
    }

    /** 暂停保持业务在审及原占用，不创建审批意见，也不改写原生定时等待的到期时刻。 */
    @Transactional
    public View pause(UUID id, int roundNo, Input input) {
        var actor = requireAdministrator(); var binding = lock(actor, id, roundNo);
        var application = binding.application(); var instance = binding.instance();
        if (instance.isSuspended() || pauseTime(instance) != null) throw changed();
        application.recordRuntimeAction(input.expectedVersion());
        var previous = notifications.pendingAudience(application);
        Instant now = now();
        for (var task : tasks.createTaskQuery().processInstanceId(instance.getId()).includeTaskLocalVariables().list()) {
            if (!task.getTaskLocalVariables().containsKey(FlowableTaskDeadlineListener.CALENDAR_ID)) continue;
            if (task.getDueDate() == null || task.getTaskLocalVariables().containsKey(PAUSED_DUE_AT)) throw invalidDeadline();
            tasks.setVariableLocal(task.getId(), PAUSED_DUE_AT, task.getDueDate().toInstant().toString());
        }
        runtime.setVariable(instance.getId(), PAUSED_AT, now.toString());
        runtime.suspendProcessInstanceById(instance.getId());
        save(binding, actor, input, ApplicationAuditPort.Action.INSTANCE_PAUSE);
        notifications.instancePaused(application, actor.userId(), previous);
        return view(application, roundNo, State.PAUSED, now, true);
    }

    /** 恢复沿用每张任务的原日历修订与剩余工时，已经发出的超时提醒不重新投递。 */
    @Transactional
    public View resume(UUID id, int roundNo, Input input) {
        var actor = requireAdministrator(); var binding = lock(actor, id, roundNo);
        var application = binding.application(); var instance = binding.instance();
        Instant pausedAt = pauseTime(instance);
        if (!instance.isSuspended() || pausedAt == null) throw changed();
        application.recordRuntimeAction(input.expectedVersion());
        Instant now = now();
        if (now.isBefore(pausedAt)) throw changed();
        var deadlines = resumedDeadlines(application, instance.getId(), pausedAt, now);
        // 先在事务内激活再更新原任务，引擎不允许修改暂停任务；任何失败均回滚激活及全部期限。
        runtime.activateProcessInstanceById(instance.getId());
        for (var deadline : deadlines) {
            tasks.setDueDate(deadline.taskId(), Date.from(deadline.dueAt()));
            tasks.removeVariableLocal(deadline.taskId(), PAUSED_DUE_AT);
        }
        runtime.removeVariable(instance.getId(), PAUSED_AT);
        save(binding, actor, input, ApplicationAuditPort.Action.INSTANCE_RESUME);
        notifications.instanceResumed(application, actor.userId());
        return view(application, roundNo, State.RUNNING, null, true);
    }

    private List<Deadline> resumedDeadlines(Application application, String instanceId, Instant pausedAt, Instant now) {
        var results = new ArrayList<Deadline>();
        for (var task : tasks.createTaskQuery().processInstanceId(instanceId).includeTaskLocalVariables().list()) {
            var values = task.getTaskLocalVariables();
            if (!values.containsKey(FlowableTaskDeadlineListener.CALENDAR_ID)) continue;
            if (!(values.get(PAUSED_DUE_AT) instanceof String original) || task.getDueDate() == null
                    || !task.getDueDate().toInstant().equals(Instant.parse(original))) throw invalidDeadline();
            var calendar = calendars.findVersion(application.tenantId(), UUID.fromString((String) values.get(FlowableTaskDeadlineListener.CALENDAR_ID)),
                    ((Number) values.get(FlowableTaskDeadlineListener.CALENDAR_REVISION)).longValue())
                    .orElseThrow(() -> new DomainException("DEADLINE_CALENDAR_UNAVAILABLE", "Bound deadline calendar is unavailable"));
            results.add(new Deadline(task.getId(), BusinessDeadline.resume(calendar.rules(), pausedAt, Instant.parse(original), now)));
        }
        return results;
    }

    private Binding lock(Actor actor, UUID id, int roundNo) {
        var initial = applications.findById(actor.tenantId(), id).orElseThrow(InstanceControlService::unavailable);
        executionLocks.requireRoot(initial);
        var application = executionLocks.lock(initial);
        var round = rounds.findByRound(actor.tenantId(), id, roundNo).orElseThrow(InstanceControlService::unavailable);
        var instance = instance(round.processInstanceId());
        if (application.status() != ApplicationStatus.IN_APPROVAL || application.roundNo() != roundNo
                || round.status() != SubmissionRound.Status.IN_APPROVAL || !bound(application, round, instance)) throw unavailable();
        return new Binding(application, round, instance);
    }
    private ProcessInstance instance(String id) { return runtime.createProcessInstanceQuery().processInstanceId(id).includeProcessVariables().singleResult(); }
    private boolean bound(Application application, SubmissionRound round, ProcessInstance instance) {
        if (instance == null) return false;
        var values = instance.getProcessVariables();
        String tenant = instance.getTenantId();
        return (tenant == null || tenant.isEmpty() || tenant.equals(application.tenantId()))
                && application.tenantId().equals(values.get("tenantId")) && application.id().toString().equals(values.get("applicationId"))
                && Integer.valueOf(round.roundNo()).equals(values.get("roundNo"))
                && instance.getProcessDefinitionId().equals(application.runtimeDefinitionId());
    }
    private Instant pauseTime(ProcessInstance instance) {
        Object value = instance.getProcessVariables().get(PAUSED_AT);
        return value instanceof String time ? Instant.parse(time) : null;
    }
    private View view(Application application, int round, State state, Instant pausedAt, boolean administrator) {
        return new View(application.id(), round, application.version(), state, pausedAt,
                administrator && state == State.RUNNING, administrator && state == State.PAUSED && pausedAt != null);
    }
    private void save(Binding binding, Actor actor, Input input, ApplicationAuditPort.Action action) {
        var application = binding.application(); applications.update(application, input.expectedVersion());
        audit.record(new ApplicationAuditPort.ApplicationOperation(application.tenantId(), application.id(), application.version(),
                application.roundNo(), binding.round().processInstanceId(), actor.userId(), action, ApplicationStatus.IN_APPROVAL,
                ApplicationStatus.IN_APPROVAL, input.reason().strip()));
    }
    private Instant now() { return engine.getProcessEngineConfiguration().getClock().getCurrentTime().toInstant(); }
    private static DomainException unavailable() { return new DomainException("NOT_FOUND", "The bound approval instance is not available"); }
    private static DomainException changed() { return new DomainException("CONCURRENCY_CONFLICT", "The instance is not in the required controlled runtime state"); }
    private static DomainException invalidDeadline() { return new DomainException("DEADLINE_STATE_INVALID", "The original bound deadline cannot be recovered"); }

    /** @author owlzhangfq@gmail.com */
    private record Binding(Application application, SubmissionRound round, ProcessInstance instance) { }
    /** @author owlzhangfq@gmail.com */
    private record Deadline(String taskId, Instant dueAt) { }
    /** @author owlzhangfq@gmail.com */
    public enum State { RUNNING, PAUSED, ENDED, UNAVAILABLE }
    /** @author owlzhangfq@gmail.com */
    public record View(UUID applicationId, int roundNo, long applicationVersion, State state, Instant pausedAt, boolean canPause, boolean canResume) { }
    /** @author owlzhangfq@gmail.com */
    public record Input(@NotNull @Positive Long expectedVersion, @NotBlank @Size(max = 2000) String reason) {
        /** 禁止客户端传入计时、审批人或跳转位置。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown instance control field"); }
    }
}
