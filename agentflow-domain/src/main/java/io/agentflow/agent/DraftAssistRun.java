package io.agentflow.agent;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 草稿建议的执行和人工确认聚合；申请正文由草稿应用服务在同一事务中修改。
 * @author owlzhangfq@gmail.com
 */
public final class DraftAssistRun {
    public static final String PROMPT_VERSION = "application-draft-v1";
    private final Context context;
    private State state = new State(Status.QUEUED, 1, null, null, null, null, null);

    /** 原始输入、目的地指纹和请求身份一经排队不可变。 */
    public DraftAssistRun(Context context) { this.context = java.util.Objects.requireNonNull(context); }

    /** 单次领取，数据库租约限制进程间执行。 */
    public void start(long version, Instant at) {
        require(version, Status.QUEUED); time(at, context.createdAt());
        state = new State(Status.RUNNING, version + 1, at, null, null, null, null);
    }

    /** 模型完成只产生待确认建议，不修改申请版本。 */
    public void complete(long version, DraftSuggestion suggestion, Instant at) {
        require(version, Status.RUNNING); time(at, state.startedAt());
        if (suggestion == null || !PROMPT_VERSION.equals(suggestion.promptVersion())) throw new DomainException("INVALID_AGENT_OUTPUT", "Draft prompt changed");
        suggestion.requireMatches(context.input());
        state = new State(Status.COMPLETED, version + 1, state.startedAt(), at, suggestion, null, null);
    }

    /** 只记录稳定失败分类，超时不自动重发已发出的内容。 */
    public void fail(long version, AssistRun.Failure failure, Instant at) {
        require(version, Status.RUNNING); time(at, state.startedAt());
        state = new State(Status.FAILED, version + 1, state.startedAt(), at, null, java.util.Objects.requireNonNull(failure), null);
    }

    /** 只采纳原申请版本的明确勾选值；未选字段和模型原值均保留。 */
    public void adopt(long version, long applicationVersion, String actor, List<DraftSuggestion.Selection> selected, String comment, Instant at) {
        require(version, Status.COMPLETED);
        if (applicationVersion != context.input().applicationVersion()) throw new DomainException("AGENT_INPUT_CHANGED", "Draft changed since generation");
        if (selected == null || selected.isEmpty() || selected.size() > DraftSuggestion.MAX_PROPOSALS
                || selected.stream().anyMatch(java.util.Objects::isNull)) throw invalidReview();
        var proposed = new HashSet<>(state.suggestion().proposals().stream().map(DraftSuggestion.Proposal::targetId).toList());
        for (var value : selected) {
            if (!proposed.remove(value.targetId())) throw invalidReview();
            context.input().requireValue(value.targetId(), value.value());
        }
        review(version, actor, List.copyOf(selected), applicationVersion + 1, comment, at, Status.ADOPTED);
    }

    /** 过期建议仍可明确放弃；不触碰申请内容或轮次。 */
    public void dismiss(long version, String actor, String comment, Instant at) {
        require(version, Status.COMPLETED);
        review(version, actor, null, null, comment, at, Status.DISMISSED);
    }

    private void review(long version, String actor, List<DraftSuggestion.Selection> selected, Long appliedVersion, String comment, Instant at, Status status) {
        if (!context.requestedBy().equals(actor) || comment != null && comment.length() > AssistRun.MAX_REVIEW_COMMENT_LENGTH) throw invalidReview();
        time(at, state.completedAt());
        state = new State(status, version + 1, state.startedAt(), state.completedAt(), state.suggestion(), null,
                new Review(actor, at, selected, appliedVersion, comment));
    }

    /** 持久层重放真实动作，损坏或跳步状态不能直接注入聚合。 */
    public static DraftAssistRun restore(Context context, State state) {
        var run = new DraftAssistRun(context);
        if (state.startedAt() != null) run.start(run.state.version(), state.startedAt());
        if (state.suggestion() != null) run.complete(run.state.version(), state.suggestion(), state.completedAt());
        if (state.failure() != null) run.fail(run.state.version(), state.failure(), state.completedAt());
        if (state.review() != null) {
            var review = state.review();
            if (review.selected() == null) run.dismiss(run.state.version(), review.actor(), review.comment(), review.at());
            else run.adopt(run.state.version(), context.input().applicationVersion(), review.actor(), review.selected(), review.comment(), review.at());
        }
        if (!run.state.equals(state)) throw new IllegalStateException("Persisted draft assist run is inconsistent");
        return run;
    }
    private void require(long version, Status status) {
        if (state.version() != version) throw new DomainException("CONCURRENCY_CONFLICT", "Draft assist version changed");
        if (state.status() != status) throw new DomainException("AGENT_RUN_STATE_CONFLICT", "Draft assist transition is unavailable");
    }
    private static void time(Instant at, Instant previous) {
        if (at == null || at.isBefore(previous)) throw new DomainException("INVALID_AGENT_TIME", "Draft assist event time moved backwards");
    }
    private static DomainException invalidReview() { return new DomainException("INVALID_AGENT_REVIEW", "Review only selected proposals as the original applicant"); }
    public Context context() { return context; }
    public State state() { return state; }

    /**
     * 原申请版本和发送授权由服务端产生，不允许客户端传入身份或模型地址。
     * @author owlzhangfq@gmail.com
     */
    public record Context(UUID id, String tenantId, String requestedBy, Instant createdAt, DraftAssistInput input, String targetDigest) {
        public Context {
            if (id == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || StringUtils.isBlank(requestedBy)
                    || requestedBy.length() > 128 || createdAt == null || input == null || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}")) {
                throw new DomainException("INVALID_AGENT_INPUT", "Invalid draft assist context");
            }
        }
    }
    /**
     * 运行状态与人工保存状态分离，终态不会重新调用模型。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED, ADOPTED, DISMISSED }
    /**
     * 不可变阶段快照，持久层追加完整轨迹。
     * @author owlzhangfq@gmail.com
     */
    public record State(Status status, long version, Instant startedAt, Instant completedAt, DraftSuggestion suggestion,
                        AssistRun.Failure failure, Review review) { }
    /**
     * 记录人工最终选择和实际写入的申请版本，模型建议保持不变。
     * @author owlzhangfq@gmail.com
     */
    public record Review(String actor, Instant at, List<DraftSuggestion.Selection> selected, Long appliedApplicationVersion, String comment) { }
}
