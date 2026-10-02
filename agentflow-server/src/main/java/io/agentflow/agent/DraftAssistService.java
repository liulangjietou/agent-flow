package io.agentflow.agent;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.SubprocessExecutionLocks;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 编排草稿授权、持久运行和人工保存；模型调用始终在这些事务之外。
 * @author owlzhangfq@gmail.com
 */
@Service
public class DraftAssistService {
    private final ApprovalApplicationFacade applications;
    private final ApplicationRepository applicationRepository;
    private final SubprocessExecutionLocks locks;
    private final CurrentActor actors;
    private final DraftAssistInputs inputs;
    private final JdbcDraftAssistRunRepository runs;
    private final AssistConfiguration configuration;
    private final JdbcTemplate jdbc;

    /** 草稿写入复用原申请服务，建议上下文仅拥有自己的运行与确认状态。 */
    public DraftAssistService(ApprovalApplicationFacade applications, ApplicationRepository applicationRepository,
            SubprocessExecutionLocks locks, CurrentActor actors, DraftAssistInputs inputs, JdbcDraftAssistRunRepository runs,
            AssistConfiguration configuration, JdbcTemplate jdbc) {
        this.applications = applications; this.applicationRepository = applicationRepository; this.locks = locks; this.actors = actors;
        this.inputs = inputs; this.runs = runs; this.configuration = configuration; this.jdbc = jdbc;
    }

    /** 展示实际模型目的地和可发送目录，默认不选已有正文。 */
    @Transactional(readOnly = true)
    public InputOptions input(UUID applicationId) {
        var application = applications.requireApplicant(applicationId); application.requireEditable(application.version());
        var schema = inputs.schema(application);
        String unavailable = null;
        try { configuration.requireAvailable(); } catch (DomainException failure) { unavailable = failure.code(); }
        return new InputOptions(application.version(), unavailable == null, unavailable,
                unavailable == null ? configuration.getProviderId() : null, unavailable == null ? configuration.getModel() : null,
                unavailable == null ? configuration.uri().getAuthority() : null,
                unavailable == null ? configuration.targetDigest(DraftAssistRun.PROMPT_VERSION) : null, schema, inputs.available(application));
    }

    /** 本人分页查看独立历史建议；管理员角色不替代申请人资格。 */
    @Transactional(readOnly = true)
    public JdbcDraftAssistRunRepository.Page list(UUID applicationId, int page, int pageSize) {
        var application = applications.requireApplicant(applicationId);
        return runs.page(application.tenantId(), application.id(), page, pageSize);
    }

    /** 原始输入只返回原申请人，过期结果可以查看但不能覆盖新草稿。 */
    @Transactional(readOnly = true)
    public Detail get(UUID applicationId, UUID runId) {
        var application = applications.requireApplicant(applicationId);
        var run = requireRun(application, runId); var state = run.state();
        return new Detail(run.context().id(), run.context().input().applicationVersion(), state.status(), state.version(),
                run.context().createdAt(), state.startedAt(), state.completedAt(), run.context().input().targetSchema(),
                run.context().input().sources(), state.suggestion(), state.failure(), state.review(),
                state.status() == DraftAssistRun.Status.COMPLETED && application.editable()
                        && application.version() == run.context().input().applicationVersion());
    }

    /** 版本、来源和目标确认后排队，202 不代表生成完成，更不代表提交申请。 */
    @Transactional
    public Receipt queue(UUID applicationId, long expectedVersion, String targetDigest, String brief, List<String> sourceIds) {
        var application = locks.lock(applications.requireApplicant(applicationId));
        application.requireEditable(expectedVersion);
        configuration.requireAvailable();
        if (!configuration.targetDigest(DraftAssistRun.PROMPT_VERSION).equals(targetDigest)) throw new DomainException("AGENT_TARGET_CHANGED", "Refresh the draft model destination");
        var selected = inputs.select(application, brief, sourceIds);
        if (runs.active(application.tenantId(), applicationId)) throw new DomainException("AGENT_RUN_ACTIVE", "Draft generation is already active");
        var run = new DraftAssistRun(new DraftAssistRun.Context(UUID.randomUUID(), application.tenantId(), actors.actor().userId(), Instant.now(), selected, targetDigest));
        runs.create(run); return receipt(run);
    }

    /** 人工选择与申请修改、运行复核及审计同事务提交，失败时一起回滚。 */
    @Transactional
    public Receipt review(UUID applicationId, UUID runId, long expectedRunVersion, Long expectedApplicationVersion,
                          AssistExecutionService.ReviewAction action, List<DraftSuggestion.Selection> selected, String comment) {
        var application = locks.lock(applications.requireApplicant(applicationId));
        requireRun(application, runId);
        runs.lock(application.tenantId(), runId);
        var run = requireRun(application, runId);
        if (action == AssistExecutionService.ReviewAction.DISMISS) {
            if (selected != null && !selected.isEmpty()) throw new DomainException("INVALID_AGENT_REVIEW", "Dismissal cannot save draft values");
            run.dismiss(expectedRunVersion, actors.actor().userId(), comment, Instant.now());
        } else {
            if (expectedApplicationVersion == null) throw new DomainException("INVALID_AGENT_REVIEW", "Draft version is required for adoption");
            application.requireEditable(expectedApplicationVersion);
            run.adopt(expectedRunVersion, application.version(), actors.actor().userId(), selected, comment, Instant.now());
            var payload = new LinkedHashMap<>(application.payload()); String title = application.title();
            for (var value : run.state().review().selected()) {
                if (DraftAssistInput.TITLE.equals(value.targetId())) title = (String) value.value();
                else payload.put(value.targetId().substring(DraftAssistInput.FIELD_PREFIX.length()), value.value());
            }
            applications.revise(applicationId, application.version(), title, payload);
        }
        runs.update(run, expectedRunVersion); return receipt(run);
    }

    /** 按申请到运行的顺序领取，锁后复核当前可编辑状态和原申请版本。 */
    @Transactional
    public DraftAssistRun.Context claim(String tenant, UUID id, Instant now) {
        var initial = runs.find(tenant, id).orElse(null);
        if (initial == null) return null;
        UUID applicationId = initial.context().input().applicationId();
        if (jdbc.queryForList("SELECT id FROM approval_application WHERE tenant_id=? AND id=? FOR UPDATE", String.class, tenant, applicationId.toString()).isEmpty()) return null;
        if (!runs.lock(tenant, id)) return null;
        var run = runs.find(tenant, id).orElseThrow();
        if (run.state().status() == DraftAssistRun.Status.RUNNING) {
            var lease = runs.lease(tenant, id);
            if (lease != null && !lease.isAfter(now)) fail(run, AssistRun.Failure.MODEL_TIMEOUT, now);
            return null;
        }
        if (run.state().status() != DraftAssistRun.Status.QUEUED) return null;
        run.start(1, now); runs.update(run, 1);
        try {
            configuration.requireAvailable();
            if (!configuration.targetDigest(DraftAssistRun.PROMPT_VERSION).equals(run.context().targetDigest())) throw new DomainException("AGENT_TARGET_CHANGED", "Draft model target changed");
        } catch (DomainException unavailable) { fail(run, AssistRun.Failure.MODEL_UNAVAILABLE, now); return null; }
        try {
            var application = applicationRepository.findById(tenant, applicationId).orElseThrow(DraftAssistService::notFound);
            application.requireEditable(run.context().input().applicationVersion());
            if (!application.createdBy().equals(run.context().requestedBy()) || application.businessReference() != null) throw notFound();
        } catch (DomainException unavailable) { fail(run, AssistRun.Failure.INPUT_UNAVAILABLE, now); return null; }
        runs.lease(tenant, id, now.plusSeconds(configuration.getTimeoutSeconds() + 30L));
        return run.context();
    }

    /** 晚到结果不能覆盖超时结论；生成后申请已变化时仍保留原建议供查看，不允许采纳。 */
    @Transactional
    public void finish(String tenant, UUID id, DraftSuggestion suggestion, AssistRun.Failure failure, Instant now) {
        if (!runs.lock(tenant, id)) return;
        var run = runs.find(tenant, id).orElseThrow();
        if (run.state().status() != DraftAssistRun.Status.RUNNING) return;
        var lease = runs.lease(tenant, id);
        if (lease == null || !lease.isAfter(now)) { fail(run, AssistRun.Failure.MODEL_TIMEOUT, now); return; }
        if (failure != null) { fail(run, failure, now); return; }
        try { run.complete(2, suggestion, now); }
        catch (DomainException invalid) { fail(run, AssistRun.Failure.INVALID_MODEL_OUTPUT, now); return; }
        runs.update(run, 2); runs.lease(tenant, id, null);
    }
    private void fail(DraftAssistRun run, AssistRun.Failure failure, Instant now) {
        run.fail(run.state().version(), failure, now); runs.update(run, run.state().version() - 1);
        runs.lease(run.context().tenantId(), run.context().id(), null);
    }
    private DraftAssistRun requireRun(Application application, UUID id) {
        var run = runs.find(application.tenantId(), id).orElseThrow(DraftAssistService::notFound);
        if (!run.context().input().applicationId().equals(application.id()) || !run.context().requestedBy().equals(actors.actor().userId())) throw notFound();
        return run;
    }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Draft suggestion not found"); }
    private static Receipt receipt(DraftAssistRun run) {
        return new Receipt(run.context().id(), run.state().status(), run.state().version(),
                run.state().review() == null ? null : run.state().review().appliedApplicationVersion());
    }
    /**
     * 当前草稿和实际模型目的地供申请人选择发送内容。
     * @author owlzhangfq@gmail.com
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record InputOptions(long applicationVersion, boolean enabled, String unavailableCode, String providerId,
                               String model, String destination, String targetDigest, FormSchema targetSchema, List<AssistModelPort.Source> sources) { }
    /**
     * 安全幂等回执不携带申请正文、模型结果或原始输入。
     * @author owlzhangfq@gmail.com
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Receipt(UUID id, DraftAssistRun.Status status, long version, Long savedApplicationVersion) { }
    /**
     * 只有原申请人可以读取的建议、输入与人工记录。
     * @author owlzhangfq@gmail.com
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Detail(UUID id, long applicationVersion, DraftAssistRun.Status status, long version, Instant createdAt,
                         Instant startedAt, Instant completedAt, FormSchema targetSchema, List<AssistModelPort.Source> sources,
                         DraftSuggestion suggestion, AssistRun.Failure failure, DraftAssistRun.Review review, boolean canAdopt) { }
}
