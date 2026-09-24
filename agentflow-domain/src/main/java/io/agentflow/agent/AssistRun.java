package io.agentflow.agent;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Agent 摘要运行聚合，维护输入版本、执行状态与人工采纳记录，不能改变审批申请。
 * @author owlzhangfq@gmail.com
 */
public final class AssistRun {
    public static final int MAX_REVIEW_TEXT_LENGTH = AssistSuggestion.MAX_CLAIMS * (AssistSuggestion.MAX_CLAIM_LENGTH + 1);
    public static final int MAX_REVIEW_COMMENT_LENGTH = 2000;
    private static final int MAX_ACTOR_LENGTH = 128;
    private final UUID id;
    private final String tenantId;
    private final String requestedBy;
    private final Instant createdAt;
    private final AssistInput input;
    private final String promptVersion;
    private Status status = Status.QUEUED;
    private long version = 1;
    private Instant startedAt;
    private Instant completedAt;
    private AssistSuggestion suggestion;
    private Failure failure;
    private Review review;

    /** 在权限及字段策略通过后创建运行；本工厂不访问资源，也不调用任何模型。 */
    public static AssistRun queue(UUID id, String tenantId, String requestedBy, Instant createdAt,
                                  AssistInput input, String promptVersion) {
        return new AssistRun(id, tenantId, requestedBy, createdAt, input, promptVersion);
    }

    private AssistRun(UUID id, String tenantId, String requestedBy, Instant createdAt, AssistInput input, String promptVersion) {
        this.id = Objects.requireNonNull(id);
        if (StringUtils.isBlank(tenantId) || tenantId.length() > 64 || StringUtils.isBlank(promptVersion)
                || promptVersion.length() > AssistSuggestion.MAX_VERSION_LENGTH) {
            throw new DomainException("INVALID_AGENT_INPUT", "Invalid tenant or prompt identifier");
        }
        this.tenantId = tenantId;
        this.requestedBy = actor(requestedBy);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.input = Objects.requireNonNull(input);
        this.promptVersion = promptVersion;
    }

    /** 领取待执行运行；乐观版本阻止两个执行者同时推进同一快照。 */
    public void start(long expectedVersion, Instant at) {
        require(expectedVersion, Status.QUEUED);
        requireTime(at, createdAt);
        startedAt = at;
        status = Status.RUNNING;
        version++;
    }

    /** 保存有证据绑定的结果；完成只表示建议可供人工查看，不表示已采纳或批准。 */
    public void complete(long expectedVersion, AssistSuggestion result, Instant at) {
        require(expectedVersion, Status.RUNNING);
        requireTime(at, startedAt);
        Objects.requireNonNull(result);
        if (!promptVersion.equals(result.promptVersion())) {
            throw new DomainException("INVALID_AGENT_OUTPUT", "Prompt version changed during the run");
        }
        result.requireEvidenceFrom(input);
        suggestion = result;
        completedAt = at;
        status = Status.COMPLETED;
        version++;
    }

    /** 仅记录稳定错误分类，不把密钥、远端错误正文或申请内容写入失败记录。 */
    public void fail(long expectedVersion, Failure reason, Instant at) {
        require(expectedVersion, Status.RUNNING);
        requireTime(at, startedAt);
        failure = Objects.requireNonNull(reason);
        completedAt = at;
        status = Status.FAILED;
        version++;
    }

    /** 采纳绑定人工当前看到的申请版本，修订文本与模型原文分别保存。 */
    public void adopt(long expectedVersion, long currentApplicationVersion, String reviewer, String acceptedText,
                      String comment, Instant at) {
        require(expectedVersion, Status.COMPLETED);
        if (currentApplicationVersion != input.applicationVersion()) {
            throw new DomainException("AGENT_INPUT_CHANGED", "Application changed since the summary was requested");
        }
        if (StringUtils.isBlank(acceptedText) || acceptedText.length() > MAX_REVIEW_TEXT_LENGTH) {
            throw new DomainException("INVALID_AGENT_REVIEW", "Invalid accepted summary text");
        }
        review(reviewer, acceptedText, comment, at, Status.ADOPTED);
    }

    /** 拒绝建议只结束本次人工复核；即使申请已变化，仍可记录拒绝且不触碰审批状态。 */
    public void dismiss(long expectedVersion, String reviewer, String comment, Instant at) {
        require(expectedVersion, Status.COMPLETED);
        review(reviewer, null, comment, at, Status.DISMISSED);
    }

    private void review(String reviewer, String acceptedText, String comment, Instant at, Status target) {
        String user = actor(reviewer);
        requireTime(at, completedAt);
        if (comment != null && comment.length() > MAX_REVIEW_COMMENT_LENGTH) {
            throw new DomainException("INVALID_AGENT_REVIEW", "Review comment exceeds the limit");
        }
        review = new Review(user, at, acceptedText, comment);
        status = target;
        version++;
    }

    private void require(long expectedVersion, Status expectedStatus) {
        if (version != expectedVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Assist run version has changed");
        if (status != expectedStatus) throw new DomainException("AGENT_RUN_STATE_CONFLICT", "Assist run cannot accept this transition");
    }

    private static void requireTime(Instant at, Instant previous) {
        if (at == null || at.isBefore(previous)) throw new DomainException("INVALID_AGENT_TIME", "Assist event time precedes the previous event");
    }

    private static String actor(String value) {
        if (StringUtils.isBlank(value) || value.length() > MAX_ACTOR_LENGTH) {
            throw new DomainException("INVALID_AGENT_ACTOR", "Invalid assist actor");
        }
        return value;
    }

    public UUID id() { return id; }
    public String tenantId() { return tenantId; }
    public String requestedBy() { return requestedBy; }
    public Instant createdAt() { return createdAt; }
    public AssistInput input() { return input; }
    public String promptVersion() { return promptVersion; }
    public Status status() { return status; }
    public long version() { return version; }
    public Instant startedAt() { return startedAt; }
    public Instant completedAt() { return completedAt; }
    public AssistSuggestion suggestion() { return suggestion; }
    public Failure failure() { return failure; }
    public Review review() { return review; }

    /**
     * 摘要与人工复核状态，完全独立于审批申请的生命周期。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED, ADOPTED, DISMISSED }

    /**
     * 可公开的执行失败分类；实际连接与重试策略由后续适配器承担。
     * @author owlzhangfq@gmail.com
     */
    public enum Failure { MODEL_UNAVAILABLE, MODEL_TIMEOUT, INVALID_MODEL_OUTPUT, INPUT_UNAVAILABLE }

    /**
     * 人工复核记录，acceptedText 仅采纳时存在，原模型建议不被覆盖。
     * @author owlzhangfq@gmail.com
     */
    public record Review(String reviewer, Instant reviewedAt, String acceptedText, String comment) { }
}
