package io.agentflow.agent;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

/**
 * 独立保存票据抽取与本人确认，确认动作只产生候选信息，不更改发票聚合。
 * @author owlzhangfq@gmail.com
 */
public final class InvoiceExtractionRun {
    public static final String PROMPT_VERSION = "invoice-extraction-v1";
    private final Context context;
    private State state = new State(Status.QUEUED, 1, null, null, null, null, null);

    /** 运行身份、本人授权、完整原件和模型目的地在创建时固定。 */
    public InvoiceExtractionRun(Context context) { this.context = Objects.requireNonNull(context); }

    /** 单次开始；持久租约由运行仓储约束，不允许终态重新发送。 */
    public void start(long expectedVersion, Instant at) {
        require(expectedVersion, Status.QUEUED); time(at, context.createdAt());
        state = new State(Status.RUNNING, expectedVersion + 1, at, null, null, null, null);
    }

    /** 只接受本次来源和提示版本的完整建议，原件或财务查验不在此写入。 */
    public void complete(long expectedVersion, InvoiceExtractionSuggestion suggestion, Instant at) {
        require(expectedVersion, Status.RUNNING); time(at, state.startedAt());
        if (suggestion == null || !PROMPT_VERSION.equals(suggestion.promptVersion())) {
            throw new DomainException("INVALID_AGENT_OUTPUT", "Invoice extraction prompt changed");
        }
        suggestion.requireMatches(context.input());
        state = new State(Status.COMPLETED, expectedVersion + 1, state.startedAt(), at, suggestion, null, null);
    }

    /** 保存稳定失败分类；超时和晚到结果不能触发自动重发。 */
    public void fail(long expectedVersion, AssistRun.Failure failure, Instant at) {
        require(expectedVersion, Status.RUNNING); time(at, state.startedAt());
        state = new State(Status.FAILED, expectedVersion + 1, state.startedAt(), at, null, Objects.requireNonNull(failure), null);
    }

    /** 本人按原件逐字段确认，可修改选中值，模型原值和未选字段仍保留。 */
    public void confirm(long expectedVersion, String actor, InvoiceExtractionInput currentInput,
                        List<InvoiceExtractionSuggestion.Selection> selected, String comment, Instant at) {
        require(expectedVersion, Status.COMPLETED);
        if (!context.input().equals(currentInput)) throw new DomainException("AGENT_INPUT_CHANGED", "Invoice original changed since extraction");
        if (CollectionUtils.isEmpty(selected) || selected.size() > InvoiceExtractionSuggestion.MAX_PROPOSALS
                || selected.stream().anyMatch(Objects::isNull)) throw invalidReview();
        var available = new HashSet<>(state.suggestion().proposals().stream().map(InvoiceExtractionSuggestion.Proposal::field).toList());
        for (var value : selected) if (!available.remove(value.field())) throw invalidReview();
        review(expectedVersion, actor, List.copyOf(selected), comment, at, Status.CONFIRMED);
    }

    /** 本人可明确放弃过期或不合用建议，不需要重新发送原件。 */
    public void dismiss(long expectedVersion, String actor, String comment, Instant at) {
        require(expectedVersion, Status.COMPLETED);
        review(expectedVersion, actor, null, comment, at, Status.DISMISSED);
    }

    private void review(long version, String actor, List<InvoiceExtractionSuggestion.Selection> selected, String comment, Instant at, Status status) {
        if (!context.requestedBy().equals(actor) || comment != null && comment.length() > AssistRun.MAX_REVIEW_COMMENT_LENGTH) throw invalidReview();
        time(at, state.completedAt());
        state = new State(status, version + 1, state.startedAt(), state.completedAt(), state.suggestion(), null, new Review(actor, at, selected, comment));
    }

    /** 按合法动作重建持久状态，拒绝缺失阶段或被篡改的确认记录。 */
    public static InvoiceExtractionRun restore(Context context, State state) {
        var run = new InvoiceExtractionRun(context);
        if (state.startedAt() != null) run.start(run.state.version(), state.startedAt());
        if (state.suggestion() != null) run.complete(run.state.version(), state.suggestion(), state.completedAt());
        if (state.failure() != null) run.fail(run.state.version(), state.failure(), state.completedAt());
        if (state.review() != null) {
            var review = state.review();
            if (review.selected() == null) run.dismiss(run.state.version(), review.actor(), review.comment(), review.at());
            else run.confirm(run.state.version(), review.actor(), context.input(), review.selected(), review.comment(), review.at());
        }
        if (!run.state.equals(state)) throw new IllegalStateException("Persisted invoice extraction run is inconsistent");
        return run;
    }

    private void require(long version, Status status) {
        if (state.version() != version) throw new DomainException("CONCURRENCY_CONFLICT", "Invoice extraction version changed");
        if (state.status() != status) throw new DomainException("AGENT_RUN_STATE_CONFLICT", "Invoice extraction transition is unavailable");
    }
    private static void time(Instant at, Instant previous) {
        if (at == null || at.isBefore(previous)) throw new DomainException("INVALID_AGENT_TIME", "Invoice extraction time moved backwards");
    }
    private static DomainException invalidReview() {
        return new DomainException("INVALID_AGENT_REVIEW", "Only the original owner may confirm selected invoice proposals");
    }
    public Context context() { return context; }
    public State state() { return state; }

    /**
     * 模型目的地只记录指纹，凭据和本地文件路径不得进入业务历史。
     * @author owlzhangfq@gmail.com
     */
    public record Context(UUID id, String tenantId, String requestedBy, Instant createdAt,
                          InvoiceExtractionInput input, String targetDigest) {
        public Context {
            if (id == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64
                    || StringUtils.isBlank(requestedBy) || requestedBy.length() > 128 || createdAt == null || input == null
                    || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}")) {
                throw new DomainException("INVALID_AGENT_INPUT", "Invoice extraction context is invalid");
            }
        }
    }
    /**
     * 人工确认与真实性查验分别建模，CONFIRMED 只属于本次建议。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED, CONFIRMED, DISMISSED }
    /**
     * 原始建议、失败及人工动作分开保存。
     * @author owlzhangfq@gmail.com
     */
    public record State(Status status, long version, Instant startedAt, Instant completedAt,
                        InvoiceExtractionSuggestion suggestion, AssistRun.Failure failure, Review review) { }
    /**
     * selected 为空表示明确放弃；确认列表为不可变副本。
     * @author owlzhangfq@gmail.com
     */
    public record Review(String actor, Instant at, List<InvoiceExtractionSuggestion.Selection> selected, String comment) {
        public Review { if (selected != null) selected = List.copyOf(selected); }
    }
}
