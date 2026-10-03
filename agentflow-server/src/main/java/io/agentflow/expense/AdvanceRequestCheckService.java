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
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static io.agentflow.expense.AdvanceRequestCheck.*;

/**
 * 预检的本人授权与短事务编排；后台身份仅用于读取原申请人的固定任职。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AdvanceRequestCheckService {
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;
    private final CurrentActor actors;
    private final AdvanceRequestRepository requests;
    private final ApprovalApplicationFacade applications;
    private final ApplicationRepository applicationRepository;
    private final OrganizationInitiatorDirectory initiators;
    private final FinanceGatewayConfiguration configuration;
    private final JdbcAdvanceRequestCheckRepository jobs;
    private final int timeoutSeconds;
    private final AdvanceOverdueConfiguration overdueConfiguration;
    private final AdvanceOverdueChecks overdue;

    /** 身份检查不复用管理员读取权限，所有外部事实由独立执行器获取。 */
    public AdvanceRequestCheckService(CurrentActor actors, AdvanceRequestRepository requests, ApprovalApplicationFacade applications,
            ApplicationRepository applicationRepository, OrganizationInitiatorDirectory initiators, FinanceGatewayConfiguration configuration,
            JdbcAdvanceRequestCheckRepository jobs, AdvanceOverdueConfiguration overdueConfiguration, AdvanceOverdueChecks overdue,
            @Value("${agentflow.advance-requests.precheck-timeout-seconds:300}") int timeoutSeconds) {
        if (timeoutSeconds < 15 || timeoutSeconds > 900) throw new IllegalArgumentException("Advance request check timeout must be between 15 and 900 seconds");
        this.actors = actors; this.requests = requests; this.applications = applications; this.applicationRepository = applicationRepository;
        this.initiators = initiators; this.configuration = configuration; this.jobs = jobs; this.timeoutSeconds = timeoutSeconds;
        this.overdueConfiguration = overdueConfiguration; this.overdue = overdue;
    }

    /** 返回实际目标和双版本，不把配置可用解释为借款申请已经通过。 */
    public Options options(UUID requestId) {
        var advance = owned(requestId); var application = applications.requireApplicant(advance.applicationId());
        var destination = configuration.destination(advance.tenantId()).orElse(null);
        String unavailable = !application.editable() ? "APPLICATION_NOT_EDITABLE" : destination == null ? "FINANCE_GATEWAY_UNAVAILABLE" : null;
        return new Options(application.version(), advance.version(), unavailable == null, unavailable,
                destination == null ? null : destination.baseUri().getAuthority(), destination == null ? null : destination.digest(advance.tenantId()),
                jobs.latestId(advance.tenantId(), requestId).orElse(null), overdueConfiguration.policy(advance.tenantId()));
    }

    /** 只登记明确选择的任职和输入版本，排队事务不访问外部系统。 */
    @Transactional
    public Receipt queue(UUID requestId, QueueInput request) {
        var actor = actors.actor(); owned(requestId); requests.lock(actor.tenantId(), requestId);
        var advance = owned(requestId); var application = applications.requireApplicant(advance.applicationId());
        application.requireEditable(request.applicationVersion());
        if (advance.version() != request.requestVersion() || advance.rounds().size() + 1 != application.nextSubmissionRound()) throw changed();
        var initiator = initiators.snapshot(actor, request.initiatorAppointmentId());
        if (!initiator.legalEntityId().equals(advance.content().legalEntityId())) throw new DomainException("ADVANCE_INITIATOR_MISMATCH", "Selected appointment and advance legal entity must match");
        var destination = configuration.destination(actor.tenantId()).orElseThrow(() -> new DomainException("FINANCE_GATEWAY_UNAVAILABLE", "Advance request check is not configured for this tenant"));
        if (!destination.digest(actor.tenantId()).equals(request.targetDigest())) throw new DomainException("FINANCE_TARGET_CHANGED", "Refresh the finance destination before queuing");
        if (jobs.active(actor.tenantId(), requestId)) throw new DomainException("ADVANCE_REQUEST_CHECK_ACTIVE", "Advance request already has an active precheck");
        var input = new Input(UUID.randomUUID(), actor.tenantId(), requestId, advance.applicationId(), actor.userId(), application.version(), advance.version(),
                application.nextSubmissionRound(), jobs.nextAttempt(actor.tenantId(), requestId), initiator, request.targetDigest());
        jobs.create(AdvanceRequestCheck.queue(input, time(Instant.now()))); return new Receipt(input.id());
    }

    /** 只向本人展示候选金额；完整目录和外部响应正文均不返回。 */
    public View get(UUID requestId, UUID jobId) {
        var advance = owned(requestId);
        var job = jobs.find(advance.tenantId(), jobId).filter(value -> value.input().requestId().equals(requestId)).orElseThrow(AdvanceRequestCheckService::notFound);
        var evidence = job.result() == null ? null : job.result().evidence();
        String unavailable = job.status() == Status.READY ? readyFailure(job, advance, Instant.now()) : "PRECHECK_NOT_READY";
        return new View(summary(job), unavailable == null, unavailable, job.input().initiator(),
                evidence == null ? null : evidence.validUntil(), evidence == null ? null : AdvanceRequestRoundView.of(evidence.preview()),
                job.result() == null ? null : job.result().code());
    }

    /** 历史分页只列轻量状态，不在一页内复制多份完整借款申请明细。 */
    public Page list(UUID requestId, Map<String, String> parameters) {
        var advance = owned(requestId);
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
        var found = jobs.list(advance.tenantId(), requestId, before, limit + 1);
        var items = found.stream().limit(limit).map(AdvanceRequestCheckService::summary).toList();
        return new Page(items, found.size() > limit ? items.get(items.size() - 1).id() : null);
    }

    /** 排队恢复可以执行，已领取后崩溃则到期超时，不自动重发。 */
    @Transactional
    public AdvanceRequestCheck claim(String tenant, UUID id, Instant at) {
        var found = jobs.find(tenant, id).orElse(null); if (found == null) return null;
        requests.lock(tenant, found.input().requestId());
        var job = jobs.find(tenant, id).orElseThrow(); Instant now = time(at);
        if (job.expired(now)) { jobs.update(job.finish(Result.unavailable("TIMEOUT"), now)); return null; }
        if (job.status() != Status.QUEUED) return null;
        job = job.start(now, now.plusSeconds(timeoutSeconds)); jobs.update(job);
        String failure = contextFailure(job);
        if (failure != null) { jobs.update(job.finish(Result.unavailable(failure), now)); return null; }
        return job;
    }

    /** 落库前复核申请、任职、目标；网络等待期间的任何修改都不能混入成功。 */
    @Transactional
    public void finish(AdvanceRequestCheck claimed, Result result, Instant at) {
        requests.lock(claimed.input().tenantId(), claimed.input().requestId());
        var job = jobs.find(claimed.input().tenantId(), claimed.input().id()).orElseThrow();
        if (job.status() != Status.RUNNING || job.version() != claimed.version() || !job.input().equals(claimed.input())) return;
        Instant now = time(at); String failure = contextFailure(job);
        if (failure != null) result = Result.unavailable(failure);
        else if (result.evidence() != null && !result.evidence().validUntil().isAfter(at)) result = Result.unavailable("FACTS_EXPIRED");
        jobs.update(job.finish(result, now));
    }

    /** 正式提交和页面提示共用有效性判断；不从历史 READY 回退到旧事实。 */
    public String readyFailure(AdvanceRequestCheck job, AdvanceRequest advance, Instant now) {
        if (job.status() != Status.READY) return "PRECHECK_NOT_READY";
        if (jobs.latestAttempt(job.input().tenantId(), job.input().requestId()) != job.input().attempt()) return "PRECHECK_SUPERSEDED";
        if (!job.result().evidence().validUntil().isAfter(now)) return "FACTS_EXPIRED";
        String failure = contextFailure(job); if (failure != null) return failure;
        var preview = job.result().evidence().preview();
        if (!preview.content().equals(advance.content())) return "CONTEXT_CHANGED";
        LocalDate date = LocalDate.ofInstant(now, ZoneId.of(preview.legalEntity().timeZone()));
        if (!date.equals(LocalDate.ofInstant(preview.submittedAt(), ZoneId.of(preview.legalEntity().timeZone())))) return "SUBMISSION_DATE_CHANGED";
        return overdue.failure(advance.tenantId(), advance.employeeId(), preview.legalEntity(), now);
    }

    private String contextFailure(AdvanceRequestCheck job) {
        var input = job.input(); var destination = configuration.destination(input.tenantId()).orElse(null);
        if (destination == null) return "NOT_CONFIGURED";
        if (!destination.digest(input.tenantId()).equals(input.targetDigest())) return "TARGET_CHANGED";
        var advance = requests.find(input.tenantId(), input.requestId()).orElse(null);
        var application = applicationRepository.findById(input.tenantId(), input.applicationId()).orElse(null);
        if (advance == null || advance.version() != input.requestVersion() || application == null || !application.editable()
                || application.version() != input.applicationVersion() || application.nextSubmissionRound() != input.roundNo()) return "CONTEXT_CHANGED";
        var current = initiators.findCurrent(new Actor(input.tenantId(), input.employeeId(), Set.of()), input.initiator().appointmentId());
        if (current.filter(input.initiator()::equals).isEmpty()) return "INITIATOR_CHANGED";
        return null;
    }
    private AdvanceRequest owned(UUID id) { return requests.find(actors.actor().tenantId(), id).filter(value -> value.employeeId().equals(actors.actor().userId())).orElseThrow(AdvanceRequestCheckService::notFound); }
    private static Summary summary(AdvanceRequestCheck job) { return new Summary(job.input().id(), job.version(), job.status(), job.input().applicationVersion(), job.input().requestVersion(), job.input().attempt(), job.createdAt(), job.startedAt(), job.completedAt()); }
    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MILLIS); }
    private static DomainException changed() { return new DomainException("CONCURRENCY_CONFLICT", "Advance request or application version changed"); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Advance request check context not found"); }
    private static DomainException invalidQuery() { return new DomainException("INVALID_EXPENSE_QUERY", "Advance request check query is invalid"); }

    /**
     * 申请人只能选择输入上下文，不能提交核算或预算结论。
     * @author owlzhangfq@gmail.com
     */
    public record QueueInput(@NotNull @Positive Long applicationVersion, @NotNull @Positive Long requestVersion,
            @NotNull UUID initiatorAppointmentId, @NotNull @Pattern(regexp = "[a-f0-9]{64}") String targetDigest) {
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
    public record Options(long applicationVersion, long requestVersion, boolean enabled, String unavailableCode, String destination, String targetDigest, UUID latestPrecheckId,
                          AdvanceOverdueConfiguration.Policy overduePolicy) { }
    /**
     * 有界历史的轻量状态。
     * @author owlzhangfq@gmail.com
     */
    public record Summary(UUID id, long version, Status status, long applicationVersion, long requestVersion, long attempt, Instant createdAt, Instant startedAt, Instant completedAt) { }
    /**
     * READY 的历史状态与现在是否仍可使用分别展示。
     * @author owlzhangfq@gmail.com
     */
    public record View(Summary job, boolean usable, String unavailableCode, InitiatorContext initiator,
            Instant validUntil, AdvanceRequestRoundView preview, String failureCode) { }

    /**
     * 下一页游标只能用于本人当前单据。
     * @author owlzhangfq@gmail.com
     */
    public record Page(List<Summary> items, UUID nextBeforeId) { }
}
