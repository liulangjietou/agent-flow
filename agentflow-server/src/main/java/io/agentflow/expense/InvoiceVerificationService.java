package io.agentflow.expense;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceGatewayConfiguration;
import io.agentflow.finance.FinanceResult;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import static io.agentflow.expense.InvoiceVerificationJob.Failure;

/**
 * 个人验票的短事务编排；排队、领取、结果提交均不调用外部服务或读取文件字节。
 * @author owlzhangfq@gmail.com
 */
@Service
public class InvoiceVerificationService {
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;
    private static final int COMPLETION_GRACE_SECONDS = 30;
    private final CurrentActor actors;
    private final InvoiceRepository invoices;
    private final JdbcInvoiceOriginalRepository originals;
    private final JdbcInvoiceVerificationRepository jobs;
    private final FinanceGatewayConfiguration configuration;

    /** 跨聚合版本和任务审计在原数据源事务内提交。 */
    public InvoiceVerificationService(CurrentActor actors, InvoiceRepository invoices, JdbcInvoiceOriginalRepository originals,
            JdbcInvoiceVerificationRepository jobs, FinanceGatewayConfiguration configuration) {
        this.actors = actors; this.invoices = invoices; this.originals = originals; this.jobs = jobs; this.configuration = configuration;
    }

    /** 员工确认实际目标后才能排队；不公开网关凭据和文件路径。 */
    public Options options(UUID invoiceId) {
        var invoice = owned(invoiceId); var original = originals.find(invoice.tenantId(), invoiceId).orElseThrow(InvoiceVerificationService::notFound);
        var destination = configuration.destination(invoice.tenantId()).orElse(null);
        String unavailable = original.status() != InvoiceOriginal.Status.READY ? "INVOICE_ORIGINAL_NOT_READY"
                : destination == null ? "FINANCE_GATEWAY_UNAVAILABLE" : null;
        return new Options(invoice.version(), unavailable == null, unavailable,
                destination == null ? null : destination.baseUri().getAuthority(), destination == null ? null : destination.digest(invoice.tenantId()),
                invoice.facts() == null ? null : invoice.facts().legalEntityId());
    }

    /** 幂等响应只保存任务编号，不把票面或原件复制进响应缓存。 */
    @Transactional
    public Receipt queue(UUID invoiceId, QueueInput request) {
        var actor = actors.actor();
        jobs.lockInvoice(actor.tenantId(), invoiceId);
        var invoice = owned(invoiceId);
        if (invoice.version() != request.expectedInvoiceVersion()) throw new DomainException("CONCURRENCY_CONFLICT", "Invoice changed before verification was requested");
        var original = originals.find(actor.tenantId(), invoiceId).orElseThrow(InvoiceVerificationService::notFound); original.requireReady();
        if (invoice.facts() != null && !invoice.facts().legalEntityId().equals(request.legalEntityId())) {
            throw new DomainException("INVOICE_TITLE_MISMATCH", "Confirmed invoice buyer cannot be replaced by a verification request");
        }
        var destination = configuration.destination(actor.tenantId()).orElseThrow(() -> new DomainException("FINANCE_GATEWAY_UNAVAILABLE", "Invoice verification is not configured for this tenant"));
        if (!destination.digest(actor.tenantId()).equals(request.targetDigest())) throw new DomainException("FINANCE_TARGET_CHANGED", "Refresh the verification destination before queuing");
        if (jobs.active(actor.tenantId(), invoiceId)) throw new DomainException("INVOICE_VERIFICATION_ACTIVE", "Invoice already has an active verification job");
        var input = new InvoiceVerificationJob.Input(UUID.randomUUID(), actor.tenantId(), invoiceId, actor.userId(), invoice.version(),
                request.legalEntityId(), original.id(), original.sha256(), request.targetDigest());
        var job = InvoiceVerificationJob.queue(input, time(Instant.now())); jobs.create(job);
        return new Receipt(input.id());
    }

    /** 单条和列表读取均复用本人票夹权限；管理员不绕过归属。 */
    public View get(UUID invoiceId, UUID jobId) {
        var invoice = owned(invoiceId);
        var job = jobs.find(invoice.tenantId(), jobId).filter(value -> value.input().invoiceId().equals(invoiceId)).orElseThrow(InvoiceVerificationService::notFound);
        return view(job);
    }

    /** 限制参数和值，不支持借由身份参数枚举他人任务。 */
    public Page list(UUID invoiceId, Map<String, String> parameters) {
        var invoice = owned(invoiceId);
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
        var found = jobs.list(invoice.tenantId(), invoiceId, before, limit + 1);
        var items = found.stream().limit(limit).map(InvoiceVerificationService::view).toList();
        return new Page(items, found.size() > limit ? items.get(items.size() - 1).id() : null);
    }

    /** 每次领取在同一发票行锁下重读；过期运行只结算超时，不重新外发。 */
    @Transactional
    public InvoiceVerificationJob claim(String tenant, UUID id, Instant at) {
        var found = jobs.find(tenant, id).orElse(null); if (found == null) return null;
        jobs.lockInvoice(tenant, found.input().invoiceId());
        var job = jobs.find(tenant, id).orElseThrow(); Instant now = time(at);
        if (job.expired(now)) { jobs.update(job.unavailable(Failure.TIMEOUT, now)); return null; }
        if (job.status() != InvoiceVerificationJob.Status.QUEUED) return null;
        var destination = configuration.destination(tenant).orElse(null);
        long leaseSeconds = COMPLETION_GRACE_SECONDS + (destination == null ? 0 : destination.timeout().toSeconds());
        job = job.start(now, now.plusSeconds(leaseSeconds)); jobs.update(job);
        Failure unavailable = contextFailure(job, destination);
        if (unavailable != null) { jobs.update(job.unavailable(unavailable, now)); return null; }
        return job;
    }

    /** 只有租约、输入和发票版本仍一致时，任务结果与发票审计一起提交。 */
    @Transactional
    public void finish(InvoiceVerificationJob claimed, FinanceResult<Invoice.VerifiedFacts> result, Instant at) {
        var job = current(claimed); if (job == null) return;
        Instant now = time(at);
        if (job.expired(now)) { jobs.update(job.unavailable(Failure.TIMEOUT, now)); return; }
        Failure unavailable = contextFailure(job, configuration.destination(job.input().tenantId()).orElse(null));
        if (unavailable != null) { jobs.update(job.unavailable(unavailable, now)); return; }
        var invoice = invoices.find(job.input().tenantId(), job.input().invoiceId()).orElseThrow();
        InvoiceVerificationJob completed;
        if (result instanceof FinanceResult.Success<Invoice.VerifiedFacts> success) {
            var facts = success.value();
            if (!job.input().legalEntityId().equals(facts.legalEntityId()) || facts.verifiedAt().isAfter(at) || !facts.validUntil().isAfter(at)) {
                jobs.update(job.unavailable(Failure.INVALID_RESPONSE, now)); return;
            }
            try { invoice.verified(job.input().invoiceVersion(), facts); }
            catch (DomainException invalid) { jobs.update(job.unavailable(Failure.INVALID_RESPONSE, now)); return; }
            invoices.update(invoice, job.input().invoiceVersion(), job.input().ownerId(), "VERIFICATION_SUCCEEDED");
            completed = job.succeed(invoice.version(), now);
        } else if (result instanceof FinanceResult.Rejected<Invoice.VerifiedFacts> rejected) {
            var reason = rejection(rejected.reason());
            if (reason == null) { jobs.update(job.unavailable(Failure.INVALID_RESPONSE, now)); return; }
            Long resultingVersion = null;
            if (reason != InvoiceVerificationJob.Rejection.LEGAL_ENTITY_UNAVAILABLE) {
                invoice.invalidated(job.input().invoiceVersion(), reason.name(), at);
                invoices.update(invoice, job.input().invoiceVersion(), job.input().ownerId(), "VERIFICATION_REJECTED");
                resultingVersion = invoice.version();
            }
            completed = job.reject(reason, resultingVersion, now);
        } else if (result instanceof FinanceResult.Unavailable<Invoice.VerifiedFacts> failed) {
            completed = job.unavailable(gatewayFailure(failed.failure()), now);
        } else completed = job.unavailable(Failure.INVALID_RESPONSE, now);
        jobs.update(completed);
    }

    /** 文件损坏或本地执行异常只更新任务，不能伪造票面无效结论。 */
    @Transactional
    public void fail(InvoiceVerificationJob claimed, Failure failure, Instant at) {
        var job = current(claimed); if (job != null) jobs.update(job.unavailable(failure, time(at)));
    }

    private InvoiceVerificationJob current(InvoiceVerificationJob claimed) {
        jobs.lockInvoice(claimed.input().tenantId(), claimed.input().invoiceId());
        return jobs.find(claimed.input().tenantId(), claimed.input().id())
                .filter(value -> value.status() == InvoiceVerificationJob.Status.RUNNING && value.version() == claimed.version()
                        && value.input().equals(claimed.input())).orElse(null);
    }
    private Failure contextFailure(InvoiceVerificationJob job, FinanceGatewayConfiguration.Destination destination) {
        if (destination == null) return Failure.NOT_CONFIGURED;
        var input = job.input();
        if (!destination.digest(input.tenantId()).equals(input.targetDigest())) return Failure.TARGET_CHANGED;
        var invoice = invoices.find(input.tenantId(), input.invoiceId()).orElse(null);
        if (invoice == null || invoice.version() != input.invoiceVersion() || !invoice.ownerId().equals(input.ownerId())) return Failure.INVOICE_CHANGED;
        var original = originals.find(input.tenantId(), input.invoiceId()).orElse(null);
        if (original == null || original.status() != InvoiceOriginal.Status.READY || !original.id().equals(input.originalId())
                || !original.sha256().equals(input.originalDigest())) return Failure.ORIGINAL_UNAVAILABLE;
        return null;
    }
    private Invoice owned(UUID id) { return invoices.find(actors.actor().tenantId(), id).filter(invoice -> invoice.ownerId().equals(actors.actor().userId())).orElseThrow(InvoiceVerificationService::notFound); }
    private static Instant time(Instant value) { return Objects.requireNonNull(value).truncatedTo(ChronoUnit.MILLIS); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Invoice verification context not found"); }
    private static DomainException invalidQuery() { return new DomainException("INVALID_INVOICE_QUERY", "Invoice verification query is invalid"); }
    private static InvoiceVerificationJob.Rejection rejection(FinanceResult.Reason reason) {
        return switch (reason) {
            case INVOICE_INVALID -> InvoiceVerificationJob.Rejection.INVOICE_INVALID;
            case INVOICE_CANCELLED -> InvoiceVerificationJob.Rejection.INVOICE_CANCELLED;
            case INVOICE_BUYER_MISMATCH -> InvoiceVerificationJob.Rejection.INVOICE_BUYER_MISMATCH;
            case LEGAL_ENTITY_UNAVAILABLE -> InvoiceVerificationJob.Rejection.LEGAL_ENTITY_UNAVAILABLE;
            default -> null;
        };
    }
    private static Failure gatewayFailure(FinanceResult.Failure failure) {
        return switch (failure) {
            case NOT_CONFIGURED -> Failure.NOT_CONFIGURED;
            case TARGET_CHANGED -> Failure.TARGET_CHANGED;
            case TIMEOUT -> Failure.TIMEOUT;
            case CONNECTION -> Failure.CONNECTION;
            case AUTHENTICATION -> Failure.AUTHENTICATION;
            case REMOTE_FAILURE -> Failure.REMOTE_FAILURE;
            case INVALID_RESPONSE -> Failure.INVALID_RESPONSE;
            case RESPONSE_TOO_LARGE -> Failure.RESPONSE_TOO_LARGE;
        };
    }
    private static View view(InvoiceVerificationJob value) {
        return new View(value.input().id(), value.version(), value.status(), value.input().invoiceVersion(), value.resultingInvoiceVersion(),
                value.input().legalEntityId(), value.createdAt(), value.startedAt(), value.completedAt(), value.rejection(), value.failure());
    }

    /**
     * 本人明确选择法人及目标，其他原件输入由服务端取得。
     * @author owlzhangfq@gmail.com
     */
    public record QueueInput(@NotNull @Min(1) Long expectedInvoiceVersion, @NotNull UUID legalEntityId,
                             @NotNull @Pattern(regexp = "[a-f0-9]{64}") String targetDigest) {
        /** 写入边界拒绝额外结论或地址字段，不改变其他接口既有 JSON 兼容策略。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown invoice verification request field"); }
    }
    /**
     * 最小回执支持幂等回放，随后按当前权限查询结果。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID id) { }
    /**
     * 实际验票目的地和当前输入版本供发起人核对。
     * @author owlzhangfq@gmail.com
     */
    public record Options(long invoiceVersion, boolean enabled, String unavailableCode, String destination, String targetDigest, UUID confirmedLegalEntityId) { }
    /**
     * 只返回明确状态与稳定分类，不包含远端响应正文、原件字节或目标凭据。
     * @author owlzhangfq@gmail.com
     */
    public record View(UUID id, long version, InvoiceVerificationJob.Status status, long invoiceVersion, Long resultingInvoiceVersion,
                       UUID legalEntityId, Instant createdAt, Instant startedAt, Instant completedAt,
                       InvoiceVerificationJob.Rejection rejection, Failure failure) { }
    /**
     * 有界任务历史；终态通过新任务重试，原任务保持不可变。
     * @author owlzhangfq@gmail.com
     */
    public record Page(List<View> items, UUID nextBeforeId) { }
}
