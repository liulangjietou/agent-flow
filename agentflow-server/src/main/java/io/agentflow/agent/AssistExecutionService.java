package io.agentflow.agent;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Agent 跨聚合应用编排；所有方法只做短事务，不在锁内请求模型。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AssistExecutionService {
    private final ApprovalApplicationFacade applications;
    private final ApplicationRepository applicationRepository;
    private final CurrentActor actors;
    private final AssistInputService inputs;
    private final AssistRunRepository runs;
    private final JdbcAssistJobRepository jobs;
    private final AssistConfiguration configuration;
    private final JdbcTemplate jdbc;

    /** 复用审批授权与领域运行，不建立第二套申请或审批状态。 */
    public AssistExecutionService(ApprovalApplicationFacade applications, ApplicationRepository applicationRepository,
            CurrentActor actors, AssistInputService inputs, AssistRunRepository runs, JdbcAssistJobRepository jobs,
            AssistConfiguration configuration, JdbcTemplate jdbc) {
        this.applications = applications; this.applicationRepository = applicationRepository; this.actors = actors;
        this.inputs = inputs; this.runs = runs; this.jobs = jobs; this.configuration = configuration; this.jdbc = jdbc;
    }

    /** 输入预览只给当前决策待办，所有来源默认不选。 */
    @Transactional(readOnly = true)
    public InputOptions input(UUID applicationId, String taskId) {
        var application = applications.get(applicationId);
        inputs.requireDecision(application, taskId, actors.actor());
        String unavailable = null;
        try { configuration.requireAvailable(); } catch (DomainException failure) { unavailable = failure.code(); }
        return new InputOptions(application.version(), unavailable == null, unavailable,
                unavailable == null ? configuration.getProviderId() : null, unavailable == null ? configuration.getModel() : null,
                unavailable == null ? configuration.uri().getAuthority() : null,
                unavailable == null ? configuration.targetDigest() : null, inputs.available(application, application.roundNo()));
    }

    /** 幂等层只保存不含正文的运行回执，回放不会返回已失权的摘要或来源。 */
    @Transactional
    public Receipt queue(UUID applicationId, String taskId, long expectedVersion, String targetDigest, List<String> sourceIds) {
        configuration.requireAvailable();
        if (!configuration.targetDigest().equals(targetDigest)) throw new DomainException("AGENT_TARGET_CHANGED", "Refresh the model destination before selecting input");
        lockApplication(actors.actor().tenantId(), applicationId);
        var application = applications.get(applicationId);
        requireVersion(application, expectedVersion);
        inputs.requireDecision(application, taskId, actors.actor());
        var sources = inputs.select(application, sourceIds);
        Integer active = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_assist_run r
                JOIN agent_assist_job j ON j.tenant_id=r.tenant_id AND j.run_id=r.id
                WHERE r.tenant_id=? AND r.application_id=? AND r.status IN ('QUEUED','RUNNING')
                """,
                Integer.class, application.tenantId(), applicationId.toString());
        if (active != null && active > 0) throw new DomainException("AGENT_RUN_ACTIVE", "An assist run is already active for this application");
        var run = AssistRun.queue(UUID.randomUUID(), application.tenantId(), actors.actor().userId(), Instant.now(),
                new AssistInput(applicationId, application.version(), application.roundNo(), sources.stream().map(AssistModelPort.Source::reference).toList()),
                AssistConfiguration.PROMPT_VERSION);
        runs.create(run);
        jobs.create(new JdbcAssistJobRepository.Job(run.tenantId(), run.id(), taskId, actors.actor(), sources, configuration.targetDigest(), null));
        return receipt(run);
    }

    /** 采纳与拒绝只追加人工意见；原模型建议和审批申请均不改变。 */
    @Transactional
    public Receipt review(UUID applicationId, UUID runId, String taskId, long expectedVersion, long expectedRunVersion,
                          ReviewAction action, String acceptedText, String comment) {
        lockApplication(actors.actor().tenantId(), applicationId);
        var application = applications.get(applicationId);
        requireVersion(application, expectedVersion);
        inputs.requireDecision(application, taskId, actors.actor());
        var run = requireRun(application.tenantId(), runId);
        if (!run.input().applicationId().equals(applicationId) || run.input().roundNo() != application.roundNo()) throw notFound();
        var job = jobs.find(application.tenantId(), runId).orElseThrow(AssistExecutionService::notFound);
        inputs.requireReadable(application, run, job.sources());
        if (action == ReviewAction.ADOPT) run.adopt(expectedRunVersion, application.version(), actors.actor().userId(), acceptedText, comment, Instant.now());
        else run.dismiss(expectedRunVersion, actors.actor().userId(), comment, Instant.now());
        runs.update(run, expectedRunVersion);
        return receipt(run);
    }

    /** 领取时锁运行并复核当前版本和任务；过期执行直接记录失败，不再次外发。 */
    @Transactional
    public JdbcAssistJobRepository.Job claim(String tenant, UUID id, Instant now) {
        if (!jobs.lock(tenant, id)) return null;
        var run = requireRun(tenant, id);
        var job = jobs.find(tenant, id).orElseThrow();
        if (run.status() == AssistRun.Status.RUNNING) {
            if (job.leaseUntil() != null && !job.leaseUntil().isAfter(now)) finishFailure(run, AssistRun.Failure.MODEL_TIMEOUT, now);
            return null;
        }
        if (run.status() != AssistRun.Status.QUEUED) return null;
        run.start(run.version(), now); runs.update(run, run.version() - 1);
        try {
            configuration.requireAvailable();
            if (!configuration.targetDigest().equals(job.targetDigest())) throw new DomainException("AGENT_TARGET_CHANGED", "Model target changed");
        } catch (DomainException unavailable) { finishFailure(run, AssistRun.Failure.MODEL_UNAVAILABLE, now); return null; }
        try {
            var application = applicationRepository.findById(tenant, run.input().applicationId()).orElseThrow(AssistExecutionService::notFound);
            requireVersion(application, run.input().applicationVersion());
            if (application.roundNo() != run.input().roundNo() || !tenant.equals(job.requester().tenantId())) throw notFound();
            inputs.requireDecision(application, job.taskId(), job.requester());
        } catch (DomainException unavailable) { finishFailure(run, AssistRun.Failure.INPUT_UNAVAILABLE, now); return null; }
        jobs.lease(tenant, id, now.plusSeconds(configuration.getTimeoutSeconds() + 30L));
        return job;
    }

    /** 晚到结果不能覆盖已过期失败或已复核结果；结果校验失败也保留明确失败状态。 */
    @Transactional
    public void finish(JdbcAssistJobRepository.Job job, AssistSuggestion result, AssistRun.Failure failure, Instant now) {
        if (!jobs.lock(job.tenantId(), job.runId())) return;
        var run = requireRun(job.tenantId(), job.runId());
        if (run.status() != AssistRun.Status.RUNNING) return;
        var currentJob = jobs.find(job.tenantId(), job.runId()).orElseThrow();
        if (currentJob.leaseUntil() == null || !currentJob.leaseUntil().isAfter(now)) {
            finishFailure(run, AssistRun.Failure.MODEL_TIMEOUT, now); return;
        }
        if (failure != null) { finishFailure(run, failure, now); return; }
        try { run.complete(run.version(), result, now); }
        catch (DomainException invalid) { finishFailure(run, AssistRun.Failure.INVALID_MODEL_OUTPUT, now); return; }
        runs.update(run, run.version() - 1);
        jobs.lease(job.tenantId(), job.runId(), null);
    }

    private void finishFailure(AssistRun run, AssistRun.Failure failure, Instant now) {
        run.fail(run.version(), failure, now); runs.update(run, run.version() - 1);
        jobs.lease(run.tenantId(), run.id(), null);
    }
    private void lockApplication(String tenant, UUID id) {
        if (jdbc.queryForList("SELECT id FROM approval_application WHERE tenant_id=? AND id=? FOR UPDATE", String.class, tenant, id.toString()).isEmpty()) throw notFound();
    }
    private AssistRun requireRun(String tenant, UUID id) { return runs.find(tenant, id).orElseThrow(AssistExecutionService::notFound); }
    private static void requireVersion(Application application, long version) {
        if (application.version() != version) throw new DomainException("AGENT_INPUT_CHANGED", "Application changed since the assist input was selected");
    }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Assist context not found"); }
    private static Receipt receipt(AssistRun run) { return new Receipt(run.id(), run.status(), run.version()); }

    /**
     * 复核是独立动作，不能携带或暗示批准指令。
     * @author owlzhangfq@gmail.com
     */
    public enum ReviewAction { ADOPT, DISMISS }

    /**
     * 最小变更回执用于安全幂等回放。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID id, AssistRun.Status status, long version) { }

    /**
     * 实际可发送内容供审批人预览，凭据和内部目标配置不进入响应。
     * @author owlzhangfq@gmail.com
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record InputOptions(long applicationVersion, boolean enabled, String unavailableCode, String providerId,
                               String model, String destination, String targetDigest, List<AssistModelPort.Source> sources) { }
}
