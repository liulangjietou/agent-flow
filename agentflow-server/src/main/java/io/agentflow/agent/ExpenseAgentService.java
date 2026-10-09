package io.agentflow.agent;

import io.agentflow.auth.DeferredActorAuthentication;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.ExpenseReportRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 跨工具的办理循环；模型传输在事务外，状态和原调用身份在外发前提交。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseAgentService {
    private static final int MODEL_LEASE_GRACE_SECONDS = 30;
    private final CurrentActor actors;
    private final DeferredActorAuthentication authentication;
    private final ExpenseHandlingService handling;
    private final ExpenseReportRepository reports;
    private final ExpenseAgentRepository repository;
    private final ExpenseAgentTools tools;
    private final ExpenseAgentModel model;
    private final AgentExecutionTelemetry telemetry;
    private final AssistConfiguration configuration;
    private final JsonUtil json;
    private final InvoiceExtractionEligibility eligibility;
    private final TransactionTemplate transactions;
    private final TransactionTemplate authorizationTransactions;
    /** 状态机只协调既有用例，实际费用与票据规则留在各自领域。 */
    public ExpenseAgentService(CurrentActor actors, DeferredActorAuthentication authentication, ExpenseHandlingService handling,
            ExpenseReportRepository reports, ExpenseAgentRepository repository, ExpenseAgentTools tools, ExpenseAgentModel model,
            AgentExecutionTelemetry telemetry, AssistConfiguration configuration, JsonUtil json, PlatformTransactionManager manager, InvoiceExtractionEligibility eligibility) {
        this.actors = actors; this.authentication = authentication; this.handling = handling; this.reports = reports;
        this.repository = repository; this.tools = tools; this.model = model; this.telemetry = telemetry;
        this.configuration = configuration; this.json = json; this.eligibility = eligibility; this.transactions = new TransactionTemplate(manager);
        this.authorizationTransactions = new TransactionTemplate(manager);
        this.authorizationTransactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.authorizationTransactions.setReadOnly(true);
    }
    /** 在外发前展示目标、工具范围、费用正文和真实目的地，变更后需重新确认摘要。 */
    @Transactional(readOnly = true)
    public Preview preview(UUID reportId, UUID taskId, ExpenseAgentRun.Scope scope) {
        configuration.requireAvailable();
        if (!authentication.available()) throw new DomainException("DEFERRED_AUTHENTICATION_UNAVAILABLE", "Agent requires original shared login recovery");
        var task = handling.requireReadable(reportId, taskId, null); var actor = actors.actor();
        eligibility.requireActive(actor.tenantId(), actor.userId());
        var data = tools.preview(reportId, scope);
        String target = configuration.targetDigest(ExpenseAgentRun.PROMPT_VERSION);
        String digest = AssistConfiguration.digest(json.write(Map.of("task", taskId, "owner", actor, "goal", task.context().goal(),
                "applicationVersion", task.state().applicationVersion(), "financialVersion", task.state().financialVersion(), "scope", scope, "target", target, "data", data)));
        return new Preview(task.context().goal(), scope, task.state().applicationVersion(), task.state().financialVersion(),
                configuration.getProviderId(), configuration.getModel(), configuration.uri().getAuthority(), target, digest, data);
    }
    /** 一次明确授权建立持久循环；当前浏览器请求的原登录才可以成为后台执行身份。 */
    @Transactional
    public View start(UUID reportId, UUID taskId, ExpenseAgentRun.Scope scope, String consentDigest, String targetDigest, HttpServletRequest request) {
        var actor = actors.actor(); reports.lock(actor.tenantId(), reportId); var preview = preview(reportId, taskId, scope);
        if (!preview.consentDigest().equals(consentDigest) || !preview.targetDigest().equals(targetDigest)) throw changed();
        if (repository.find(actor.tenantId(), taskId, true).isPresent()) throw new DomainException("AGENT_RUN_ACTIVE", "Handling already has an agent authorization");
        var now = Instant.now(); var login = authentication.capture(request, actor, now);
        var run = new ExpenseAgentRun(new ExpenseAgentRun.Context(UUID.randomUUID(), taskId, reportId, actor.tenantId(), actor.userId(), preview.goal(),
                preview.applicationVersion(), preview.financialVersion(), scope, targetDigest, now, now.plusSeconds(ExpenseAgentRun.AUTHORIZATION_SECONDS)));
        repository.create(run, login); return view(run);
    }
    /** 历史读取不暴露后台认证引用，即使已停止也保留原步骤。 */
    @Transactional(readOnly = true)
    public View get(UUID reportId, UUID taskId) {
        handling.authorizeTask(reportId, taskId);
        return repository.find(actors.actor().tenantId(), taskId, false).map(value -> view(value.run())).orElse(null);
    }
    /** 人工回答和未知模型执行分别明确确认，不隐式重试原模型请求。 */
    @Transactional
    public View resume(UUID reportId, UUID taskId, long expectedVersion, String answer, boolean acknowledgeUnknown) {
        handling.authorizeTask(reportId, taskId); var stored = required(taskId, true); requireLive(stored);
        var run = stored.run(); var previous = run.state().version(); run.resume(expectedVersion, answer, acknowledgeUnknown, Instant.now());
        repository.save(run, previous); return view(run);
    }
    /** 用户可以撤销后续自动行为，已登记子任务仍保留其独立人工确认。 */
    @Transactional
    public View cancel(UUID reportId, UUID taskId, long expectedVersion) {
        handling.authorizeTask(reportId, taskId); var run = required(taskId, true).run(); run.requireVersion(expectedVersion);
        if (!run.active()) throw new DomainException("AGENT_RUN_STATE_CONFLICT", "Agent is already terminal");
        run.stop(ExpenseAgentRun.Status.CANCELLED, "本人已停止后续自动执行。", Instant.now()); repository.save(run, expectedVersion); return view(run);
    }
    /** 原子任务必须在原排队事务中绑定，不允许先排队再异步猜测关联。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void bind(UUID reportId, UUID taskId, ExpenseAgentRun.Action action, UUID invoiceId, UUID childId) {
        handling.requireReadable(reportId, taskId, null);
        var stored = repository.find(actors.actor().tenantId(), taskId, true).orElse(null);
        if (stored == null) return;
        requireLive(stored); var run = stored.run(); long previous = run.state().version(); run.bind(action, invoiceId, childId, Instant.now()); repository.save(run, previous);
    }
    /** 一次轮询只推进一个原步骤；所有网络阶段位于短事务之外。 */
    @Transactional(propagation = Propagation.NEVER)
    public void advance(UUID taskId) {
        var claimed = transactions.execute(status -> {
            var stored = required(taskId, true); var run = stored.run(); long previous = run.state().version();
            if (!run.active()) return null;
            try { requireLive(stored); } catch (DomainException denied) {
                run.stop(ExpenseAgentRun.Status.FAILED, denied.code(), Instant.now()); repository.save(run, previous); return null;
            }
            run.recover(Instant.now());
            String input = null;
            if (run.state().status() == ExpenseAgentRun.Status.READY) {
                input = modelInput(run);
                if (input.getBytes(StandardCharsets.UTF_8).length > AssistInputService.MAX_INPUT_BYTES) run.stop(ExpenseAgentRun.Status.LIMIT_REACHED, "模型上下文已达到 64 KiB 上限。", Instant.now());
                else if (run.plan(Instant.now(), configuration.getTimeoutSeconds() + MODEL_LEASE_GRACE_SECONDS) != null) repository.modelCall(run, input);
            } else if (run.state().status() == ExpenseAgentRun.Status.TOOL_READY) run.startTool(Instant.now());
            if (run.state().version() != previous) repository.save(run, previous);
            else if (run.state().status() != ExpenseAgentRun.Status.WAITING_CHILD) return null;
            return new Claimed(run, input);
        });
        if (claimed == null) return;
        var run = claimed.run();
        try {
            switch (run.state().status()) {
                case MODEL_RUNNING -> {
                    if (claimed.input() == null) return;
                    // 外发临界点再次恢复原登录，数据库失败不能转换成已授权。
                    requireLive(required(taskId, false));
                    var decision = telemetry.execute(run.context().tenantId(), run.context().ownerId(), AgentExecutionUsage.Kind.HANDLING,
                            run.last().id(), run.last().createdAt(), () -> model.decide(run.context(), json.read(claimed.input(), com.fasterxml.jackson.databind.JsonNode.class)));
                    finish(run, current -> current.decided(decision, Instant.now()));
                }
                case TOOL_RUNNING -> {
                    requireLive(required(taskId, false)); var result = json.write(tools.execute(run));
                    finish(run, current -> current.observed(result, Instant.now()));
                }
                case WAITING_CHILD -> {
                    var child = tools.child(run);
                    if (child.status().equals("CONFIRMED")) finish(run, current -> current.childConfirmed(json.write(child.confirmed()), Instant.now()));
                    else if (child.status().equals("FAILED") || child.status().equals("DISMISSED")) finish(run, current -> current.childFailed(child.failure() == null ? "原任务已由本人放弃。" : child.failure(), Instant.now()));
                }
                default -> { }
            }
        } catch (RuntimeException failure) {
            finish(run, current -> current.stop(run.state().status() == ExpenseAgentRun.Status.MODEL_RUNNING
                    && !(failure instanceof DomainException)
                    && !(failure instanceof AssistModelPort.ModelFailure rejected && rejected.failure() == AssistRun.Failure.INVALID_MODEL_OUTPUT)
                    ? ExpenseAgentRun.Status.INTERRUPTED : ExpenseAgentRun.Status.FAILED,
                    failure instanceof DomainException domain ? domain.code() : failure instanceof AssistModelPort.ModelFailure modelFailure ? modelFailure.failure().name() : "AGENT_EXECUTION_FAILED", Instant.now()));
        }
    }
    /** 只有原登录解析确定失效时调用；认证数据库故障保留原状态等待恢复。 */
    @Transactional
    public void authenticationLost(String tenant, UUID taskId) {
        var stored = repository.find(tenant, taskId, true).orElse(null);
        if (stored == null || !stored.run().active()) return;
        var run = stored.run(); long previous = run.state().version();
        run.stop(ExpenseAgentRun.Status.FAILED, "UNAUTHENTICATED", Instant.now()); repository.save(run, previous);
    }
    private void finish(ExpenseAgentRun claimed, java.util.function.Consumer<ExpenseAgentRun> transition) {
        transactions.executeWithoutResult(status -> {
            var stored = required(claimed.context().taskId(), true); var current = stored.run();
            if (current.state().version() != claimed.state().version()) return;
            long previous = current.state().version();
            try { requireLive(stored); transition.accept(current); }
            catch (DomainException denied) { current.stop(ExpenseAgentRun.Status.FAILED, denied.code(), Instant.now()); }
            repository.save(current, previous);
        });
    }
    private void requireLive(ExpenseAgentRepository.Stored stored) {
        // 原读取用例在校验失败时会标记事务回滚；独立只读事务使外层仍能提交停止原因。
        authorizationTransactions.executeWithoutResult(status -> validateAuthorization(stored));
    }
    private void validateAuthorization(ExpenseAgentRepository.Stored stored) {
        var c = stored.run().context(); var now = Instant.now();
        if (!c.deadline().isAfter(now)) throw new DomainException("AGENT_AUTHORIZATION_EXPIRED", "Agent authorization expired");
        var restored = authentication.resolve(stored.login(), c.tenantId(), c.ownerId(), now).orElseThrow(() -> new DomainException("UNAUTHENTICATED", "Original login was revoked"));
        if (!restored.equals(actors.actor())) throw new DomainException("FORBIDDEN", "Original actor changed");
        eligibility.requireActive(c.tenantId(), c.ownerId());
        configuration.requireAvailable(); if (!configuration.targetDigest(ExpenseAgentRun.PROMPT_VERSION).equals(c.targetDigest())) throw changed();
        var task = handling.requireReadable(c.reportId(), c.taskId(), null);
        if (task.state().applicationVersion() != c.applicationVersion() || task.state().financialVersion() != c.financialVersion()) throw changed();
    }
    private ExpenseAgentRepository.Stored required(UUID task, boolean lock) {
        return repository.find(actors.actor().tenantId(), task, lock).orElseThrow(() -> new DomainException("NOT_FOUND", "Expense agent not found"));
    }
    private String modelInput(ExpenseAgentRun run) { return json.write(Map.of("goal", run.context().goal(), "scope", run.context().scope(), "answers", run.state().answers(), "history", run.state().steps())); }
    private static View view(ExpenseAgentRun run) { var c = run.context(); return new View(c.id(), c.taskId(), c.reportId(), c.applicationVersion(), c.financialVersion(), c.scope(), c.deadline(), run.state()); }
    private static DomainException changed() { return new DomainException("AGENT_INPUT_CHANGED", "Expense agent authorization or input changed"); }
    /**
     * 发送预览不包含凭据；对后续白名单读取结果的发送范围一同授权。
     * @author owlzhangfq@gmail.com
     */
    public record Preview(String goal, ExpenseAgentRun.Scope scope, long applicationVersion, long financialVersion, String providerId,
            String model, String destination, String targetDigest, String consentDigest, Object sendableData) { }
    /**
     * 公开视图仅包含本单执行状态，原登录引用永不出站。
     * @author owlzhangfq@gmail.com
     */
    public record View(UUID id, UUID taskId, UUID reportId, long applicationVersion, long financialVersion, ExpenseAgentRun.Scope scope,
            Instant deadline, ExpenseAgentRun.State state) { }
    /**
     * 只有新领取的模型步骤携带已持久化输入。
     * @author owlzhangfq@gmail.com
     */
    private record Claimed(ExpenseAgentRun run, String input) { }
}
