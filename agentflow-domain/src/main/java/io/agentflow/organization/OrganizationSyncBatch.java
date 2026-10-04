package io.agentflow.organization;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import static io.agentflow.organization.OrganizationSyncKey.*;

/**
 * 来源读取、待人工核对和已应用分别记录；收到来源数据本身不改变组织游标或成员。
 * @author owlzhangfq@gmail.com
 */
public final class OrganizationSyncBatch {
    public static final int MAX_COMMENT_LENGTH = 2000;
    private final Context context;
    private State state = new State(Status.QUEUED, 1, null, null, null, null, null, null, null);

    /** 排队只固定可信来源、原游标和发起管理员。 */
    public OrganizationSyncBatch(Context context) { this.context = Objects.requireNonNull(context); }

    /** 领取一次并固定原租约，进程恢复不延长读取时间。 */
    public void start(long expectedVersion, Instant at, Instant leaseUntil) {
        require(expectedVersion, Status.QUEUED); time(at, context.createdAt());
        if (leaseUntil == null || !leaseUntil.isAfter(at)) throw invalid();
        state = new State(Status.FETCHING, 2, at, leaseUntil, null, null, null, null, null);
    }

    /** 只接收原来源及连续游标的数据；人工预检和应用另行进行。 */
    public void receive(long expectedVersion, OrganizationSyncDelta delta, Instant at) {
        require(expectedVersion, Status.FETCHING); time(at, state.startedAt());
        if (!state.leaseUntil().isAfter(at)) throw new DomainException("ORGANIZATION_SYNC_LEASE_EXPIRED", "Organization source read lease expired");
        if (delta == null || !context.sourceKey().equals(delta.sourceKey()) || context.afterRevision() != delta.afterRevision()) {
            throw new DomainException("ORGANIZATION_SYNC_SOURCE_CHANGED", "Organization source or revision does not match the requested batch");
        }
        state = new State(Status.RECEIVED, 3, state.startedAt(), state.leaseUntil(), at, delta, null, null, null);
    }

    /** 读取失败成为稳定事实，重试必须创建有原批次引用的新请求。 */
    public void fail(long expectedVersion, Failure failure, Instant at) {
        require(expectedVersion, Status.FETCHING); time(at, state.startedAt());
        if (failure == null) throw invalid();
        state = new State(Status.FAILED, 3, state.startedAt(), state.leaseUntil(), null, null, failure, at, null);
    }

    /** 只有已收到的数据能够应用，原来源游标必须仍然一致；持久化用例原子提交组织及游标。 */
    public void apply(long expectedVersion, long currentSourceRevision, Applied applied, String actor, String comment, Instant at) {
        require(expectedVersion, Status.RECEIVED); time(at, state.receivedAt());
        if (currentSourceRevision != context.afterRevision()) throw new DomainException("ORGANIZATION_SYNC_SOURCE_CHANGED", "Organization source cursor changed before application");
        if (applied == null) throw invalid();
        state = new State(Status.APPLIED, 4, state.startedAt(), state.leaseUntil(), state.receivedAt(), state.delta(), null, at,
                new Decision(actor, comment, at, applied));
    }

    /** 管理员可取消未应用批次；迟到来源响应不能覆盖取消决定。 */
    public void cancel(long expectedVersion, String actor, String comment, Instant at) {
        if (expectedVersion != state.version()) throw conflict();
        if (!pending()) throw stateConflict();
        time(at, state.receivedAt() != null ? state.receivedAt() : state.startedAt() != null ? state.startedAt() : context.createdAt());
        state = new State(Status.CANCELLED, state.version() + 1, state.startedAt(), state.leaseUntil(), state.receivedAt(), state.delta(), null, at,
                new Decision(actor, comment, at, null));
    }

    /** 恢复时重放合法状态转换并精确比较，拒绝拼接出不存在的终态。 */
    public static OrganizationSyncBatch restore(Context context, State state) {
        if (state == null) throw invalid();
        var batch = new OrganizationSyncBatch(context);
        if (state.startedAt() != null) batch.start(1, state.startedAt(), state.leaseUntil());
        if (state.delta() != null) batch.receive(2, state.delta(), state.receivedAt());
        if (state.status() == Status.FAILED) batch.fail(2, state.failure(), state.finishedAt());
        if (state.status() == Status.APPLIED) {
            if (state.decision() == null) throw invalid();
            batch.apply(3, context.afterRevision(), state.decision().applied(), state.decision().actor(), state.decision().comment(), state.finishedAt());
        }
        if (state.status() == Status.CANCELLED) {
            if (state.decision() == null) throw invalid();
            batch.cancel(batch.state.version(), state.decision().actor(), state.decision().comment(), state.finishedAt());
        }
        if (!batch.state.equals(state)) throw new IllegalStateException("Persisted organization synchronization state is inconsistent");
        return batch;
    }

    /** 待人工处理的已接收批次也占用活动位置，不能用第二个来源覆盖它。 */
    public boolean pending() { return state.status() == Status.QUEUED || state.status() == Status.FETCHING || state.status() == Status.RECEIVED; }
    /** 只有原租约到期的读取才进入失败恢复候选。 */
    public boolean expired(Instant at) { return state.status() == Status.FETCHING && !state.leaseUntil().isAfter(at); }
    public Context context() { return context; }
    public State state() { return state; }

    private void require(long version, Status status) {
        if (version != state.version()) throw conflict();
        if (state.status() != status) throw stateConflict();
    }
    private static void time(Instant at, Instant previous) { if (at == null || at.isBefore(previous)) throw invalid(); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Organization synchronization batch version changed"); }
    private static DomainException stateConflict() { return new DomainException("ORGANIZATION_SYNC_STATE_CONFLICT", "Organization synchronization batch is not in the required state"); }

    /**
     * 原请求身份和部署目标摘要不可被客户端结果或重试覆盖。
     * @author owlzhangfq@gmail.com
     */
    public record Context(UUID id, String tenantId, String sourceKey, long afterRevision, String targetDigest,
                          String requestedBy, Instant createdAt, UUID retryOf) {
        /** 重试链保留原批次标识，新请求读取当前已应用游标。 */
        public Context {
            if (id == null || afterRevision < 0 || createdAt == null || id.equals(retryOf)) throw invalid();
            text(tenantId, 64); source(sourceKey); digest(targetDigest); text(requestedBy, 128);
        }
    }
    /**
     * 人工核对的计划摘要及组织修订区间，实际变更细节保存在原组织审计和映射轨迹。
     * @author owlzhangfq@gmail.com
     */
    public record Applied(String planDigest, long directoryRevisionBefore, long directoryRevisionAfter) {
        /** 没有实质组织变更的同步允许保留目录修订，来源游标仍可前进。 */
        public Applied {
            digest(planDigest);
            if (directoryRevisionBefore < 1 || directoryRevisionAfter < directoryRevisionBefore) throw invalid();
        }
    }
    /**
     * 具名管理员的应用或取消决定；权限由可信应用入口校验。
     * @author owlzhangfq@gmail.com
     */
    public record Decision(String actor, String comment, Instant at, Applied applied) {
        /** 意见只记录人工说明，不能携带新的组织或身份事实。 */
        public Decision {
            text(actor, 128);
            if (at == null || comment != null && (comment.length() > MAX_COMMENT_LENGTH || comment.chars().anyMatch(value -> Character.isISOControl(value) && value != '\n' && value != '\t'))) throw invalid();
        }
    }
    /**
     * 状态原文只由聚合转换创建，恢复必须通过完整重放校验。
     * @author owlzhangfq@gmail.com
     */
    public record State(Status status, long version, Instant startedAt, Instant leaseUntil, Instant receivedAt,
                        OrganizationSyncDelta delta, Failure failure, Instant finishedAt, Decision decision) { }
    /**
     * 已接收并不表示组织已经同步，只有显式应用成功才进入 APPLIED。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, FETCHING, RECEIVED, FAILED, APPLIED, CANCELLED }
    /**
     * 持久化失败只保存受控原因，不保存上游错误正文或凭据。
     * @author owlzhangfq@gmail.com
     */
    public enum Failure { SOURCE_UNAVAILABLE, SOURCE_TIMEOUT, INVALID_SOURCE_DATA, SOURCE_CHANGED }
}
