package io.agentflow.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseReport;
import io.agentflow.expense.ExpenseReportRepository;
import io.agentflow.finance.FinanceGatewayConfiguration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 编排本人来源、持久执行和人工选择；外部目录与模型调用由事务外准备及工作器完成。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseDraftAssistService {
    private static final long LEASE_GRACE_SECONDS = 30;
    private final CurrentActor actors;
    private final ExpenseHandlingJournal journal;
    private final ExpenseReportRepository reports;
    private final ApprovalApplicationFacade applications;
    private final ApplicationRepository applicationRepository;
    private final JdbcExpenseDraftAssistRepository runs;
    private final AssistConfiguration configuration;
    private final FinanceGatewayConfiguration finance;

    /** 助手不持有费用保存服务，无法通过生成或确认动作改写费用和审批。 */
    public ExpenseDraftAssistService(CurrentActor actors, ExpenseReportRepository reports, ApprovalApplicationFacade applications,
            ApplicationRepository applicationRepository, JdbcExpenseDraftAssistRepository runs,
            AssistConfiguration configuration, FinanceGatewayConfiguration finance, ExpenseHandlingJournal journal) {
        this.actors = actors; this.journal = journal; this.reports = reports; this.applications = applications; this.applicationRepository = applicationRepository;
        this.runs = runs; this.configuration = configuration; this.finance = finance;
    }

    /** 幂等回放也必须先核对本人资格；历史读取不要求旧单据仍可编辑。 */
    @Transactional(readOnly = true)
    public void authorize(UUID reportId) { owned(reportId); }

    /** 事务外目录准备前先完成授权与双版本校验，避免向财务系统查询无权单据。 */
    @Transactional(readOnly = true)
    public Snapshot editable(UUID reportId, long applicationVersion, long financialVersion) {
        var snapshot = owned(reportId); snapshot.application().requireEditable(applicationVersion);
        if (snapshot.report().version() != financialVersion) throw changed("VERSION_CHANGED");
        return snapshot;
    }

    /** 仅后台和确认准备调用；来源必须仍是数据库中的原运行和当前报销版本。 */
    @Transactional(readOnly = true)
    public Snapshot sources(ExpenseDraftAssistRun.Context context) {
        var run = runs.find(context.tenantId(), context.id()).orElseThrow(ExpenseDraftAssistService::notFound);
        if (!run.context().equals(context) || !(run.state().status() == ExpenseDraftAssistRun.Status.RUNNING
                || run.state().status() == ExpenseDraftAssistRun.Status.COMPLETED)) throw changed("RUN_CHANGED");
        var snapshot = load(context.tenantId(), context.input().reportId()); requireCurrent(currentFailure(snapshot, context, Instant.now()));
        return snapshot;
    }

    /** 排队只接受与预览完全一致的内容和目的地；原有效期不能因再次读目录而延长。 */
    @Transactional
    public Receipt queue(UUID reportId, ExpenseDraftAssistPreparation.Prepared prepared, String targetDigest, String consentDigest) {
        var original = owned(reportId); reports.lock(original.report().tenantId(), reportId); var current = owned(reportId);
        requirePrepared(prepared, current);
        if (!prepared.targetDigest().equals(targetDigest)) throw new DomainException("AGENT_TARGET_CHANGED", "Refresh the expense draft model destination");
        if (!prepared.consentDigest().equals(consentDigest)) throw changed("CONSENT_CHANGED");
        Instant at = Instant.now();
        if (!prepared.input().currentAt(at)) throw changed("FACTS_EXPIRED");
        var run = new ExpenseDraftAssistRun(new ExpenseDraftAssistRun.Context(UUID.randomUUID(), current.report().tenantId(),
                actors.actor().userId(), time(at), prepared.input(), targetDigest));
        requireCurrent(currentFailure(current, run.context(), at)); runs.create(run); record(run); return receipt(run);
    }

    /** 历史目录只返回本人报销的轻量索引，不暴露其他单据的数量。 */
    @Transactional(readOnly = true)
    public JdbcExpenseDraftAssistRepository.Page list(UUID reportId, int page, int size) {
        var snapshot = owned(reportId); return runs.page(snapshot.report().tenantId(), reportId, page, size);
    }

    /** 原建议保留供本人读取，确认前仍须重新取得当前目录，不能只依赖页面提示。 */
    @Transactional(readOnly = true)
    public Detail get(UUID reportId, UUID runId) {
        var snapshot = owned(reportId); var run = requireRun(snapshot.report(), runId); var state = run.state();
        String unavailable = state.status() == ExpenseDraftAssistRun.Status.COMPLETED
                ? currentFailure(snapshot, run.context(), Instant.now()) : "AGENT_RUN_NOT_REVIEWABLE";
        if (unavailable == null && state.suggestion().lines().isEmpty()) unavailable = "AGENT_NO_SUGGESTIONS";
        return new Detail(run.context().id(), run.context().input(), state.status(), state.version(), run.context().createdAt(),
                state.startedAt(), state.completedAt(), state.suggestion(), state.failure(), state.review(), unavailable == null, unavailable);
    }

    /** 先确认原运行及本人当前版本，再让事务外准备读取目录，错误请求不会外发。 */
    @Transactional(readOnly = true)
    public ExpenseDraftAssistRun.Context reviewContext(UUID reportId, UUID runId, long runVersion, long applicationVersion, long financialVersion) {
        var snapshot = owned(reportId); var run = requireRun(snapshot.report(), runId);
        if (run.state().version() != runVersion) throw conflict();
        if (run.state().status() != ExpenseDraftAssistRun.Status.COMPLETED) throw new DomainException("AGENT_RUN_STATE_CONFLICT", "Expense draft is not reviewable");
        requireCurrent(currentFailure(snapshot, run.context(), Instant.now()));
        if (applicationVersion != run.context().input().applicationVersion() || financialVersion != run.context().input().financialVersion()) throw changed("VERSION_CHANGED");
        return run.context();
    }

    /** 人工确认仅追加选择记录；真实保存仍由费用编辑器调用原领域入口。 */
    @Transactional
    public Receipt confirm(UUID reportId, UUID runId, long expectedVersion, ExpenseDraftAssistPreparation.Prepared prepared,
                           List<ExpenseDraftAssistRun.Selection> selected, String comment) {
        var original = owned(reportId); reports.lock(original.report().tenantId(), reportId); var snapshot = owned(reportId);
        requireRun(snapshot.report(), runId); runs.lock(snapshot.report().tenantId(), runId); var run = requireRun(snapshot.report(), runId);
        requirePrepared(prepared, snapshot);
        if (!run.context().input().equals(prepared.input()) || !run.context().targetDigest().equals(prepared.targetDigest())) throw changed("SOURCES_CHANGED");
        Instant now = Instant.now(); requireCurrent(currentFailure(snapshot, run.context(), now));
        run.confirm(expectedVersion, snapshot.application().version(), snapshot.report().version(), actors.actor().userId(), selected, comment, now);
        save(run, expectedVersion); return receipt(run);
    }

    /** 过期或不再可编辑的单据仍可放弃旧建议，不触发目录或模型网络调用。 */
    @Transactional
    public Receipt dismiss(UUID reportId, UUID runId, long expectedVersion, String comment) {
        var snapshot = owned(reportId); requireRun(snapshot.report(), runId); runs.lock(snapshot.report().tenantId(), runId);
        var run = requireRun(snapshot.report(), runId); run.dismiss(expectedVersion, actors.actor().userId(), comment, time(Instant.now()));
        save(run, expectedVersion); return receipt(run);
    }

    /** 原租约只领取一次；执行中崩溃恢复为超时失败，不重新发送模型输入。 */
    @Transactional
    public ExpenseDraftAssistRun.Context claim(String tenant, UUID id, Instant at) {
        var initial = runs.find(tenant, id).orElse(null); if (initial == null) return null;
        reports.lock(tenant, initial.context().input().reportId()); if (!runs.lock(tenant, id)) return null;
        var run = runs.find(tenant, id).orElseThrow(); Instant now = time(at);
        if (run.expired(now)) { fail(run, AssistRun.Failure.MODEL_TIMEOUT, now); return null; }
        if (run.state().status() != ExpenseDraftAssistRun.Status.QUEUED) return null;
        String modelFailure = modelFailure(run.context().targetDigest());
        run.start(1, now, now.plusSeconds(LEASE_GRACE_SECONDS + (modelFailure == null ? configuration.getTimeoutSeconds() : 0))); save(run, 1);
        if (modelFailure != null) { fail(run, AssistRun.Failure.MODEL_UNAVAILABLE, now); return null; }
        // 外部事实有效期可能带微秒；只规范化存储时间，不能向前舍入判定时间。
        if (currentFailure(load(tenant, run.context().input().reportId()), run.context(), at) != null) {
            fail(run, AssistRun.Failure.INPUT_UNAVAILABLE, now); return null;
        }
        return run.context();
    }

    /** 目录准备前后均检查原运行，期间修改版本或配置不能被模型发送忽略。 */
    @Transactional
    public boolean sendable(ExpenseDraftAssistRun.Context context, Instant at) {
        reports.lock(context.tenantId(), context.input().reportId()); if (!runs.lock(context.tenantId(), context.id())) return false;
        var run = runs.find(context.tenantId(), context.id()).orElseThrow(); Instant now = time(at);
        if (run.state().status() != ExpenseDraftAssistRun.Status.RUNNING || !run.context().equals(context)) return false;
        if (run.expired(now)) { fail(run, AssistRun.Failure.MODEL_TIMEOUT, now); return false; }
        if (modelFailure(context.targetDigest()) != null) { fail(run, AssistRun.Failure.MODEL_UNAVAILABLE, now); return false; }
        if (currentFailure(load(context.tenantId(), context.input().reportId()), context, at) != null) {
            fail(run, AssistRun.Failure.INPUT_UNAVAILABLE, now); return false;
        }
        return true;
    }

    /** 保存原结果或稳定失败；生成期间的单据变化由详情和确认时效检查处理。 */
    @Transactional
    public void finish(String tenant, UUID id, ExpenseDraftSuggestion suggestion, AssistRun.Failure failure, Instant at) {
        if (!runs.lock(tenant, id)) return; var run = runs.find(tenant, id).orElseThrow();
        if (run.state().status() != ExpenseDraftAssistRun.Status.RUNNING) return;
        Instant now = time(at);
        if (run.expired(now)) { fail(run, AssistRun.Failure.MODEL_TIMEOUT, now); return; }
        if (failure != null) { fail(run, failure, now); return; }
        try { run.complete(2, suggestion, now); }
        catch (DomainException invalid) { fail(run, AssistRun.Failure.INVALID_MODEL_OUTPUT, now); return; }
        save(run, 2);
    }

    private void requirePrepared(ExpenseDraftAssistPreparation.Prepared prepared, Snapshot snapshot) {
        var actor = actors.actor();
        if (!actor.tenantId().equals(prepared.tenantId()) || !actor.userId().equals(prepared.requestedBy())
                || !snapshot.report().id().equals(prepared.input().reportId())) throw notFound();
    }
    private String currentFailure(Snapshot snapshot, ExpenseDraftAssistRun.Context context, Instant at) {
        var report = snapshot.report(); var app = snapshot.application(); var input = context.input();
        if (!report.employeeId().equals(context.requestedBy()) || !app.createdBy().equals(context.requestedBy())
                || !report.applicationId().equals(input.applicationId()) || !app.editable() || app.version() != input.applicationVersion()
                || report.version() != input.financialVersion() || !report.content().legalEntityId().equals(input.legalEntityId())
                || report.content().type() != input.reportType()
                || !new BusinessReference(BusinessReference.Type.EXPENSE, report.id()).equals(app.businessReference())) return "CONTEXT_CHANGED";
        if (!input.currentAt(at)) return "FACTS_EXPIRED";
        var destination = finance.destination(context.tenantId());
        if (destination.isEmpty() || !destination.get().digest(context.tenantId()).equals(input.financeTargetDigest())) return "FINANCE_TARGET_CHANGED";
        return modelFailure(context.targetDigest());
    }
    private String modelFailure(String target) {
        try { configuration.requireAvailable(); return configuration.targetDigest(ExpenseDraftAssistRun.PROMPT_VERSION).equals(target) ? null : "AGENT_TARGET_CHANGED"; }
        catch (DomainException unavailable) { return unavailable.code(); }
    }
    private Snapshot owned(UUID id) {
        var actor = actors.actor(); var report = reports.find(actor.tenantId(), id).filter(value -> value.employeeId().equals(actor.userId())).orElseThrow(ExpenseDraftAssistService::notFound);
        return new Snapshot(applications.requireApplicant(report.applicationId()), report);
    }
    private Snapshot load(String tenant, UUID id) {
        var report = reports.find(tenant, id).orElseThrow(ExpenseDraftAssistService::notFound);
        return new Snapshot(applicationRepository.findById(tenant, report.applicationId()).orElseThrow(ExpenseDraftAssistService::notFound), report);
    }
    private ExpenseDraftAssistRun requireRun(ExpenseReport report, UUID id) {
        return runs.find(report.tenantId(), id).filter(run -> run.context().input().reportId().equals(report.id())
                && run.context().requestedBy().equals(actors.actor().userId())).orElseThrow(ExpenseDraftAssistService::notFound);
    }
    private void save(ExpenseDraftAssistRun run, long expectedVersion) { runs.update(run, expectedVersion); record(run); }
    private void record(ExpenseDraftAssistRun run) {
        var context = run.context(); var input = context.input();
        journal.record(context.tenantId(), input.reportId(), ExpenseHandlingTask.Tool.DRAFT, context.id(), run.state().version(),
                run.state().status().name(), input.applicationVersion(), input.financialVersion(), input, context.createdAt());
    }
    private void fail(ExpenseDraftAssistRun run, AssistRun.Failure failure, Instant now) { run.fail(2, failure, now); save(run, 2); }
    private static void requireCurrent(String failure) { if (failure != null) throw changed(failure); }
    private static DomainException changed(String reason) { return new DomainException("AGENT_INPUT_CHANGED", "Refresh expense draft sources: " + reason); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Expense draft run version changed"); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Expense draft assistance not found"); }
    private static Instant time(Instant at) { return at.truncatedTo(ChronoUnit.MILLIS); }
    private static Receipt receipt(ExpenseDraftAssistRun run) { return new Receipt(run.context().id(), run.state().status(), run.state().version()); }

    /**
     * 服务端内部一致来源，不作为请求 DTO 接收。
     * @author owlzhangfq@gmail.com
     */
    public record Snapshot(Application application, ExpenseReport report) { }
    /**
     * 幂等回执只保留运行标识和状态，不缓存个人来源与建议正文。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID id, ExpenseDraftAssistRun.Status status, long version) { }
    /**
     * 历史详情保留完整建议和人工选择，确认能力只是当前本地检查结果。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Detail(UUID id, ExpenseDraftAssistInput input, ExpenseDraftAssistRun.Status status, long version, Instant createdAt,
            Instant startedAt, Instant completedAt, ExpenseDraftSuggestion suggestion, AssistRun.Failure failure,
            ExpenseDraftAssistRun.Review review, boolean canConfirm, String unavailableCode) { }
}
