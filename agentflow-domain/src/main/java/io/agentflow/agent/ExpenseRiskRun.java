package io.agentflow.agent;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 费用风险提示执行与人工复核聚合；拥有自己的状态，不调用审批或财务变更。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseRiskRun {
    public static final String PROMPT_VERSION = "expense-risk-explanation-v1";
    private final Context context;
    private State state = new State(Status.QUEUED, 1, null, null, null, null, null, null);

    /** 应用服务在构造之前核对当前决策任务及每份来源的完整明细读取权限。 */
    public ExpenseRiskRun(Context context) { this.context = Objects.requireNonNull(context); }

    /** 首次领取固定租约；不提供延长或重新发送同一运行的状态迁移。 */
    public void start(long version, Instant at, Instant leaseUntil) {
        require(version, Status.QUEUED); time(at, context.createdAt());
        if (leaseUntil == null || !leaseUntil.isAfter(at)) throw new DomainException("INVALID_AGENT_TIME", "Invalid risk explanation lease");
        state = new State(Status.RUNNING, version + 1, at, leaseUntil, null, null, null, null);
    }

    /** 只接受租约内且逐项绑定原证据的解释，晚到输出不能成为可复核结果。 */
    public void complete(long version, ExpenseRiskSuggestion suggestion, Instant at) {
        require(version, Status.RUNNING); time(at, state.startedAt());
        if (expired(at)) throw new DomainException("AGENT_RUN_STATE_CONFLICT", "Risk explanation lease expired");
        if (suggestion == null || !PROMPT_VERSION.equals(suggestion.promptVersion())) throw new DomainException("INVALID_AGENT_OUTPUT", "Risk explanation prompt changed");
        suggestion.requireMatches(context.input());
        state = new State(Status.COMPLETED, version + 1, state.startedAt(), state.leaseUntil(), at, suggestion, null, null);
    }

    /** 不确定发送只记录稳定失败；重试必须另建经当前操作者重新确认的运行。 */
    public void fail(long version, AssistRun.Failure failure, Instant at) {
        require(version, Status.RUNNING); time(at, state.startedAt());
        state = new State(Status.FAILED, version + 1, state.startedAt(), state.leaseUntil(), at, null, Objects.requireNonNull(failure), null);
    }

    /** 当前有效审批人可采纳所选解释，所有来源版本和当前选择必须与生成时完全一致。 */
    public void adopt(long version, ExpenseRiskInput currentInput, String actor, List<String> selected, String comment, Instant at) {
        require(version, Status.COMPLETED);
        if (!context.input().equals(currentInput)) throw new DomainException("AGENT_INPUT_CHANGED", "Refresh changed risk evidence before review");
        if (selected == null || selected.isEmpty() || selected.size() > ExpenseRiskInput.MAX_CONCERNS
                || selected.stream().anyMatch(Objects::isNull) || new HashSet<>(selected).size() != selected.size()
                || !context.input().concernIds().containsAll(selected)) throw invalidReview();
        review(version, actor, selected, comment, at, Status.ADOPTED);
    }

    /** 业务变化后仍可记录放弃，但应用服务依然必须核验当前决策资格和全部原文读取权限。 */
    public void dismiss(long version, String actor, String comment, Instant at) {
        require(version, Status.COMPLETED); review(version, actor, List.of(), comment, at, Status.DISMISSED);
    }

    private void review(long version, String actor, List<String> selected, String comment, Instant at, Status target) {
        if (StringUtils.isBlank(actor) || actor.length() > 128 || comment != null && comment.length() > AssistRun.MAX_REVIEW_COMMENT_LENGTH) throw invalidReview();
        time(at, state.completedAt());
        state = new State(target, version + 1, state.startedAt(), state.leaseUntil(), state.completedAt(), state.suggestion(), null,
                new Review(actor, at, selected, comment));
    }

    /** 重放已发生的迁移校验持久状态，不用当前业务版本改写历史采纳。 */
    public static ExpenseRiskRun restore(Context context, State state) {
        var run = new ExpenseRiskRun(context);
        if (state.startedAt() != null) run.start(1, state.startedAt(), state.leaseUntil());
        if (state.suggestion() != null) run.complete(2, state.suggestion(), state.completedAt());
        if (state.failure() != null) run.fail(2, state.failure(), state.completedAt());
        if (state.review() != null) {
            var review = state.review();
            if (state.status() == Status.ADOPTED) run.adopt(3, context.input(), review.actor(), review.selectedConcernIds(), review.comment(), review.at());
            else run.dismiss(3, review.actor(), review.comment(), review.at());
        }
        if (!run.state.equals(state)) throw new IllegalStateException("Persisted expense risk explanation is inconsistent");
        return run;
    }

    public Context context() { return context; }
    public State state() { return state; }
    public boolean active() { return state.status() == Status.QUEUED || state.status() == Status.RUNNING; }
    public boolean expired(Instant at) { return state.status() == Status.RUNNING && !state.leaseUntil().isAfter(at); }

    private void require(long version, Status status) {
        if (version != state.version()) throw new DomainException("CONCURRENCY_CONFLICT", "Risk explanation version changed");
        if (state.status() != status) throw new DomainException("AGENT_RUN_STATE_CONFLICT", "Risk explanation transition is unavailable");
    }
    private static void time(Instant at, Instant previous) {
        if (at == null || at.isBefore(previous)) throw new DomainException("INVALID_AGENT_TIME", "Risk explanation time moved backwards");
    }
    private static DomainException invalidReview() { return new DomainException("INVALID_AGENT_REVIEW", "Select existing risk explanations for an authorized human review"); }

    /**
     * 本地排队身份及固定模型目的地；整个上下文禁止直接序列化给模型。
     * @author owlzhangfq@gmail.com
     */
    public record Context(UUID id, String tenantId, String requestedBy, String taskId, Instant createdAt,
                          ExpenseRiskInput input, String targetDigest) {
        /** 任务决定权由应用服务实时检查，这里的标识仅用于绑定原意向。 */
        public Context {
            if (id == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || StringUtils.isBlank(requestedBy)
                    || requestedBy.length() > 128 || StringUtils.isBlank(taskId) || taskId.length() > 64 || createdAt == null || input == null
                    || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}")) throw new DomainException("INVALID_AGENT_INPUT", "Invalid risk explanation context");
        }
    }

    /**
     * 完成表示模型解释已保存，采纳也不会使任何申请自动批准。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED, ADOPTED, DISMISSED }

    /**
     * 运行及复核保存完整不可变状态，历史租约和原模型文本继续保留。
     * @author owlzhangfq@gmail.com
     */
    public record State(Status status, long version, Instant startedAt, Instant leaseUntil, Instant completedAt,
                        ExpenseRiskSuggestion suggestion, AssistRun.Failure failure, Review review) { }

    /**
     * 人工只采纳选定的解释，未选项与原模型输出都不删除。
     * @author owlzhangfq@gmail.com
     */
    public record Review(String actor, Instant at, List<String> selectedConcernIds, String comment) {
        /** 不允许外部集合在保存后改变历史复核选择。 */
        public Review { selectedConcernIds = List.copyOf(selectedConcernIds); }
    }
}
