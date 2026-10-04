package io.agentflow.agent;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

/**
 * 报销建议的执行与逐项确认状态；确认仅记录人工选择，不修改费用、补贴或审批聚合。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseDraftAssistRun {
    public static final String PROMPT_VERSION = "expense-draft-assist-v1";
    private final Context context;
    private State state = new State(Status.QUEUED, 1, null, null, null, null, null, null);

    /** 排队只保存来源和目的地，不生成或修改费用草稿。 */
    public ExpenseDraftAssistRun(Context context) { this.context = Objects.requireNonNull(context); }

    /** 租约固定到首次领取，进程崩溃后不自动重发原模型输入。 */
    public void start(long version, Instant at, Instant leaseUntil) {
        require(version, Status.QUEUED); time(at, context.createdAt());
        if (leaseUntil == null || !leaseUntil.isAfter(at)) throw new DomainException("INVALID_AGENT_TIME", "Invalid expense draft lease");
        state = new State(Status.RUNNING, version + 1, at, leaseUntil, null, null, null, null);
    }

    /** 完成时检查提示版本和冻结来源，尚不表示申请人接受了任何建议。 */
    public void complete(long version, ExpenseDraftSuggestion suggestion, Instant at) {
        require(version, Status.RUNNING); time(at, state.startedAt());
        if (expired(at)) throw new DomainException("AGENT_RUN_STATE_CONFLICT", "Expense draft lease expired");
        if (suggestion == null || !PROMPT_VERSION.equals(suggestion.promptVersion())) throw invalidOutput();
        suggestion.requireMatches(context.input());
        state = new State(Status.COMPLETED, version + 1, state.startedAt(), state.leaseUntil(), at, suggestion, null, null);
    }

    /** 失败和超时是稳定终态，晚到模型结果不能替换。 */
    public void fail(long version, AssistRun.Failure failure, Instant at) {
        require(version, Status.RUNNING); time(at, state.startedAt());
        state = new State(Status.FAILED, version + 1, state.startedAt(), state.leaseUntil(), at, null, Objects.requireNonNull(failure), null);
    }

    /** 本人逐项选择原建议；原双版本变化或目录过期时不得带入当前草稿。 */
    public void confirm(long version, long applicationVersion, long financialVersion, String actor,
                        List<Selection> selected, String comment, Instant at) {
        require(version, Status.COMPLETED);
        if (applicationVersion != context.input().applicationVersion() || financialVersion != context.input().financialVersion()
                || !context.input().currentAt(at)) throw new DomainException("AGENT_INPUT_CHANGED", "Expense draft or catalog changed before confirmation");
        if (CollectionUtils.isEmpty(selected) || selected.size() > ExpenseDraftSuggestion.MAX_LINES
                || selected.stream().anyMatch(Objects::isNull)
                || selected.stream().map(Selection::proposalId).distinct().count() != selected.size()) throw invalidReview();
        var allowed = state.suggestion().lines().stream().map(ExpenseDraftSuggestion.Line::id).toList();
        if (selected.stream().anyMatch(value -> !allowed.contains(value.proposalId()))) throw invalidReview();
        review(version, actor, selected, comment, at, Status.CONFIRMED);
    }

    /** 过期或不再适用的建议仍可明确放弃，保留全部原始来源与模型输出。 */
    public void dismiss(long version, String actor, String comment, Instant at) {
        require(version, Status.COMPLETED); review(version, actor, List.of(), comment, at, Status.DISMISSED);
    }

    private void review(long version, String actor, List<Selection> selected, String comment, Instant at, Status status) {
        if (!context.requestedBy().equals(actor) || comment != null && comment.length() > AssistRun.MAX_REVIEW_COMMENT_LENGTH) throw invalidReview();
        time(at, state.completedAt());
        state = new State(status, version + 1, state.startedAt(), state.leaseUntil(), state.completedAt(), state.suggestion(), null,
                new Review(actor, at, List.copyOf(selected), comment));
    }

    /** 重放原迁移还原持久状态，原来源和人工选择不能在恢复时被静默替换。 */
    public static ExpenseDraftAssistRun restore(Context context, State state) {
        var run = new ExpenseDraftAssistRun(context);
        if (state.startedAt() != null) run.start(1, state.startedAt(), state.leaseUntil());
        if (state.suggestion() != null) run.complete(2, state.suggestion(), state.completedAt());
        if (state.failure() != null) run.fail(2, state.failure(), state.completedAt());
        if (state.review() != null) {
            var review = state.review();
            if (state.status() == Status.CONFIRMED) run.confirm(3, context.input().applicationVersion(), context.input().financialVersion(),
                    review.actor(), review.selected(), review.comment(), review.at());
            else run.dismiss(3, review.actor(), review.comment(), review.at());
        }
        if (!run.state.equals(state)) throw new IllegalStateException("Persisted expense draft assist is inconsistent");
        return run;
    }
    public Context context() { return context; }
    public State state() { return state; }
    public boolean active() { return state.status() == Status.QUEUED || state.status() == Status.RUNNING; }
    public boolean expired(Instant at) { return state.status() == Status.RUNNING && !state.leaseUntil().isAfter(at); }
    private void require(long version, Status status) {
        if (version != state.version()) throw new DomainException("CONCURRENCY_CONFLICT", "Expense draft run version changed");
        if (state.status() != status) throw new DomainException("AGENT_RUN_STATE_CONFLICT", "Expense draft transition is unavailable");
    }
    private static void time(Instant at, Instant previous) {
        if (at == null || at.isBefore(previous)) throw new DomainException("INVALID_AGENT_TIME", "Expense draft time moved backwards");
    }
    private static DomainException invalidReview() { return new DomainException("INVALID_AGENT_REVIEW", "Only the original applicant may confirm selected expense draft parts"); }
    private static DomainException invalidOutput() { return new DomainException("INVALID_AGENT_OUTPUT", "Expense draft prompt changed"); }

    /**
     * 不可变运行身份和模型目标仅在本地使用，模型只收到已确认发送的来源。
     * @author owlzhangfq@gmail.com
     */
    public record Context(UUID id, String tenantId, String requestedBy, Instant createdAt, ExpenseDraftAssistInput input, String targetDigest) {
        public Context {
            if (id == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || StringUtils.isBlank(requestedBy)
                    || requestedBy.length() > 128 || createdAt == null || input == null || !input.currentAt(createdAt)
                    || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}")) throw new DomainException("INVALID_AGENT_INPUT", "Invalid expense draft context");
        }
    }
    /**
     * 确认与费用保存分开，任何状态都不表示已经提交或批准报销。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED, CONFIRMED, DISMISSED }
    /**
     * 行程建行、类别单位和分摊建议分别取得人工确认。
     * @author owlzhangfq@gmail.com
     */
    public enum Part { ITINERARY, CATEGORY, ALLOCATION }
    /**
     * 每个新行必须明确确认行程，其他部分不默认勾选或随之采纳。
     * @author owlzhangfq@gmail.com
     */
    public record Selection(String proposalId, Set<Part> parts) {
        public Selection {
            if (StringUtils.isBlank(proposalId) || proposalId.length() > 64 || CollectionUtils.isEmpty(parts)
                    || parts.stream().anyMatch(Objects::isNull) || !parts.contains(Part.ITINERARY)) throw invalidReview();
            parts = Set.copyOf(parts);
        }
    }
    /**
     * 完整不可变状态保留执行租约及原建议，不因人工只选部分字段而裁掉证据。
     * @author owlzhangfq@gmail.com
     */
    public record State(Status status, long version, Instant startedAt, Instant leaseUntil, Instant completedAt,
                        ExpenseDraftSuggestion suggestion, AssistRun.Failure failure, Review review) { }
    /**
     * 人工确认记录不携带已保存版本，实际费用保存沿用双版本草稿入口。
     * @author owlzhangfq@gmail.com
     */
    public record Review(String actor, Instant at, List<Selection> selected, String comment) { }
}
