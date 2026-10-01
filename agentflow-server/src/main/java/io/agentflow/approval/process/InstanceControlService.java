package io.agentflow.approval.process;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.SubprocessExecutionLocks;
import io.agentflow.approval.SubprocessStartService;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.model.SubprocessCall;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.approval.repository.SubprocessCallRepository;
import io.agentflow.approval.service.ApplicationAuditPort;
import io.agentflow.calendar.BusinessCalendarRepository;
import io.agentflow.calendar.BusinessDeadline;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.notification.ApprovalNotificationService;
import io.agentflow.expense.ExpenseReleaseService;
import io.agentflow.procurement.ProcurementPayableReservations;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 当前审批轮次的具名暂停、恢复与终止；引擎、业务资源、版本和审计在同一事务保存。
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
    private final SubprocessCallRepository calls;
    private final ApprovalCompletionService completion;
    private final SubprocessStopService stops;
    private final ExpenseReleaseService expenses;
    private final ProcurementPayableReservations procurement;

    /** 原轮次控制复用既有资源锁与停止联动，不替换审批人或生成批准意见。 */
    public InstanceControlService(ApplicationRepository applications, SubmissionRoundRepository rounds,
            ApprovalApplicationFacade reads, RuntimeService runtime, TaskService tasks,
            BusinessCalendarRepository calendars, ApplicationAuditPort audit, ApprovalNotificationService notifications,
            CurrentActor actors, ProcessEngine engine, SubprocessExecutionLocks executionLocks, SubprocessCallRepository calls,
            ApprovalCompletionService completion, SubprocessStopService stops, ExpenseReleaseService expenses,
            ProcurementPayableReservations procurement) {
        this.applications = applications; this.rounds = rounds; this.reads = reads; this.runtime = runtime;
        this.tasks = tasks; this.calendars = calendars; this.audit = audit; this.notifications = notifications;
        this.actors = actors; this.engine = engine;
        this.executionLocks = executionLocks;
        this.calls = calls;
        this.completion = completion;
        this.stops = stops;
        this.expenses = expenses;
        this.procurement = procurement;
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
        binding.application().checkVersion(input.expectedVersion());
        var family = family(binding);
        for (var item : family) if (item.instance().isSuspended() || pauseTime(item.instance()) != null) throw changed();
        Instant now = now();
        for (var item : family) {
            var application = item.application(); var instance = item.instance();
            long version = application.version(); application.recordRuntimeAction(version);
            var previous = notifications.pendingAudience(application);
            for (var task : tasks.createTaskQuery().processInstanceId(instance.getId()).includeTaskLocalVariables().list()) {
                var values = task.getTaskLocalVariables();
                if (!values.containsKey(FlowableTaskDeadlineListener.CALENDAR_ID)) continue;
                if (task.getDueDate() == null || values.containsKey(PAUSED_DUE_AT)) throw invalidDeadline();
                tasks.setVariableLocal(task.getId(), PAUSED_DUE_AT, task.getDueDate().toInstant().toString());
                if (values.containsKey(TaskEscalationBindings.DUE_AT) && !values.containsKey(TaskEscalationBindings.ESCALATED_AT)) {
                    if (!(values.get(TaskEscalationBindings.DUE_AT) instanceof Date due) || values.containsKey(TaskEscalationBindings.PAUSED_DUE_AT)) throw invalidDeadline();
                    tasks.setVariableLocal(task.getId(), TaskEscalationBindings.PAUSED_DUE_AT, Date.from(due.toInstant()));
                }
            }
            runtime.setVariable(instance.getId(), PAUSED_AT, now.toString());
            runtime.suspendProcessInstanceById(instance.getId());
            String operator = save(item, id, actor, input, ApplicationAuditPort.Action.INSTANCE_PAUSE, version);
            notifications.instancePaused(application, operator, previous);
        }
        return view(binding.application(), roundNo, State.PAUSED, now, true);
    }

    /** 恢复沿用每张任务的原日历修订与剩余工时，已经发出的超时提醒不重新投递。 */
    @Transactional
    public View resume(UUID id, int roundNo, Input input) {
        var actor = requireAdministrator(); var binding = lock(actor, id, roundNo);
        binding.application().checkVersion(input.expectedVersion());
        var family = family(binding);
        Instant pausedAt = pauseTime(binding.instance());
        if (pausedAt == null) throw changed();
        for (var item : family) if (!item.instance().isSuspended() || !pausedAt.equals(pauseTime(item.instance()))) throw changed();
        Instant now = now();
        if (now.isBefore(pausedAt)) throw changed();
        for (var item : family) {
            var application = item.application(); var instance = item.instance();
            long version = application.version(); application.recordRuntimeAction(version);
            var deadlines = resumedDeadlines(application, instance.getId(), pausedAt, now);
            // 先在事务内激活再更新原任务，引擎不允许修改暂停任务；失败会回滚整棵树的激活及期限。
            runtime.activateProcessInstanceById(instance.getId());
            for (var deadline : deadlines) {
                tasks.setDueDate(deadline.taskId(), Date.from(deadline.dueAt()));
                tasks.removeVariableLocal(deadline.taskId(), PAUSED_DUE_AT);
                if (deadline.escalationDueAt() != null) {
                    tasks.setVariableLocal(deadline.taskId(), TaskEscalationBindings.DUE_AT, Date.from(deadline.escalationDueAt()));
                    tasks.removeVariableLocal(deadline.taskId(), TaskEscalationBindings.PAUSED_DUE_AT);
                }
            }
            runtime.removeVariable(instance.getId(), PAUSED_AT);
            String operator = save(item, id, actor, input, ApplicationAuditPort.Action.INSTANCE_RESUME, version);
            notifications.instanceResumed(application, operator);
        }
        return view(binding.application(), roundNo, State.RUNNING, null, true);
    }

    /** 终止运行或暂停中的原轮次，保留已有意见，并按实际财务状态释放或继续对账。 */
    @Transactional
    public View terminate(UUID id, int roundNo, Input input) {
        var actor = requireAdministrator();
        var binding = lock(actor, id, roundNo);
        var application = binding.application();
        application.checkVersion(input.expectedVersion());
        completion.lock(application);
        var plan = stops.before(application);
        var audience = notifications.unfinishedAudience(application);
        String instanceId = binding.round().processInstanceId();
        String reason = input.reason().strip();
        Instant completedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        application.terminateApproval(input.expectedVersion());
        // 引擎删除原因会传入后代历史，因此只包含来源编号；具名自由文本仅保存在根轮次和根审计。
        runtime.deleteProcessInstance(plan.instanceToStop(instanceId), "Approval terminated through root application " + id);
        var history = engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(instanceId).singleResult();
        if (runtime.createProcessInstanceQuery().processInstanceId(instanceId).count() != 0
                || history == null || history.getEndTime() == null || history.getDeleteReason() == null) throw unavailable();
        applications.update(application, input.expectedVersion());
        rounds.complete(application.tenantId(), id, roundNo, instanceId, SubmissionRound.Status.CANCELLED, reason, actor.userId(), completedAt);
        audit.record(new ApplicationAuditPort.ApplicationOperation(application.tenantId(), id, application.version(), roundNo,
                instanceId, actor.userId(), ApplicationAuditPort.Action.INSTANCE_TERMINATE, ApplicationStatus.IN_APPROVAL,
                ApplicationStatus.CANCELLED, reason));
        stops.after(plan, application, actor.userId());
        expenses.release(application, actor.userId(), completedAt);
        procurement.releaseStopped(application, actor.userId(), completedAt);
        notifications.instanceTerminated(application, actor.userId(), audience);
        return view(application, roundNo, State.ENDED, null, false);
    }

    private List<Binding> family(Binding root) {
        var active = new ArrayList<Binding>();
        collect(root, true, new HashSet<>(), active);
        return active;
    }

    /** 根锁串行化整棵原调用树；已结束分支只核对绑定，不能重新暂停或产生运维审计。 */
    private void collect(Binding binding, boolean ancestorsActive, Set<UUID> seen, List<Binding> active) {
        var application = binding.application(); var round = binding.round();
        if (!seen.add(application.id()) || application.roundNo() != round.roundNo()
                || application.definitionVersion() != round.definitionVersion() || !application.status().name().equals(round.status().name())) throw unavailable();
        boolean running = application.status() == ApplicationStatus.IN_APPROVAL;
        if (running) {
            if (!ancestorsActive || !bound(application, round, binding.instance())) throw unavailable();
            active.add(binding);
        } else if (binding.instance() != null) throw unavailable();
        for (var call : calls.findByParentRound(application.tenantId(), application.id(), round.roundNo())) {
            if (!call.parentProcessInstanceId().equals(round.processInstanceId())
                    || !call.parentRuntimeDefinitionId().equals(application.runtimeDefinitionId())) throw unavailable();
            var child = applications.lockById(application.tenantId(), call.childApplicationId()).orElseThrow(InstanceControlService::unavailable);
            var childRound = rounds.findByRound(application.tenantId(), child.id(), SubprocessCall.CHILD_ROUND).orElseThrow(InstanceControlService::unavailable);
            if (!call.childRuntimeDefinitionId().equals(child.runtimeDefinitionId()) || !call.childProcessInstanceId().equals(childRound.processInstanceId())
                    || call.policy().version() != child.definitionVersion() || !call.policy().processKey().equals(child.processKey())) throw unavailable();
            collect(new Binding(child, childRound, instance(childRound.processInstanceId())), ancestorsActive && running, seen, active);
        }
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
            Instant escalationDue = null;
            if (values.containsKey(TaskEscalationBindings.DUE_AT) && !values.containsKey(TaskEscalationBindings.ESCALATED_AT)) {
                if (!(values.get(TaskEscalationBindings.PAUSED_DUE_AT) instanceof Date previous)
                        || !previous.equals(values.get(TaskEscalationBindings.DUE_AT))) throw invalidDeadline();
                // 审批已超时也可能仍有升级等待时间，必须独立接续，不能从恢复后的审批期限重算全额。
                escalationDue = BusinessDeadline.resume(calendar.rules(), pausedAt, previous.toInstant(), now);
            }
            results.add(new Deadline(task.getId(), BusinessDeadline.resume(calendar.rules(), pausedAt, Instant.parse(original), now), escalationDue));
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
                administrator && state == State.RUNNING, administrator && state == State.PAUSED && pausedAt != null,
                administrator && (state == State.RUNNING || state == State.PAUSED));
    }
    private String save(Binding binding, UUID rootId, Actor actor, Input input, ApplicationAuditPort.Action action, long previousVersion) {
        var application = binding.application(); applications.update(application, previousVersion);
        boolean root = application.id().equals(rootId);
        String operator = root ? actor.userId() : SubprocessStartService.SYSTEM_ACTOR;
        // 原具名原因只保存在根申请；子申请用系统身份与原调用来源解释联动，不外传父表单或自由文本。
        String reason = root ? input.reason().strip() : "Subprocess runtime controlled through root application " + rootId + ", action=" + action;
        audit.record(new ApplicationAuditPort.ApplicationOperation(application.tenantId(), application.id(), application.version(),
                application.roundNo(), binding.round().processInstanceId(), operator, action, ApplicationStatus.IN_APPROVAL,
                ApplicationStatus.IN_APPROVAL, reason));
        return operator;
    }
    private Instant now() { return engine.getProcessEngineConfiguration().getClock().getCurrentTime().toInstant(); }
    private static DomainException unavailable() { return new DomainException("NOT_FOUND", "The bound approval instance is not available"); }
    private static DomainException changed() { return new DomainException("CONCURRENCY_CONFLICT", "The instance is not in the required controlled runtime state"); }
    private static DomainException invalidDeadline() { return new DomainException("DEADLINE_STATE_INVALID", "The original bound deadline cannot be recovered"); }

    /** @author owlzhangfq@gmail.com */
    private record Binding(Application application, SubmissionRound round, ProcessInstance instance) { }
    /** @author owlzhangfq@gmail.com */
    private record Deadline(String taskId, Instant dueAt, Instant escalationDueAt) { }
    /** @author owlzhangfq@gmail.com */
    public enum State { RUNNING, PAUSED, ENDED, UNAVAILABLE }
    /** @author owlzhangfq@gmail.com */
    public record View(UUID applicationId, int roundNo, long applicationVersion, State state, Instant pausedAt, boolean canPause, boolean canResume, boolean canTerminate) { }
    /** @author owlzhangfq@gmail.com */
    public record Input(@NotNull @Positive Long expectedVersion, @NotBlank @Size(max = 2000) String reason) {
        /** 禁止客户端传入计时、审批人或跳转位置。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown instance control field"); }
    }
}
