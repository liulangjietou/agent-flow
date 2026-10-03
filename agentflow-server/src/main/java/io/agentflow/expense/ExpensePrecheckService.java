package io.agentflow.expense;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceGatewayConfiguration;
import io.agentflow.organization.InitiatorContext;
import io.agentflow.organization.OrganizationInitiatorDirectory;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static io.agentflow.expense.ExpensePrecheckJob.*;

/**
 * 预检的本人授权与短事务编排；后台身份仅用于读取原申请人的固定任职。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePrecheckService {
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;
    private final CurrentActor actors;
    private final ExpenseReportRepository reports;
    private final ApprovalApplicationFacade applications;
    private final ApplicationRepository applicationRepository;
    private final OrganizationInitiatorDirectory initiators;
    private final FinanceGatewayConfiguration configuration;
    private final JdbcExpensePrecheckRepository jobs;
    private final ExpensePrecheckResources resources;
    private final ExpensePolicyConfiguration policyConfiguration;
    private final int timeoutSeconds;

    /** 身份检查不复用管理员读取权限，所有外部事实由独立执行器获取。 */
    public ExpensePrecheckService(CurrentActor actors, ExpenseReportRepository reports, ApprovalApplicationFacade applications,
            ApplicationRepository applicationRepository, OrganizationInitiatorDirectory initiators, FinanceGatewayConfiguration configuration,
            JdbcExpensePrecheckRepository jobs, ExpensePrecheckResources resources, ExpensePolicyConfiguration policyConfiguration,
            @Value("${agentflow.expenses.precheck-timeout-seconds:300}") int timeoutSeconds) {
        if (timeoutSeconds < 15 || timeoutSeconds > 900) throw new IllegalArgumentException("Expense precheck timeout must be between 15 and 900 seconds");
        this.actors = actors; this.reports = reports; this.applications = applications; this.applicationRepository = applicationRepository;
        this.initiators = initiators; this.configuration = configuration; this.jobs = jobs; this.resources = resources; this.timeoutSeconds = timeoutSeconds;
        this.policyConfiguration = policyConfiguration;
    }

    /** 返回实际目标和双版本，不把配置可用解释为费用已经通过。 */
    public Options options(UUID reportId) {
        var report = owned(reportId); var application = applications.requireApplicant(report.applicationId());
        var destination = configuration.destination(report.tenantId()).orElse(null);
        String unavailable = !application.editable() ? "APPLICATION_NOT_EDITABLE" : report.content().lines().isEmpty()
                ? "EXPENSE_LINES_REQUIRED" : destination == null ? "FINANCE_GATEWAY_UNAVAILABLE" : null;
        return new Options(application.version(), report.version(), unavailable == null, unavailable,
                destination == null ? null : destination.baseUri().getAuthority(), destination == null ? null : destination.digest(report.tenantId()),
                jobs.latestId(report.tenantId(), reportId).orElse(null));
    }

    /** 只登记明确选择的任职和输入版本，排队事务不访问外部系统。 */
    @Transactional
    public Receipt queue(UUID reportId, QueueInput request) {
        var actor = actors.actor(); reports.lock(actor.tenantId(), reportId);
        var report = owned(reportId); var application = applications.requireApplicant(report.applicationId());
        application.requireEditable(request.applicationVersion());
        if (report.version() != request.financialVersion() || report.rounds().size() + 1 != application.nextSubmissionRound()) throw changed();
        if (report.content().lines().isEmpty()) throw new DomainException("EXPENSE_LINES_REQUIRED", "Expense precheck requires at least one line");
        var initiator = initiators.snapshot(actor, request.initiatorAppointmentId());
        if (!initiator.legalEntityId().equals(report.content().legalEntityId())) throw new DomainException("EXPENSE_LEGAL_ENTITY_MISMATCH", "Selected appointment and expense legal entity must match");
        var destination = configuration.destination(actor.tenantId()).orElseThrow(() -> new DomainException("FINANCE_GATEWAY_UNAVAILABLE", "Expense precheck is not configured for this tenant"));
        if (!destination.digest(actor.tenantId()).equals(request.targetDigest())) throw new DomainException("FINANCE_TARGET_CHANGED", "Refresh the finance destination before queuing");
        if (jobs.active(actor.tenantId(), reportId)) throw new DomainException("EXPENSE_PRECHECK_ACTIVE", "Expense report already has an active precheck");
        var input = new Input(UUID.randomUUID(), actor.tenantId(), reportId, report.applicationId(), actor.userId(), application.version(), report.version(),
                application.nextSubmissionRound(), jobs.nextAttempt(actor.tenantId(), reportId), initiator, request.accountingDate(), request.targetDigest());
        jobs.create(ExpensePrecheckJob.queue(input, time(Instant.now()))); return new Receipt(input.id());
    }

    /** 只向本人展示候选金额；账户内部引用、摘要和外部响应正文均不返回。 */
    public View get(UUID reportId, UUID jobId) {
        var report = owned(reportId);
        var job = jobs.find(report.tenantId(), jobId).filter(value -> value.input().reportId().equals(reportId)).orElseThrow(ExpensePrecheckService::notFound);
        var evidence = job.result() == null ? null : job.result().evidence();
        String unavailable = job.status() == Status.READY ? readyFailure(job, report, Instant.now()) : "PRECHECK_NOT_READY";
        return new View(summary(job), unavailable == null, unavailable, job.input().initiator(), job.input().accountingDate(),
                evidence == null ? null : evidence.rateDate(), evidence == null ? null : evidence.validUntil(),
                evidence == null ? null : ExpenseResponse.FinancialRound.from(evidence.preview()), job.result() == null ? List.of() : job.result().findings());
    }

    /** 历史分页只列轻量状态，不在一页内复制多份完整费用明细。 */
    public Page list(UUID reportId, Map<String, String> parameters) {
        var report = owned(reportId);
        if (!Set.of("limit", "beforeId").containsAll(parameters.keySet())) throw invalidQuery();
        int limit = DEFAULT_LIMIT; UUID before = null;
        try {
            if (parameters.containsKey("limit")) {
                String value = parameters.get("limit"); if (!value.matches("[1-9][0-9]{0,2}")) throw invalidQuery();
                limit = Integer.parseInt(value); if (limit > MAX_LIMIT) throw invalidQuery();
            }
            if (parameters.containsKey("beforeId")) {
                String value = parameters.get("beforeId"); before = UUID.fromString(value);
                if (!before.toString().equals(value)) throw invalidQuery();
            }
        } catch (IllegalArgumentException malformed) { throw invalidQuery(); }
        var found = jobs.list(report.tenantId(), reportId, before, limit + 1);
        var items = found.stream().limit(limit).map(ExpensePrecheckService::summary).toList();
        return new Page(items, found.size() > limit ? items.get(items.size() - 1).id() : null);
    }

    /** 排队恢复可以执行，已领取后崩溃则到期超时，不自动重发。 */
    @Transactional
    public ExpensePrecheckJob claim(String tenant, UUID id, Instant at) {
        var found = jobs.find(tenant, id).orElse(null); if (found == null) return null;
        reports.lock(tenant, found.input().reportId());
        var job = jobs.find(tenant, id).orElseThrow(); Instant now = time(at);
        if (job.expired(now)) { jobs.update(job.finish(Result.unavailable(Stage.SYSTEM, "TIMEOUT"), now)); return null; }
        if (job.status() != Status.QUEUED) return null;
        job = job.start(now, now.plusSeconds(timeoutSeconds)); jobs.update(job);
        String failure = contextFailure(job);
        if (failure != null) { jobs.update(job.finish(Result.unavailable(Stage.CONTEXT, failure), now)); return null; }
        return job;
    }

    /** 落库前复核申请、任职、目标和资源；网络等待期间的任何修改都不能混入成功。 */
    @Transactional
    public void finish(ExpensePrecheckJob claimed, Result result, Instant at) {
        reports.lock(claimed.input().tenantId(), claimed.input().reportId());
        var job = jobs.find(claimed.input().tenantId(), claimed.input().id()).orElseThrow();
        if (job.status() != Status.RUNNING || job.version() != claimed.version() || !job.input().equals(claimed.input())) return;
        Instant now = time(at); String failure = contextFailure(job);
        if (failure != null) result = Result.unavailable(Stage.CONTEXT, failure);
        else if (result.evidence() != null) {
            if (!policyConfiguration.current(job.input().tenantId(), result.evidence().policySelection())) result = Result.unavailable(Stage.POLICY, "POLICY_CONFIGURATION_CHANGED");
            else if (!result.evidence().validUntil().isAfter(at)) result = Result.unavailable(Stage.CONTEXT, "FACTS_EXPIRED");
            else if (!resources.current(reports.find(job.input().tenantId(), job.input().reportId()).orElseThrow(), result.evidence())) {
                result = Result.unavailable(Stage.RESOURCES, "RESOURCES_CHANGED");
            }
        }
        jobs.update(job.finish(result, now));
    }

    /** 正式提交和页面提示共用有效性判断；不从历史 READY 回退到旧事实。 */
    public String readyFailure(ExpensePrecheckJob job, ExpenseReport report, Instant now) {
        if (job.status() != Status.READY) return "PRECHECK_NOT_READY";
        if (jobs.latestAttempt(job.input().tenantId(), job.input().reportId()) != job.input().attempt()) return "PRECHECK_SUPERSEDED";
        if (!job.result().evidence().validUntil().isAfter(now)) return "FACTS_EXPIRED";
        if (!policyConfiguration.current(job.input().tenantId(), job.result().evidence().policySelection())) return "POLICY_CONFIGURATION_CHANGED";
        String failure = contextFailure(job); if (failure != null) return failure;
        return resources.current(report, job.result().evidence()) ? null : "RESOURCES_CHANGED";
    }

    private String contextFailure(ExpensePrecheckJob job) {
        var input = job.input(); var destination = configuration.destination(input.tenantId()).orElse(null);
        if (destination == null) return "NOT_CONFIGURED";
        if (!destination.digest(input.tenantId()).equals(input.targetDigest())) return "TARGET_CHANGED";
        var report = reports.find(input.tenantId(), input.reportId()).orElse(null);
        var application = applicationRepository.findById(input.tenantId(), input.applicationId()).orElse(null);
        if (report == null || report.version() != input.financialVersion() || application == null || !application.editable()
                || application.version() != input.applicationVersion() || application.nextSubmissionRound() != input.roundNo()) return "CONTEXT_CHANGED";
        var current = initiators.findCurrent(new Actor(input.tenantId(), input.employeeId(), Set.of()), input.initiator().appointmentId());
        if (current.filter(input.initiator()::equals).isEmpty()) return "INITIATOR_CHANGED";
        return null;
    }
    private ExpenseReport owned(UUID id) { return reports.find(actors.actor().tenantId(), id).filter(value -> value.employeeId().equals(actors.actor().userId())).orElseThrow(ExpensePrecheckService::notFound); }
    private static Summary summary(ExpensePrecheckJob job) { return new Summary(job.input().id(), job.version(), job.status(), job.input().applicationVersion(), job.input().financialVersion(), job.input().attempt(), job.createdAt(), job.startedAt(), job.completedAt()); }
    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MILLIS); }
    private static DomainException changed() { return new DomainException("CONCURRENCY_CONFLICT", "Expense or application version changed"); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Expense precheck context not found"); }
    private static DomainException invalidQuery() { return new DomainException("INVALID_EXPENSE_QUERY", "Expense precheck query is invalid"); }

    /**
     * 申请人只能选择输入上下文，不能提交核算或预算结论。
     * @author owlzhangfq@gmail.com
     */
    public record QueueInput(@NotNull @Positive Long applicationVersion, @NotNull @Positive Long financialVersion,
            @NotNull UUID initiatorAppointmentId, @NotNull LocalDate accountingDate, @NotNull @Pattern(regexp = "[a-f0-9]{64}") String targetDigest) {
        /** 未知字段在此入口拒绝，避免误认为客户端金额或通过标记已经生效。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown expense precheck request field"); }
    }
    /**
     * 幂等缓存只记录任务标识，不缓存敏感财务结果。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID id) { }
    /**
     * 排队入口可用性和真实目标。
     * @author owlzhangfq@gmail.com
     */
    public record Options(long applicationVersion, long financialVersion, boolean enabled, String unavailableCode, String destination, String targetDigest, UUID latestPrecheckId) { }
    /**
     * 有界历史的轻量状态。
     * @author owlzhangfq@gmail.com
     */
    public record Summary(UUID id, long version, Status status, long applicationVersion, long financialVersion, long attempt, Instant createdAt, Instant startedAt, Instant completedAt) { }
    /**
     * READY 的历史状态与现在是否仍可使用分别展示。
     * @author owlzhangfq@gmail.com
     */
    public record View(Summary job, boolean usable, String unavailableCode, InitiatorContext initiator, LocalDate accountingDate,
            LocalDate rateDate, Instant validUntil, ExpenseResponse.FinancialRound preview, List<Finding> findings) { }
    /**
     * 下一页游标只能用于本人当前单据。
     * @author owlzhangfq@gmail.com
     */
    public record Page(List<Summary> items, UUID nextBeforeId) { }
}
