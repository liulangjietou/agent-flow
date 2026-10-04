package io.agentflow.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.auth.DeferredActorAuthentication;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.ExpenseReportRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

/**
 * 编排逐单授权、显式发送确认、持久执行与人工复核；风险提示没有审批或财务写入职责。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseRiskService {
    private static final long LEASE_GRACE_SECONDS = 30;
    private final CurrentActor actors;
    private final ExpenseReportRepository reports;
    private final ExpenseRiskAccess access;
    private final ExpenseRiskSources sources;
    private final JdbcExpenseRiskRepository runs;
    private final DeferredActorAuthentication authentication;
    private final AssistConfiguration configuration;
    private final JsonUtil json;

    /** 权限与事实归属来源服务，状态归属聚合，跨资源的短事务归属本服务。 */
    public ExpenseRiskService(CurrentActor actors, ExpenseReportRepository reports, ExpenseRiskAccess access,
            ExpenseRiskSources sources, JdbcExpenseRiskRepository runs, DeferredActorAuthentication authentication,
            AssistConfiguration configuration, JsonUtil json) {
        this.actors = actors; this.reports = reports; this.access = access; this.sources = sources;
        this.runs = runs; this.authentication = authentication; this.configuration = configuration; this.json = json;
    }

    /** 预览绑定操作者、任务、原版本与完整目录；公开投影不包含登录引用或原始本地上下文。 */
    @Transactional(readOnly = true)
    public InputOptions input(UUID reportId, String taskId, ExpenseRiskAccess.Selection selection) {
        var actor = actors.actor(); var catalog = access.available(actor, reportId, taskId, selection, Instant.now());
        String unavailable = modelFailure(null);
        if (unavailable == null && !authentication.available()) unavailable = "DEFERRED_AUTHENTICATION_UNAVAILABLE";
        if (unavailable == null && catalog.concerns().isEmpty()) unavailable = "NO_RISK_OBSERVATIONS";
        boolean enabled = unavailable == null;
        return new InputOptions(enabled, unavailable, enabled ? fingerprint(actor, taskId, catalog) : null,
                enabled ? configuration.targetDigest(ExpenseRiskRun.PROMPT_VERSION) : null,
                enabled ? configuration.getProviderId() : null, enabled ? configuration.getModel() : null,
                enabled ? configuration.uri().getAuthority() : null,
                enabled ? catalog.concerns() : List.of(), enabled ? catalog.sources() : List.of());
    }

    /** 所有来源先授权再按固定顺序加锁；重读后逐字核对预览摘要，不能静默改用新来源。 */
    @Transactional
    public Receipt queue(UUID reportId, String taskId, ExpenseRiskAccess.Selection selection,
                         String inputDigest, String targetDigest, List<String> sourceIds, HttpServletRequest request) {
        var actor = actors.actor(); var initial = access.available(actor, reportId, taskId, selection, Instant.now());
        lockDocuments(actor.tenantId(), initial.documents()); Instant now = time(Instant.now());
        var catalog = access.available(actor, reportId, taskId, selection, now);
        if (!fingerprint(actor, taskId, catalog).equals(inputDigest)) throw changed();
        configuration.requireAvailable();
        if (!configuration.targetDigest(ExpenseRiskRun.PROMPT_VERSION).equals(targetDigest)) {
            throw new DomainException("AGENT_TARGET_CHANGED", "Refresh the risk model destination");
        }
        var input = sources.select(catalog, sourceIds);
        var login = authentication.capture(request, actor, now);
        var run = new ExpenseRiskRun(new ExpenseRiskRun.Context(UUID.randomUUID(), actor.tenantId(), actor.userId(), taskId, now, input, targetDigest));
        runs.create(run, login); return receipt(run);
    }

    /** 幂等回执及轻量索引也必须重新检查主单原轮次的字段权限。 */
    @Transactional(readOnly = true)
    public void authorize(UUID reportId, int roundNo) { access.requirePrimaryReadable(actors.actor(), reportId, roundNo); }

    /** 主单历史按原轮次分页；不在列表中泄露对照单或模型正文。 */
    @Transactional(readOnly = true)
    public JdbcExpenseRiskRepository.Page list(UUID reportId, int roundNo, int page, int pageSize) {
        var actor = actors.actor(); access.requirePrimaryReadable(actor, reportId, roundNo);
        return runs.page(actor.tenantId(), reportId, roundNo, page, pageSize);
    }

    /** 失效历史仍须逐单可读；可采纳提示在独立读取中计算，权限异常不能污染外层写事务。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Detail get(UUID reportId, UUID runId) {
        var actor = actors.actor(); var run = requireRun(actor.tenantId(), reportId, runId).run();
        var context = run.context(); var state = run.state(); access.requireReadable(actor, context.input());
        String unavailable = state.status() == ExpenseRiskRun.Status.COMPLETED ? reviewFailure(actor, context) : "AGENT_RUN_NOT_REVIEWABLE";
        return new Detail(context.id(), context.taskId(), context.input().documents().get(0).roundNo(), state.status(), state.version(),
                context.createdAt(), state.startedAt(), state.completedAt(), context.input().concerns(), context.input().sources(),
                state.suggestion(), state.failure(), state.review(), unavailable == null, unavailable);
    }

    /** 当前审批人可复核他人生成的解释；登录发送授权不冒充永久复核资格。 */
    @Transactional
    public Receipt review(UUID reportId, UUID runId, long expectedVersion, AssistExecutionService.ReviewAction action,
                          List<String> selectedConcernIds, String comment) {
        var actor = actors.actor(); var initial = requireRun(actor.tenantId(), reportId, runId).run();
        access.requireReadable(actor, initial.context().input());
        lockDocuments(actor.tenantId(), initial.context().input().documents());
        if (!runs.lock(actor.tenantId(), runId)) throw notFound();
        var run = requireRun(actor.tenantId(), reportId, runId).run(); var context = run.context();
        access.requireReadable(actor, context.input());
        access.requireDecision(actor, context.input().documents().get(0), context.taskId());
        Instant now = time(Instant.now());
        if (action == AssistExecutionService.ReviewAction.ADOPT) {
            run.adopt(expectedVersion, currentInput(actor, context, now), actor.userId(), selectedConcernIds, comment, now);
        } else if (action == AssistExecutionService.ReviewAction.DISMISS) {
            if (!CollectionUtils.isEmpty(selectedConcernIds)) throw new DomainException("INVALID_AGENT_REVIEW", "Dismissal cannot adopt risk explanations");
            run.dismiss(expectedVersion, actor.userId(), comment, now);
        } else throw new DomainException("INVALID_AGENT_REVIEW", "Risk review action is required");
        runs.update(run, expectedVersion); return receipt(run);
    }

    /** 领取只提交一次不可延期租约；授权失败由发送检查结束后另开事务记录，防止回滚吞掉失败状态。 */
    @Transactional
    public ExpenseRiskRun.Context claim(String tenant, UUID id, Instant at) {
        var initial = runs.find(tenant, id).orElse(null); if (initial == null) return null;
        lockDocuments(tenant, initial.run().context().input().documents());
        if (!runs.lock(tenant, id)) return null;
        var run = runs.find(tenant, id).orElseThrow().run(); Instant now = time(at);
        if (run.expired(now)) { fail(run, AssistRun.Failure.MODEL_TIMEOUT, now); return null; }
        if (run.state().status() != ExpenseRiskRun.Status.QUEUED) return null;
        String unavailable = modelFailure(run.context().targetDigest());
        run.start(1, now, now.plusSeconds(LEASE_GRACE_SECONDS + (unavailable == null ? configuration.getTimeoutSeconds() : 0)));
        runs.update(run, 1);
        if (unavailable != null) { fail(run, AssistRun.Failure.MODEL_UNAVAILABLE, now); return null; }
        return run.context();
    }

    /** 外发前恢复原登录并重新核对任务、每份原轮次权限、票据和日历；权限异常交工作器另事务结算。 */
    @Transactional
    public boolean sendable(ExpenseRiskRun.Context context, Instant at) {
        lockDocuments(context.tenantId(), context.input().documents());
        if (!runs.lock(context.tenantId(), context.id())) return false;
        var entry = runs.find(context.tenantId(), context.id()).orElseThrow(); var run = entry.run(); Instant now = time(at);
        if (run.state().status() != ExpenseRiskRun.Status.RUNNING || !run.context().equals(context)) return false;
        if (run.expired(now)) { fail(run, AssistRun.Failure.MODEL_TIMEOUT, now); return false; }
        if (modelFailure(context.targetDigest()) != null) { fail(run, AssistRun.Failure.MODEL_UNAVAILABLE, now); return false; }
        var actor = authentication.resolve(entry.login(), context.tenantId(), context.requestedBy(), now);
        if (actor.isEmpty() || !context.input().equals(currentInput(actor.get(), context, now))) {
            fail(run, AssistRun.Failure.INPUT_UNAVAILABLE, now); return false;
        }
        return true;
    }

    /** 网络返回后只更新风险运行；业务变化由详情和采纳重新检查，晚到输出不能覆盖超时。 */
    @Transactional
    public void finish(String tenant, UUID id, ExpenseRiskSuggestion suggestion, AssistRun.Failure failure, Instant at) {
        if (!runs.lock(tenant, id)) return;
        var run = runs.find(tenant, id).orElseThrow().run(); if (run.state().status() != ExpenseRiskRun.Status.RUNNING) return;
        Instant now = time(at);
        if (run.expired(now)) { fail(run, AssistRun.Failure.MODEL_TIMEOUT, now); return; }
        if (failure != null) { fail(run, failure, now); return; }
        try { run.complete(2, suggestion, now); }
        catch (DomainException invalid) { fail(run, AssistRun.Failure.INVALID_MODEL_OUTPUT, now); return; }
        runs.update(run, 2);
    }

    private ExpenseRiskInput currentInput(Actor actor, ExpenseRiskRun.Context context, Instant now) {
        var input = context.input(); var selection = new ExpenseRiskAccess.Selection(input.documents().stream()
                .map(document -> new ExpenseRiskAccess.SelectedDocument(document.reportId(), document.roundNo(), document.lineNos())).toList(),
                input.calendar() == null ? null : input.calendar().id());
        var catalog = access.available(actor, input.documents().get(0).reportId(), context.taskId(), selection, now);
        return sources.select(catalog, input.sources().stream().map(source -> source.reference().sourceId()).toList());
    }
    private String reviewFailure(Actor actor, ExpenseRiskRun.Context context) {
        try { return context.input().equals(currentInput(actor, context, Instant.now())) ? null : "AGENT_INPUT_CHANGED"; }
        catch (DomainException unavailable) { return unavailable.code(); }
    }
    private JdbcExpenseRiskRepository.Entry requireRun(String tenant, UUID reportId, UUID id) {
        return runs.find(tenant, id).filter(entry -> entry.run().context().input().documents().get(0).reportId().equals(reportId)).orElseThrow(ExpenseRiskService::notFound);
    }
    private void lockDocuments(String tenant, List<ExpenseRiskInput.Document> documents) {
        documents.stream().sorted(Comparator.comparing(document -> document.applicationId().toString()))
                .forEach(document -> reports.lock(tenant, document.reportId()));
    }
    private String fingerprint(Actor actor, String taskId, ExpenseRiskSources.Catalog catalog) {
        return AssistConfiguration.digest(json.write(new Consent(actor.tenantId(), actor.userId(), taskId, catalog)));
    }
    private String modelFailure(String targetDigest) {
        try {
            configuration.requireAvailable();
            return targetDigest == null || configuration.targetDigest(ExpenseRiskRun.PROMPT_VERSION).equals(targetDigest) ? null : "AGENT_TARGET_CHANGED";
        } catch (DomainException unavailable) { return unavailable.code(); }
    }
    private void fail(ExpenseRiskRun run, AssistRun.Failure failure, Instant now) { run.fail(2, failure, now); runs.update(run, 2); }
    private static DomainException changed() { return new DomainException("AGENT_INPUT_CHANGED", "Refresh the selected risk evidence before confirming delivery"); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Expense risk run not found"); }
    private static Instant time(Instant at) { return at.truncatedTo(ChronoUnit.MILLIS); }
    private static Receipt receipt(ExpenseRiskRun run) { return new Receipt(run.context().id(), run.state().status(), run.state().version()); }

    /**
     * 摘要绑定本次操作者和预览，不作为认证凭据或公开正文。
     * @author owlzhangfq@gmail.com
     */
    private record Consent(String tenant, String user, String taskId, ExpenseRiskSources.Catalog catalog) { }
    /**
     * 仅返回去标识后的可选事实与实际发送目的地，缺省不勾选任何来源。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record InputOptions(boolean enabled, String unavailableCode, String inputDigest, String targetDigest,
                               String providerId, String model, String destination, List<ExpenseRiskInput.Concern> concerns,
                               List<AssistModelPort.Source> sources) { }
    /**
     * 幂等回执只保存定位与状态，回放时仍须重新授权。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID id, ExpenseRiskRun.Status status, long version) { }
    /**
     * 每次逐单授权后才提供正文；原登录和本地版本上下文不对外序列化。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Detail(UUID id, String taskId, int roundNo, ExpenseRiskRun.Status status, long version, Instant createdAt,
                         Instant startedAt, Instant completedAt, List<ExpenseRiskInput.Concern> concerns, List<AssistModelPort.Source> sources,
                         ExpenseRiskSuggestion suggestion, AssistRun.Failure failure, ExpenseRiskRun.Review review,
                         boolean adoptable, String unavailableCode) { }
}
