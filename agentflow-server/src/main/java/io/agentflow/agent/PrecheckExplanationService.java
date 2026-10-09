package io.agentflow.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpensePrecheckJob;
import io.agentflow.expense.ExpensePrecheckService;
import io.agentflow.expense.ExpenseReport;
import io.agentflow.expense.ExpenseReportRepository;
import io.agentflow.expense.ExpenseCorrection;
import io.agentflow.expense.ExpenseCorrectionRepository;
import io.agentflow.expense.JdbcExpensePrecheckRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 编排本人授权、原预检时效、持久解释和人工复核，不拥有财务或审批写入职责。
 * @author owlzhangfq@gmail.com
 */
@Service
public class PrecheckExplanationService {
    private static final long LEASE_GRACE_SECONDS = 30;
    private final CurrentActor actors;
    private final ExpenseHandlingJournal journal;
    private final ExpenseReportRepository reports;
    private final ApprovalApplicationFacade applications;
    private final JdbcExpensePrecheckRepository prechecks;
    private final ExpensePrecheckService checks;
    private final PrecheckExplanationSources sources;
    private final JdbcPrecheckExplanationRepository runs;
    private final AssistConfiguration configuration;
    private final ExpenseCorrectionRepository corrections;

    /** 原检查服务是业务有效性的唯一判定方，解释只投影已授权的事实。 */
    public PrecheckExplanationService(CurrentActor actors, ExpenseReportRepository reports, ApprovalApplicationFacade applications,
            JdbcExpensePrecheckRepository prechecks, ExpensePrecheckService checks, PrecheckExplanationSources sources,
            JdbcPrecheckExplanationRepository runs, AssistConfiguration configuration, ExpenseCorrectionRepository corrections, ExpenseHandlingJournal journal) {
        this.actors = actors; this.journal = journal; this.reports = reports; this.applications = applications; this.prechecks = prechecks;
        this.checks = checks; this.sources = sources; this.runs = runs; this.configuration = configuration;
        this.corrections = corrections;
    }

    /** 幂等回放前也核对当前本人权限；历史读取不要求旧预检仍可采纳。 */
    @Transactional(readOnly = true)
    public void authorize(UUID reportId) { owned(reportId); }

    /** 返回当前可选择事实和实际模型目的地；任何来源都需要本人明确选择。 */
    @Transactional
    public InputOptions input(UUID reportId, UUID precheckId) {
        var report = owned(reportId); var job = precheck(report, precheckId);
        String unavailable = checks.explanationFailure(job, report, Instant.now());
        if (unavailable == null) unavailable = modelFailure(null);
        var observation = job.result() == null ? null : job.result().observation();
        return new InputOptions(job.input().id(), job.input().applicationVersion(), job.input().financialVersion(),
                job.input().attempt(), job.status(), job.completedAt(), observation == null ? null : observation.validUntil(),
                unavailable == null, unavailable, unavailable == null ? configuration.getProviderId() : null,
                unavailable == null ? configuration.getModel() : null, unavailable == null ? configuration.uri().getAuthority() : null,
                unavailable == null ? configuration.targetDigest(PrecheckExplanationRun.PROMPT_VERSION) : null,
                unavailable == null ? sources.available(report, job) : List.of());
    }

    /** 排队冻结双版本、原检查、来源和目的地，网络请求由工作器在事务外执行。 */
    @Transactional
    public Receipt queue(UUID reportId, UUID precheckId, long applicationVersion, long financialVersion,
                         String targetDigest, List<String> sourceIds) {
        var report = owned(reportId); reports.lock(report.tenantId(), reportId); report = owned(reportId);
        var job = precheck(report, precheckId); Instant now = time(Instant.now());
        requireCurrent(checks.explanationFailure(job, report, now));
        if (job.input().applicationVersion() != applicationVersion || job.input().financialVersion() != financialVersion) throw changed("VERSION_CHANGED");
        configuration.requireAvailable();
        if (!configuration.targetDigest(PrecheckExplanationRun.PROMPT_VERSION).equals(targetDigest)) {
            throw new DomainException("AGENT_TARGET_CHANGED", "Refresh the explanation model destination");
        }
        var run = new PrecheckExplanationRun(new PrecheckExplanationRun.Context(UUID.randomUUID(), report.tenantId(),
                actors.actor().userId(), now, sources.select(report, job, sourceIds), targetDigest));
        runs.create(run); record(run); return receipt(run);
    }

    /** 仅返回本人单据的轻量索引，管理员角色不能绕过申请人资格。 */
    @Transactional(readOnly = true)
    public JdbcPrecheckExplanationRepository.Page list(UUID reportId, int page, int pageSize) {
        var report = owned(reportId); return runs.page(report.tenantId(), report.id(), page, pageSize);
    }

    /** 过期历史仍可查看，是否可采纳使用当前权威预检判断。 */
    @Transactional
    public Detail get(UUID reportId, UUID runId) {
        var report = owned(reportId); var run = requireRun(report, runId); var context = run.context(); var state = run.state();
        String unavailable = state.status() == PrecheckExplanationRun.Status.COMPLETED ? currentFailure(report, context, Instant.now()) : "AGENT_RUN_NOT_REVIEWABLE";
        var input = context.input();
        return new Detail(context.id(), input.precheckId(), input.applicationVersion(), input.financialVersion(), input.attempt(),
                input.result(), input.checkedAt(), input.validUntil(), state.status(), state.version(), context.createdAt(),
                state.startedAt(), state.completedAt(), input.sources(), state.suggestion(), state.failure(), state.review(), unavailable == null, unavailable,
                corrections.find(report.tenantId(), runId).orElse(null));
    }

    /** 人工确认只追加解释复核轨迹，不调用财务修改或审批入口。 */
    @Transactional
    public Receipt review(UUID reportId, UUID runId, long expectedVersion, AssistExecutionService.ReviewAction action,
                          List<String> selectedIssueIds, String comment) {
        var report = owned(reportId); reports.lock(report.tenantId(), reportId); report = owned(reportId);
        requireRun(report, runId); runs.lock(report.tenantId(), runId); var run = requireRun(report, runId);
        Instant now = time(Instant.now());
        if (action == AssistExecutionService.ReviewAction.DISMISS) {
            if (selectedIssueIds != null && !selectedIssueIds.isEmpty()) throw new DomainException("INVALID_AGENT_REVIEW", "Dismissal cannot adopt explanations");
            run.dismiss(expectedVersion, actors.actor().userId(), comment, now);
        } else if (action == AssistExecutionService.ReviewAction.ADOPT) {
            requireCurrent(currentFailure(report, run.context(), now));
            run.adopt(expectedVersion, actors.actor().userId(), selectedIssueIds, comment, now);
        } else throw new DomainException("INVALID_AGENT_REVIEW", "Explanation review action is required");
        save(run, expectedVersion); return receipt(run);
    }

    /** 单据到运行的锁顺序与费用写入一致；租约过期的执行只记失败，不重发。 */
    @Transactional
    public PrecheckExplanationRun.Context claim(String tenant, UUID id, Instant at) {
        var initial = runs.find(tenant, id).orElse(null); if (initial == null) return null;
        reports.lock(tenant, initial.context().input().reportId());
        if (!runs.lock(tenant, id)) return null;
        var run = runs.find(tenant, id).orElseThrow(); Instant now = time(at);
        if (run.expired(now)) { fail(run, AssistRun.Failure.MODEL_TIMEOUT, now); return null; }
        if (run.state().status() != PrecheckExplanationRun.Status.QUEUED) return null;
        String modelUnavailable = modelFailure(run.context().targetDigest());
        run.start(1, now, now.plusSeconds(LEASE_GRACE_SECONDS + (modelUnavailable == null ? configuration.getTimeoutSeconds() : 0))); save(run, 1);
        if (modelUnavailable != null) { fail(run, AssistRun.Failure.MODEL_UNAVAILABLE, now); return null; }
        var report = reports.find(tenant, run.context().input().reportId()).orElseThrow();
        if (currentFailure(report, run.context(), now) != null) { fail(run, AssistRun.Failure.INPUT_UNAVAILABLE, now); return null; }
        return run.context();
    }

    /** 发送前重新读取原来源，领取后变化不能在 HTTP 之前被忽略。 */
    @Transactional
    public boolean sendable(PrecheckExplanationRun.Context context, Instant at) {
        reports.lock(context.tenantId(), context.input().reportId());
        if (!runs.lock(context.tenantId(), context.id())) return false;
        var run = runs.find(context.tenantId(), context.id()).orElseThrow(); Instant now = time(at);
        if (run.state().status() != PrecheckExplanationRun.Status.RUNNING || !run.context().equals(context)) return false;
        if (run.expired(now)) { fail(run, AssistRun.Failure.MODEL_TIMEOUT, now); return false; }
        if (modelFailure(context.targetDigest()) != null) { fail(run, AssistRun.Failure.MODEL_UNAVAILABLE, now); return false; }
        var report = reports.find(context.tenantId(), context.input().reportId()).orElseThrow();
        if (currentFailure(report, context, now) != null) { fail(run, AssistRun.Failure.INPUT_UNAVAILABLE, now); return false; }
        return true;
    }

    /** 晚到结果不能覆盖超时；生成期间业务改变时保留原解释，读取及采纳会显示失效。 */
    @Transactional
    public void finish(String tenant, UUID id, PrecheckExplanationSuggestion suggestion, AssistRun.Failure failure, Instant at) {
        if (!runs.lock(tenant, id)) return;
        var run = runs.find(tenant, id).orElseThrow(); if (run.state().status() != PrecheckExplanationRun.Status.RUNNING) return;
        Instant now = time(at);
        if (run.expired(now)) { fail(run, AssistRun.Failure.MODEL_TIMEOUT, now); return; }
        if (failure != null) { fail(run, failure, now); return; }
        try { run.complete(2, suggestion, now); }
        catch (DomainException invalid) { fail(run, AssistRun.Failure.INVALID_MODEL_OUTPUT, now); return; }
        save(run, 2);
    }

    private String currentFailure(ExpenseReport report, PrecheckExplanationRun.Context context, Instant now) {
        var input = context.input();
        if (!report.employeeId().equals(context.requestedBy()) || !report.applicationId().equals(input.applicationId())
                || report.version() != input.financialVersion()) return "CONTEXT_CHANGED";
        var job = prechecks.find(report.tenantId(), input.precheckId()).orElse(null);
        if (job == null) return "PRECHECK_UNAVAILABLE";
        String failure = checks.explanationFailure(job, report, now); if (failure != null) return failure;
        if (!input.currentAt(now) || !input.equals(sources.select(report, job,
                input.sources().stream().map(source -> source.reference().sourceId()).toList()))) return "CONTEXT_CHANGED";
        return modelFailure(context.targetDigest());
    }
    private String modelFailure(String targetDigest) {
        try {
            configuration.requireAvailable();
            return targetDigest == null || configuration.targetDigest(PrecheckExplanationRun.PROMPT_VERSION).equals(targetDigest) ? null : "AGENT_TARGET_CHANGED";
        } catch (DomainException unavailable) { return unavailable.code(); }
    }
    private ExpenseReport owned(UUID id) {
        var actor = actors.actor();
        var report = reports.find(actor.tenantId(), id).filter(value -> value.employeeId().equals(actor.userId())).orElseThrow(PrecheckExplanationService::notFound);
        applications.requireApplicant(report.applicationId()); return report;
    }
    private ExpensePrecheckJob precheck(ExpenseReport report, UUID id) {
        return prechecks.find(report.tenantId(), id).filter(value -> value.input().reportId().equals(report.id())).orElseThrow(PrecheckExplanationService::notFound);
    }
    private PrecheckExplanationRun requireRun(ExpenseReport report, UUID id) {
        return runs.find(report.tenantId(), id).filter(value -> value.context().input().reportId().equals(report.id())
                && value.context().requestedBy().equals(actors.actor().userId())).orElseThrow(PrecheckExplanationService::notFound);
    }
    private void save(PrecheckExplanationRun run, long expectedVersion) { runs.update(run, expectedVersion); record(run); }
    private void record(PrecheckExplanationRun run) {
        var context = run.context(); var input = context.input();
        journal.record(context.tenantId(), input.reportId(), ExpenseHandlingTask.Tool.EXPLANATION, context.id(), run.state().version(),
                run.state().status().name(), input.applicationVersion(), input.financialVersion(), input, context.createdAt());
    }
    private void fail(PrecheckExplanationRun run, AssistRun.Failure failure, Instant now) { run.fail(2, failure, now); save(run, 2); }
    private static void requireCurrent(String failure) { if (failure != null) throw changed(failure); }
    private static DomainException changed(String reason) { return new DomainException("AGENT_INPUT_CHANGED", "Refresh the current expense precheck: " + reason); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Precheck explanation not found"); }
    private static Instant time(Instant at) { return at.truncatedTo(ChronoUnit.MILLIS); }
    private static Receipt receipt(PrecheckExplanationRun run) { return new Receipt(run.context().id(), run.state().status(), run.state().version()); }

    /**
     * 本次预检与发送目录；不可用时不提供可发送来源。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record InputOptions(UUID precheckId, long applicationVersion, long financialVersion, long attempt, ExpensePrecheckJob.Status result,
            Instant checkedAt, Instant validUntil, boolean enabled, String unavailableCode, String providerId, String model,
            String destination, String targetDigest, List<AssistModelPort.Source> sources) { }
    /**
     * 幂等回执不缓存财务内容、原来源或模型输出。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID id, PrecheckExplanationRun.Status status, long version) { }
    /**
     * 只有本人可读取的解释历史，业务结论和模型建议分开表达。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Detail(UUID id, UUID precheckId, long applicationVersion, long financialVersion, long attempt, ExpensePrecheckJob.Status result,
            Instant checkedAt, Instant validUntil, PrecheckExplanationRun.Status status, long version, Instant createdAt, Instant startedAt,
            Instant completedAt, List<AssistModelPort.Source> sources, PrecheckExplanationSuggestion suggestion, AssistRun.Failure failure,
            PrecheckExplanationRun.Review review, boolean canAdopt, String unavailableCode,
            @JsonInclude(JsonInclude.Include.NON_NULL) ExpenseCorrection correction) { }
}
