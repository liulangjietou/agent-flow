package io.agentflow.agent;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.expense.InvoiceOriginal;
import io.agentflow.expense.JdbcInvoiceOriginalRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static io.agentflow.agent.InvoiceExtractionSuggestion.Method.MODEL;

/**
 * 编排本人原件准备、持久执行和独立确认；文件读取及模型等待不进入写事务。
 * @author owlzhangfq@gmail.com
 */
@Service
public class InvoiceExtractionService {
    private static final int SOURCE_SECONDS = 30;
    private static final int COMPLETION_GRACE_SECONDS = 30;
    private final CurrentActor actors;
    private final JdbcInvoiceOriginalRepository originals;
    private final InvoiceExtractionSources sources;
    private final JdbcInvoiceExtractionRunRepository runs;
    private final AssistConfiguration configuration;
    private final InvoiceExtractionEligibility eligibility;

    /** 抽取状态只写自己的仓储，不依赖发票查验或财务写入端口。 */
    public InvoiceExtractionService(CurrentActor actors, JdbcInvoiceOriginalRepository originals, InvoiceExtractionSources sources,
            JdbcInvoiceExtractionRunRepository runs, AssistConfiguration configuration, InvoiceExtractionEligibility eligibility) {
        this.actors = actors; this.originals = originals; this.sources = sources; this.runs = runs;
        this.configuration = configuration; this.eligibility = eligibility;
    }

    /** 在幂等写事务之前核对完整原件；返回对象只能由服务端解析产生，不能由请求反序列化构造。 */
    @Transactional(propagation = Propagation.NEVER)
    public Prepared prepare(UUID invoiceId) {
        var original = owned(invoiceId);
        var source = sources.prepare(original);
        return new Prepared(original.tenantId(), original.ownerId(), source.input(), source.method());
    }

    /** 明确选择本地或外发方式；模型方式还必须确认相同目的地指纹，不能自动降级或改发。 */
    @Transactional
    public Receipt queue(Prepared prepared, InvoiceExtractionSuggestion.Method expectedMethod, String targetDigest) {
        var actor = actors.actor();
        if (!actor.tenantId().equals(prepared.tenant) || !actor.userId().equals(prepared.owner)) throw notFound();
        eligibility.lock(actor.tenantId());
        var original = originals.lock(actor.tenantId(), prepared.input.invoiceId());
        requireOwner(original); requireInput(original, prepared.input);
        if (prepared.method != expectedMethod || prepared.method != MODEL && targetDigest != null) throw inputChanged();
        requireTarget(prepared.method, targetDigest);
        if (runs.activeId(actor.tenantId(), original.invoiceId()).isPresent()) throw new DomainException("AGENT_RUN_ACTIVE", "Invoice extraction is already active");
        var run = new InvoiceExtractionRun(new InvoiceExtractionRun.Context(UUID.randomUUID(), actor.tenantId(), actor.userId(),
                Instant.now(), prepared.input, prepared.method, targetDigest));
        runs.create(run);
        return receipt(run);
    }

    /** 本人查看历史，其他员工和管理员都不能通过运行编号越权。 */
    @Transactional(readOnly = true)
    public Detail get(UUID invoiceId, UUID runId) {
        var original = owned(invoiceId); var run = requireRun(original, runId); var state = run.state();
        return new Detail(runId, run.context().input(), run.context().method(), state.status(), state.version(), run.context().createdAt(),
                state.startedAt(), state.completedAt(), state.suggestion(), state.failure(), state.review(),
                state.status() == InvoiceExtractionRun.Status.COMPLETED && matches(original, run.context().input()));
    }

    /** 历史分页在入口限额，列表只返回运行元数据。 */
    @Transactional(readOnly = true)
    public JdbcInvoiceExtractionRunRepository.Page list(UUID invoiceId, int page, int pageSize) {
        if (page < 0 || page > 1_000_000 || pageSize < 1 || pageSize > 50) throw new DomainException("INVALID_REQUEST", "Invoice extraction page is outside allowed bounds");
        var original = owned(invoiceId);
        return runs.page(original.tenantId(), invoiceId, page, pageSize);
    }

    /** 人工只确认自己的候选信息，不更新发票票面、查验结论、占用或申请。 */
    @Transactional
    public Receipt review(UUID invoiceId, UUID runId, long expectedVersion, ReviewAction action,
                          List<InvoiceExtractionSuggestion.Selection> selected, String comment) {
        var actor = actors.actor();
        eligibility.lock(actor.tenantId());
        var original = originals.lock(actor.tenantId(), invoiceId); requireOwner(original);
        requireRun(original, runId); runs.lock(actor.tenantId(), runId);
        var run = requireRun(original, runId);
        if (action == ReviewAction.CONFIRM) {
            requireInput(original, run.context().input());
            run.confirm(expectedVersion, actor.userId(), run.context().input(), selected, comment, Instant.now());
        } else if (action == ReviewAction.DISMISS) {
            if (selected != null && !selected.isEmpty()) throw new DomainException("INVALID_AGENT_REVIEW", "Dismissal cannot confirm invoice values");
            run.dismiss(expectedVersion, actor.userId(), comment, Instant.now());
        } else throw new DomainException("INVALID_AGENT_REVIEW", "Invoice extraction review action is required");
        runs.update(run, expectedVersion, null);
        return receipt(run);
    }

    /** 领取前串行化人员和原件变化；过期 RUNNING 只结算失败，不重新外发。 */
    @Transactional
    public InvoiceExtractionRun.Context claim(String tenant, UUID id, Instant observedAt) {
        var initial = runs.find(tenant, id).orElse(null);
        if (initial == null) return null;
        eligibility.lock(tenant);
        var original = originals.lock(tenant, initial.context().input().invoiceId());
        if (!runs.lock(tenant, id)) return null;
        var run = runs.find(tenant, id).orElseThrow();
        Instant now = afterLocks(observedAt);
        if (run.state().status() == InvoiceExtractionRun.Status.RUNNING) {
            if (!runs.lease(tenant, id).isAfter(now)) fail(run, InvoiceExtractionRun.Failure.EXECUTION_TIMEOUT, now);
            return null;
        }
        if (run.state().status() != InvoiceExtractionRun.Status.QUEUED) return null;
        run.start(1, now);
        // 即使后续资格拒绝，领取与失败轨迹仍在同一事务中形成，不能留下无租约的中间态。
        int modelSeconds = run.context().method() == MODEL ? Math.max(0, configuration.getTimeoutSeconds()) : 0;
        runs.update(run, 1, now.plusSeconds((long) SOURCE_SECONDS + COMPLETION_GRACE_SECONDS + modelSeconds));
        try {
            eligibility.requireActive(tenant, run.context().requestedBy());
            if (!run.context().requestedBy().equals(original.ownerId())) throw inputChanged();
            requireInput(original, run.context().input());
        } catch (DomainException unavailable) { fail(run, InvoiceExtractionRun.Failure.INPUT_UNAVAILABLE, now); return null; }
        try { requireTarget(run.context().method(), run.context().targetDigest()); }
        catch (DomainException unavailable) { fail(run, InvoiceExtractionRun.Failure.MODEL_UNAVAILABLE, now); return null; }
        return run.context();
    }

    /** 租约内一次性结算；迟到返回和并发超时不能覆盖已经保存的终态。 */
    @Transactional
    public void finish(String tenant, UUID id, InvoiceExtractionSuggestion suggestion, InvoiceExtractionRun.Failure failure, Instant observedAt) {
        if (!runs.lock(tenant, id)) return;
        var run = runs.find(tenant, id).orElseThrow();
        if (run.state().status() != InvoiceExtractionRun.Status.RUNNING) return;
        Instant now = afterLocks(observedAt);
        if (!runs.lease(tenant, id).isAfter(now)) { fail(run, InvoiceExtractionRun.Failure.EXECUTION_TIMEOUT, now); return; }
        if (failure != null) { fail(run, failure, now); return; }
        try { run.complete(2, suggestion, now); }
        catch (DomainException invalid) { fail(run, InvoiceExtractionRun.Failure.INVALID_RESULT, now); return; }
        runs.update(run, 2, null);
    }

    private InvoiceOriginal owned(UUID invoiceId) {
        var actor = actors.actor();
        var original = originals.find(actor.tenantId(), invoiceId).orElseThrow(InvoiceExtractionService::notFound);
        requireOwner(original);
        return original;
    }
    private void requireOwner(InvoiceOriginal original) {
        var actor = actors.actor();
        if (!actor.tenantId().equals(original.tenantId()) || !actor.userId().equals(original.ownerId())) throw notFound();
        eligibility.requireActive(actor.tenantId(), actor.userId());
    }
    private InvoiceExtractionRun requireRun(InvoiceOriginal original, UUID id) {
        var run = runs.find(original.tenantId(), id).orElseThrow(InvoiceExtractionService::notFound);
        if (!run.context().input().invoiceId().equals(original.invoiceId()) || !run.context().requestedBy().equals(original.ownerId())) throw notFound();
        return run;
    }
    private void requireTarget(InvoiceExtractionSuggestion.Method method, String digest) {
        if (method != MODEL) return;
        configuration.requireAvailable();
        if (!configuration.targetDigest(InvoiceExtractionRun.CONTRACT_VERSION).equals(digest)) throw new DomainException("AGENT_TARGET_CHANGED", "Refresh the invoice model destination");
    }
    private void fail(InvoiceExtractionRun run, InvoiceExtractionRun.Failure failure, Instant at) {
        long previous = run.state().version(); run.fail(previous, failure, at); runs.update(run, previous, null);
    }
    private static void requireInput(InvoiceOriginal original, InvoiceExtractionInput input) { if (!matches(original, input)) throw inputChanged(); }
    private static boolean matches(InvoiceOriginal original, InvoiceExtractionInput input) {
        return original.status() == InvoiceOriginal.Status.READY && original.invoiceId().equals(input.invoiceId())
                && original.id().equals(input.originalId()) && original.sha256().equals(input.originalDigest())
                && original.format() == input.format() && original.size() == input.originalBytes();
    }
    private static Instant afterLocks(Instant observedAt) { var current = Instant.now(); return current.isAfter(observedAt) ? current : observedAt; }
    private static DomainException inputChanged() { return new DomainException("AGENT_INPUT_CHANGED", "Invoice original or extraction method changed"); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Invoice extraction not found"); }
    private static Receipt receipt(InvoiceExtractionRun run) { return new Receipt(run.context().id(), run.state().status(), run.state().version()); }

    /**
     * 只保留服务端解析出的不可变来源与方式，不保留文件字节或本地解析结果。
     * @author owlzhangfq@gmail.com
     */
    public static final class Prepared {
        private final String tenant;
        private final String owner;
        private final InvoiceExtractionInput input;
        private final InvoiceExtractionSuggestion.Method method;
        private Prepared(String tenant, String owner, InvoiceExtractionInput input, InvoiceExtractionSuggestion.Method method) {
            this.tenant = tenant; this.owner = owner; this.input = input; this.method = method;
        }
        public InvoiceExtractionInput input() { return input; }
        public InvoiceExtractionSuggestion.Method method() { return method; }
    }
    /**
     * 安全幂等回执不复制原件内容或票面值。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID id, InvoiceExtractionRun.Status status, long version) { }
    /**
     * 本人对照原件后确认或放弃，确认不等于查验。
     * @author owlzhangfq@gmail.com
     */
    public enum ReviewAction { CONFIRM, DISMISS }
    /**
     * 本人详情分开展示来源、生成结果和人工修订。
     * @author owlzhangfq@gmail.com
     */
    public record Detail(UUID id, InvoiceExtractionInput input, InvoiceExtractionSuggestion.Method method, InvoiceExtractionRun.Status status,
                         long version, Instant createdAt, Instant startedAt, Instant completedAt, InvoiceExtractionSuggestion suggestion,
                         InvoiceExtractionRun.Failure failure, InvoiceExtractionRun.Review review, boolean canConfirm) { }
}
