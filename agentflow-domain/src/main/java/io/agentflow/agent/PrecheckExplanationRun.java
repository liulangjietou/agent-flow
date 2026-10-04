package io.agentflow.agent;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 预检解释的独立执行与人工复核；聚合没有修改费用、资源或审批的行为。
 * @author owlzhangfq@gmail.com
 */
public final class PrecheckExplanationRun {
    public static final String PROMPT_VERSION = "expense-precheck-explanation-v1";
    private final Context context;
    private State state = new State(Status.QUEUED, 1, null, null, null, null, null, null);

    /** 本人身份、实际来源与模型目的地在排队时冻结。 */
    public PrecheckExplanationRun(Context context) { this.context = Objects.requireNonNull(context); }

    /** 租约随首次领取固定，进程恢复不能延长或重复执行原模型请求。 */
    public void start(long version, Instant at, Instant leaseUntil) {
        require(version, Status.QUEUED); time(at, context.createdAt());
        if (leaseUntil == null || !leaseUntil.isAfter(at)) throw new DomainException("INVALID_AGENT_TIME", "Invalid explanation lease");
        state = new State(Status.RUNNING, version + 1, at, leaseUntil, null, null, null, null);
    }

    /** 完成仅记录带来源的建议；超时结果不能成为可复核建议。 */
    public void complete(long version, PrecheckExplanationSuggestion suggestion, Instant at) {
        require(version, Status.RUNNING); time(at, state.startedAt());
        if (expired(at)) throw new DomainException("AGENT_RUN_STATE_CONFLICT", "Explanation lease expired");
        if (suggestion == null || !PROMPT_VERSION.equals(suggestion.promptVersion())) throw new DomainException("INVALID_AGENT_OUTPUT", "Explanation prompt changed");
        suggestion.requireMatches(context.input());
        state = new State(Status.COMPLETED, version + 1, state.startedAt(), state.leaseUntil(), at, suggestion, null, null);
    }

    /** 不确定执行记录为稳定失败，不自动再次发送原始内容。 */
    public void fail(long version, AssistRun.Failure failure, Instant at) {
        require(version, Status.RUNNING); time(at, state.startedAt());
        state = new State(Status.FAILED, version + 1, state.startedAt(), state.leaseUntil(), at, null, Objects.requireNonNull(failure), null);
    }

    /** 本人明确采纳所选解释，只保存核对记录，不写回财务字段。 */
    public void adopt(long version, String actor, List<String> selected, String comment, Instant at) {
        require(version, Status.COMPLETED);
        if (!context.input().currentAt(at)) throw new DomainException("AGENT_INPUT_CHANGED", "Precheck explanation expired");
        if (selected == null || selected.isEmpty() || selected.size() > PrecheckExplanationInput.MAX_ISSUES
                || selected.stream().anyMatch(Objects::isNull) || new HashSet<>(selected).size() != selected.size()
                || !context.input().issueIds().containsAll(selected)) throw invalidReview();
        review(version, actor, selected, comment, at, Status.ADOPTED);
    }

    /** 过期建议仍可放弃，原模型文本和来源保持。 */
    public void dismiss(long version, String actor, String comment, Instant at) {
        require(version, Status.COMPLETED); review(version, actor, List.of(), comment, at, Status.DISMISSED);
    }

    private void review(long version, String actor, List<String> selected, String comment, Instant at, Status status) {
        if (!context.requestedBy().equals(actor) || comment != null && comment.length() > AssistRun.MAX_REVIEW_COMMENT_LENGTH) throw invalidReview();
        time(at, state.completedAt());
        state = new State(status, version + 1, state.startedAt(), state.leaseUntil(), state.completedAt(), state.suggestion(), null,
                new Review(actor, at, List.copyOf(selected), comment));
    }

    /** 还原时重放真实迁移，持久状态不能绕过证据校验或采纳期限。 */
    public static PrecheckExplanationRun restore(Context context, State state) {
        var run = new PrecheckExplanationRun(context);
        if (state.startedAt() != null) run.start(1, state.startedAt(), state.leaseUntil());
        if (state.suggestion() != null) run.complete(2, state.suggestion(), state.completedAt());
        if (state.failure() != null) run.fail(2, state.failure(), state.completedAt());
        if (state.review() != null) {
            var review = state.review();
            if (state.status() == Status.ADOPTED) run.adopt(3, review.actor(), review.selectedIssueIds(), review.comment(), review.at());
            else run.dismiss(3, review.actor(), review.comment(), review.at());
        }
        if (!run.state.equals(state)) throw new IllegalStateException("Persisted precheck explanation is inconsistent");
        return run;
    }
    public Context context() { return context; }
    public State state() { return state; }
    public boolean active() { return state.status() == Status.QUEUED || state.status() == Status.RUNNING; }
    public boolean expired(Instant at) { return state.status() == Status.RUNNING && !state.leaseUntil().isAfter(at); }
    private void require(long version, Status status) {
        if (version != state.version()) throw new DomainException("CONCURRENCY_CONFLICT", "Explanation run version changed");
        if (state.status() != status) throw new DomainException("AGENT_RUN_STATE_CONFLICT", "Explanation transition is unavailable");
    }
    private static void time(Instant at, Instant previous) {
        if (at == null || at.isBefore(previous)) throw new DomainException("INVALID_AGENT_TIME", "Explanation time moved backwards");
    }
    private static DomainException invalidReview() { return new DomainException("INVALID_AGENT_REVIEW", "Only selected explanations can be reviewed by the original applicant"); }

    /**
     * 完整执行身份仅在本地持久化，模型只收到其中明确授权的来源。
     * @author owlzhangfq@gmail.com
     */
    public record Context(UUID id, String tenantId, String requestedBy, Instant createdAt, PrecheckExplanationInput input, String targetDigest) {
        /** 模型目标摘要包含固定提示版本，不能替换端点复用旧同意。 */
        public Context {
            if (id == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || StringUtils.isBlank(requestedBy)
                    || requestedBy.length() > 128 || createdAt == null || input == null || !input.currentAt(createdAt)
                    || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}")) throw new DomainException("INVALID_AGENT_INPUT", "Invalid explanation context");
        }
    }
    /**
     * 模型执行与人工记录分开，不表示申请已经提交或通过。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED, ADOPTED, DISMISSED }
    /**
     * 每次迁移保存完整不可变快照，终态保留原租约供审计。
     * @author owlzhangfq@gmail.com
     */
    public record State(Status status, long version, Instant startedAt, Instant leaseUntil, Instant completedAt,
                        PrecheckExplanationSuggestion suggestion, AssistRun.Failure failure, Review review) { }
    /**
     * 人工采纳不产生财务版本，也不删除未采纳的模型建议。
     * @author owlzhangfq@gmail.com
     */
    public record Review(String actor, Instant at, List<String> selectedIssueIds, String comment) { }
}
