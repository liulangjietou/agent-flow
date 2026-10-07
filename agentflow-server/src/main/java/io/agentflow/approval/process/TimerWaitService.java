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
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionDraftRepository;
import io.agentflow.definition.DefinitionModels;
import io.agentflow.notification.ApprovalNotificationService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.job.api.Job;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 单次定时等待的应用编排；真实任务、到期与失败状态均由 Flowable 持久化。
 * @author owlzhangfq@gmail.com
 */
@Service
public class TimerWaitService {
    public static final int BATCH_SIZE = 100;
    public static final String SYSTEM_ACTOR = "system:timer";
    private static final String EXECUTION_FAILED = "TIMER_EXECUTION_FAILED";
    private final ManagementService jobs;
    private final RuntimeService runtime;
    private final ApplicationRepository applications;
    private final SubmissionRoundRepository rounds;
    private final DefinitionDraftRepository definitions;
    private final ApprovalApplicationFacade reads;
    private final ApprovalCompletionService completion;
    private final SubprocessProgressService subprocesses;
    private final ApprovalNotificationService notifications;
    private final ApplicationAuditPort audit;
    private final CurrentActor actors;
    private final JdbcTemplate jdbc;

    /** 原生异步执行器不得绕过申请事务；等待调度始终经本用例推进。 */
    public TimerWaitService(ManagementService jobs, RuntimeService runtime,
            ApplicationRepository applications, SubmissionRoundRepository rounds, DefinitionDraftRepository definitions,
            ApprovalApplicationFacade reads, ApprovalCompletionService completion, ApprovalNotificationService notifications,
            ApplicationAuditPort audit, CurrentActor actors, JdbcTemplate jdbc, SubprocessProgressService subprocesses,
            @Value("${flowable.async-executor-activate:false}") boolean nativeAsync) {
        if (nativeAsync) throw new IllegalStateException("Timer waits require the platform dispatcher and flowable.async-executor-activate=false");
        this.jobs = jobs; this.runtime = runtime; this.applications = applications;
        this.rounds = rounds; this.definitions = definitions; this.reads = reads; this.completion = completion;
        this.notifications = notifications; this.audit = audit; this.actors = actors; this.jdbc = jdbc;
        this.subprocesses = subprocesses;
    }

    /** 到期与标识共同分页，无关或不可执行的引擎任务不会阻塞后续页面。 */
    @Transactional(readOnly = true)
    public List<Candidate> candidates(Instant now, Candidate after) {
        StringBuilder sql = new StringBuilder("""
                SELECT j.ID_,j.DUEDATE_,j.TENANT_ID_,o.trace_id FROM ACT_RU_TIMER_JOB j
                LEFT JOIN workflow_execution_origin o ON o.tenant_id=j.TENANT_ID_ AND o.object_kind='TIMER' AND o.object_id=j.ID_
                WHERE j.DUEDATE_<=?
                """);
        var parameters = new ArrayList<Object>(); parameters.add(Timestamp.from(now));
        if (after != null) {
            sql.append(" AND (j.DUEDATE_>? OR (j.DUEDATE_=? AND j.ID_>?))");
            parameters.add(Timestamp.from(after.dueAt())); parameters.add(Timestamp.from(after.dueAt())); parameters.add(after.jobId());
        }
        sql.append(" ORDER BY j.DUEDATE_,j.ID_ LIMIT ?"); parameters.add(BATCH_SIZE);
        return jdbc.query(sql.toString(), (row, index) -> new Candidate(row.getString("ID_"), row.getTimestamp("DUEDATE_").toInstant(), row.getString("TENANT_ID_"), row.getString("trace_id")), parameters.toArray());
    }

    /** 到期后只推进精确绑定的等待执行，提前、暂停、已撤回或已经消费时不执行。 */
    @Transactional
    public boolean advance(String jobId, Instant now) {
        var initial = jobs.createTimerJobQuery().jobId(jobId).singleResult();
        var binding = lock(initial);
        var job = jobs.createTimerJobQuery().jobId(jobId).singleResult();
        if (binding == null || job == null || binding.suspended() || job.getDuedate() == null || job.getDuedate().toInstant().isAfter(now)) return false;
        advance(binding, job, false, binding.application().version(), SYSTEM_ACTOR, "Timer wait elapsed");
        return true;
    }

    /** 推进事务回滚后另起事务，将仍属于同轮的原等待标记失败；不丢弃等待或自动跳过。 */
    @Transactional
    public void failed(String jobId, RuntimeException failure) {
        var binding = lock(jobs.createTimerJobQuery().jobId(jobId).singleResult());
        var job = jobs.createTimerJobQuery().jobId(jobId).singleResult();
        if (binding == null || job == null || binding.suspended()) return;
        String code = failureCode(failure);
        var executable = jobs.moveTimerToExecutableJob(jobId);
        // 只存业务错误码，不将异常中的表单、账号或外部响应复制到引擎和查询接口。
        jobs.executeCommand(context -> {
            var entity = CommandContextUtil.getJobService(context).findJobById(executable.getId());
            entity.setExceptionMessage(code); entity.setExceptionStacktrace("errorCode=" + code);
            CommandContextUtil.getJobService(context).updateJob(entity); return null;
        });
        jobs.moveJobToDeadLetterJob(executable.getId());
        var application = binding.application(); long previous = application.version();
        application.recordRuntimeAction(previous); applications.update(application, previous);
        record(binding, SYSTEM_ACTOR, ApplicationAuditPort.Action.TIMER_FAILED, "nodeId=" + binding.node().id() + ", errorCode=" + code);
    }

    /** 成功回放仍要求当前管理员身份，历史回执不重新执行已结束的等待。 */
    public Actor requireAdministrator() { var actor = actors.actor(); actor.requireRole("ADMIN"); return actor; }

    /** 管理员显式说明原因，只重试当前轮次的原失败任务，不接受新时长或新节点。 */
    @Transactional
    public Receipt retry(UUID applicationId, int roundNo, String jobId, RetryInput input) {
        Actor actor = requireAdministrator();
        var initial = jobs.createDeadLetterJobQuery().jobId(jobId).singleResult();
        if (initial == null || !actor.tenantId().equals(initial.getTenantId())) throw unavailable();
        var binding = lock(initial);
        var job = jobs.createDeadLetterJobQuery().jobId(jobId).singleResult();
        if (binding == null || job == null || !binding.application().id().equals(applicationId)
                || binding.application().roundNo() != roundNo || binding.suspended()) throw unavailable();
        if (job.getDuedate() == null || job.getDuedate().toInstant().isAfter(Instant.now())) {
            throw new DomainException("TIMER_NOT_DUE", "The original timer wait is not due");
        }
        return advance(binding, job, true, input.expectedVersion(), actor.userId(), input.reason().strip());
    }

    /** 复用申请读取权限；只投影该历史轮次的实际等待，不返回引擎变量或异常原文。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View read(UUID id, int roundNo) {
        var application = reads.get(id);
        var round = rounds.findByRound(application.tenantId(), id, roundNo).orElseThrow(TimerWaitService::unavailable);
        var entries = new ArrayList<Entry>();
        append(entries, jobs.createTimerJobQuery().processInstanceId(round.processInstanceId()).list(), application, round, State.WAITING);
        append(entries, jobs.createDeadLetterJobQuery().processInstanceId(round.processInstanceId()).list(), application, round, State.FAILED);
        append(entries, jobs.createSuspendedJobQuery().processInstanceId(round.processInstanceId()).list(), application, round, State.SUSPENDED);
        entries.sort(Comparator.comparing(Entry::dueAt).thenComparing(Entry::jobId));
        return new View(id, roundNo, application.version(), List.copyOf(entries));
    }

    private void append(List<Entry> entries, List<Job> source, Application application, SubmissionRound round, State state) {
        var definition = definitions.findPublished(application.tenantId(), application.processKey(), round.definitionVersion()).orElse(null);
        if (definition == null) return;
        var instance = runtime.createProcessInstanceQuery().processInstanceId(round.processInstanceId()).singleResult();
        for (var job : source) {
            var node = definition.graph().nodes().stream().filter(n -> n.id().equals(job.getElementId()) && n.type() == DefinitionModels.NodeType.TIMER_WAIT).findFirst().orElse(null);
            if (node == null || job.getDuedate() == null || !application.tenantId().equals(job.getTenantId())
                    || !job.getProcessDefinitionId().equals(application.runtimeDefinitionId())) continue;
            boolean canRetry = state == State.FAILED && actors.actor().hasRole("ADMIN") && application.status() == ApplicationStatus.IN_APPROVAL
                    && application.roundNo() == round.roundNo() && round.status() == SubmissionRound.Status.IN_APPROVAL
                    && instance != null && !instance.isSuspended() && !job.getDuedate().toInstant().isAfter(Instant.now());
            entries.add(new Entry(job.getId(), node.id(), node.name(), job.getExecutionId(), job.getDuedate().toInstant(), state,
                    state == State.FAILED ? safeCode(job.getExceptionMessage()) : null, canRetry));
        }
    }

    private Binding lock(Job job) {
        if (job == null || job.getProcessInstanceId() == null) return null;
        var initial = runtime.createProcessInstanceQuery().processInstanceId(job.getProcessInstanceId()).includeProcessVariables().singleResult();
        if (initial == null || !(initial.getProcessVariables().get("applicationId") instanceof String id)) return null;
        String tenant = initial.getTenantId();
        if (tenant == null || !tenant.equals(job.getTenantId()) || !tenant.equals(initial.getProcessVariables().get("tenantId"))) return null;
        Application found;
        try { found = applications.findById(tenant, UUID.fromString(id)).orElse(null); }
        catch (IllegalArgumentException invalid) { return null; }
        if (found == null) return null;
        var locked = completion.lockForProgress(found);
        if (locked.ancestors() == SubprocessExecutionLocks.AncestorState.STALE) return null;
        var application = locked.application();
        var instance = runtime.createProcessInstanceQuery().processInstanceId(job.getProcessInstanceId()).includeProcessVariables().singleResult();
        var round = rounds.findByRound(tenant, application.id(), application.roundNo()).orElse(null);
        if (instance == null || application.status() != ApplicationStatus.IN_APPROVAL || round == null
                || round.status() != SubmissionRound.Status.IN_APPROVAL || !round.processInstanceId().equals(instance.getId())
                || !job.getProcessDefinitionId().equals(application.runtimeDefinitionId())
                || !job.getProcessDefinitionId().equals(instance.getProcessDefinitionId())
                || !Integer.valueOf(application.roundNo()).equals(instance.getProcessVariables().get("roundNo"))) return null;
        var definition = definitions.findPublished(tenant, application.processKey(), application.definitionVersion()).orElse(null);
        var node = definition == null ? null : definition.graph().nodes().stream()
                .filter(n -> n.id().equals(job.getElementId()) && n.type() == DefinitionModels.NodeType.TIMER_WAIT).findFirst().orElse(null);
        var execution = runtime.createExecutionQuery().executionId(job.getExecutionId()).singleResult();
        if (node == null || execution == null || !node.id().equals(execution.getActivityId())) return null;
        return new Binding(application, round, node, instance.isSuspended()
                || locked.ancestors() == SubprocessExecutionLocks.AncestorState.PAUSED);
    }

    private Receipt advance(Binding binding, Job job, boolean retry, long expectedVersion, String actor, String reason) {
        var before = subprocesses.before(binding.application());
        var application = binding.application(); application.recordRuntimeAction(expectedVersion);
        var previousTasks = notifications.pendingTaskIds(application);
        var executable = retry ? jobs.moveDeadLetterJobToExecutableJob(job.getId(), 1) : jobs.moveTimerToExecutableJob(job.getId());
        jobs.executeJob(executable.getId());
        boolean ended = runtime.createProcessInstanceQuery().processInstanceId(binding.round().processInstanceId()).singleResult() == null;
        if (ended && !subprocesses.hasApprovalEvidence(application, binding.round().processInstanceId())) {
            throw new DomainException("TIMER_REQUIRES_APPROVAL", "A timer wait cannot substitute for human approval");
        }
        completion.persistProgress(application, expectedVersion, binding.round().processInstanceId(), ended, actor, reason);
        record(binding, actor, retry ? ApplicationAuditPort.Action.TIMER_RETRY : ApplicationAuditPort.Action.TIMER_ELAPSED, reason);
        subprocesses.afterAdvance(before, application);
        notifications.processAdvanced(application, actor, null, binding.node().name(), previousTasks);
        return new Receipt(application.id(), application.roundNo(), application.version(), job.getId(), binding.node().id(), application.status());
    }

    private void record(Binding binding, String actor, ApplicationAuditPort.Action action, String reason) {
        var app = binding.application();
        audit.record(new ApplicationAuditPort.ApplicationOperation(app.tenantId(), app.businessNo(), app.id(), app.version(), app.roundNo(),
                binding.round().processInstanceId(), actor, action, ApplicationStatus.IN_APPROVAL, app.status(), reason));
    }

    private static String failureCode(RuntimeException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof DomainException domain) return safeCode(domain.code());
            if (current == current.getCause()) break;
            current = current.getCause();
        }
        return EXECUTION_FAILED;
    }
    private static String safeCode(String code) { return code != null && code.matches("[A-Z][A-Z0-9_]{0,79}") ? code : EXECUTION_FAILED; }
    private static DomainException unavailable() { return new DomainException("NOT_FOUND", "The bound timer wait is not available"); }

    /** @author owlzhangfq@gmail.com */
    private record Binding(Application application, SubmissionRound round, DefinitionModels.Node node, boolean suspended) { }
    /** @author owlzhangfq@gmail.com */
    public record Candidate(String jobId, Instant dueAt, String tenantId, String traceId) {
        /** 历史等待仍按原到期和原 ID 执行，缺失来源保持空值。 */
        public Candidate(String jobId, Instant dueAt) { this(jobId, dueAt, null, null); }
    }
    /** @author owlzhangfq@gmail.com */
    public enum State { WAITING, FAILED, SUSPENDED }
    /** @author owlzhangfq@gmail.com */
    public record Entry(String jobId, String nodeId, String nodeName, String executionId, Instant dueAt, State state, String errorCode, boolean canRetry) { }
    /** @author owlzhangfq@gmail.com */
    public record View(UUID applicationId, int roundNo, long applicationVersion, List<Entry> items) { }
    /** @author owlzhangfq@gmail.com */
    public record Receipt(UUID applicationId, int roundNo, long applicationVersion, String jobId, String nodeId, ApplicationStatus applicationStatus) { }
    /** @author owlzhangfq@gmail.com */
    public record RetryInput(@NotNull @Positive Long expectedVersion, @NotBlank @Size(max = 2000) String reason) {
        /** 原等待的版本、节点和到期时间不能由请求替换。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown timer retry field"); }
    }
}
