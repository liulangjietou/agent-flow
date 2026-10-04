package io.agentflow.servicetask;

import io.agentflow.common.DomainException;

import java.time.Duration;
import java.time.Instant;

/**
 * 后台服务操作的持久状态；状态变更属于实体，网络、数据库和引擎推进由应用层编排。
 * @author owlzhangfq@gmail.com
 */
public record ServiceTaskOperation(Input input, long version, Status status, int attempts, Instant createdAt, Instant updatedAt,
                                   Instant nextAttemptAt, Instant leaseUntil, ServiceTaskObservation observation, Failure failure) {
    private static final long INITIAL_BACKOFF_SECONDS = 5;
    private static final long MAX_BACKOFF_SECONDS = 300;
    private static final Duration MAX_LEASE = Duration.ofMinutes(5);

    /** 恢复持久记录时拒绝互相矛盾的状态，未知结果不能恢复成首次发送。 */
    public ServiceTaskOperation {
        if (input == null || status == null || createdAt == null || updatedAt == null || version < 1 || attempts < 0
                || updatedAt.isBefore(createdAt) || version < (long) attempts + 1) throw invalid();
        boolean running = status == Status.EXECUTING || status == Status.QUERYING;
        boolean confirmed = status == Status.APPLIED || status == Status.REJECTED;
        boolean terminal = confirmed || status == Status.CANCELLED;
        if (running && (attempts == 0 || leaseUntil == null || !leaseUntil.isAfter(updatedAt)
                        || Duration.between(updatedAt, leaseUntil).compareTo(MAX_LEASE) > 0 || nextAttemptAt != null || observation != null || failure != null)
                || !running && leaseUntil != null
                || !running && !terminal && (nextAttemptAt == null || nextAttemptAt.isBefore(updatedAt))
                || confirmed && (attempts == 0 || nextAttemptAt != null || observation == null || failure != null || !observation.status().name().equals(status.name()))
                || status == Status.CANCELLED && (nextAttemptAt != null || failure != null
                        || attempts == 0 && observation != null || attempts > 0 && (observation == null || observation.status() != ServiceTaskObservation.Status.NOT_FOUND))
                || status == Status.UNKNOWN && (attempts == 0 || (observation == null) == (failure == null)
                        || observation != null && observation.status() != ServiceTaskObservation.Status.PENDING)
                || status == Status.QUEUED && (failure != null || attempts == 0 && (observation != null || version != 1 || !createdAt.equals(updatedAt))
                        || attempts > 0 && (observation == null || observation.status() != ServiceTaskObservation.Status.NOT_FOUND))
                || observation != null && !observation.matches(input.command(), true, updatedAt)) throw invalid();
    }

    /** 流程事务只创建待发操作；提交成功前不产生远端调用。 */
    public static ServiceTaskOperation queue(Input input, Instant now) {
        return new ServiceTaskOperation(input, 1, Status.QUEUED, 0, now, now, now, null, null, null);
    }

    /** 未知结果只领取为查询；仅首次发送或明确查无原号后才进入执行。 */
    public ServiceTaskOperation claim(Instant now, Duration lease) {
        if ((status != Status.QUEUED && status != Status.UNKNOWN) || now.isBefore(nextAttemptAt)
                || lease == null || lease.isNegative() || lease.isZero() || lease.compareTo(MAX_LEASE) > 0) throw conflict();
        return new ServiceTaskOperation(input, Math.incrementExact(version), status == Status.QUEUED ? Status.EXECUTING : Status.QUERYING,
                Math.incrementExact(attempts), createdAt, now, null, now.plus(lease), null, null);
    }

    /** 失去领取租约后即使收到成功回执也先查原号，迟到执行者不能凭旧领取推进引擎。 */
    public ServiceTaskOperation expire(Instant now) {
        if (!expired(now)) throw conflict();
        return changed(Status.UNKNOWN, now, now, null, Failure.LEASE_EXPIRED);
    }

    /** 终态不再变更；业务拒绝停留为拒绝，不自动换号重试或解释为节点成功。 */
    public ServiceTaskOperation complete(ServiceTaskObservation value, Instant now) {
        if (!running() || now.isBefore(updatedAt)) throw conflict();
        if (expired(now)) return expire(now);
        if (value == null || !value.matches(input.command(), status == Status.QUERYING, now)) return unavailable(Failure.INVALID_RESPONSE, now);
        return switch (value.status()) {
            case APPLIED -> changed(Status.APPLIED, now, null, value, null);
            case REJECTED -> changed(Status.REJECTED, now, null, value, null);
            case PENDING -> changed(Status.UNKNOWN, now, retryAt(now), value, null);
            case NOT_FOUND -> changed(Status.QUEUED, now, retryAt(now), value, null);
        };
    }

    /** 网络、认证或本地执行异常均没有“未执行”含义，只保存封闭原因并转查询恢复。 */
    public ServiceTaskOperation unavailable(Failure value, Instant now) {
        if (!running() || now.isBefore(updatedAt) || value == null || value == Failure.LEASE_EXPIRED) throw conflict();
        if (expired(now)) return expire(now);
        return changed(Status.UNKNOWN, now, retryAt(now), null, value);
    }

    /** 仅从未发送或原号查询确认不存在的命令可以取消，不能把未知副作用当成未发生。 */
    public ServiceTaskOperation cancelUnsent(Instant now) {
        if (status != Status.QUEUED || now.isBefore(updatedAt)) throw conflict();
        return changed(Status.CANCELLED, now, null, observation, null);
    }

    public boolean terminal() { return status == Status.APPLIED || status == Status.REJECTED || status == Status.CANCELLED; }
    public boolean running() { return status == Status.EXECUTING || status == Status.QUERYING; }
    public boolean expired(Instant now) { return running() && !leaseUntil.isAfter(now); }

    private ServiceTaskOperation changed(Status next, Instant now, Instant retryAt, ServiceTaskObservation value, Failure problem) {
        return new ServiceTaskOperation(input, Math.incrementExact(version), next, attempts, createdAt, now, retryAt, null, value, problem);
    }

    private Instant retryAt(Instant now) {
        return now.plusSeconds(Math.min(MAX_BACKOFF_SECONDS, INITIAL_BACKOFF_SECONDS << Math.min(attempts - 1, 6)));
    }

    /**
     * 目标摘要只由可信服务配置生成；恢复不能把旧命令交给另一个地址或适配器。
     * @author owlzhangfq@gmail.com
     */
    public record Input(ServiceTaskCommand command, String targetDigest) {
        public Input {
            if (command == null || targetDigest == null || !ServiceTaskCommand.DIGEST.matcher(targetDigest).matches()) throw invalid();
        }
    }

    /** @author owlzhangfq@gmail.com */
    public enum Status { QUEUED, EXECUTING, UNKNOWN, QUERYING, APPLIED, REJECTED, CANCELLED }

    /**
     * 有界依赖分类；不保存异常正文、密钥或服务返回的任意业务数据。
     * @author owlzhangfq@gmail.com
     */
    public enum Failure { NOT_CONFIGURED, TARGET_CHANGED, OPERATION_DISABLED, TIMEOUT, CONNECTION, AUTHENTICATION, REMOTE_FAILURE,
        INVALID_RESPONSE, RESPONSE_TOO_LARGE, LEASE_EXPIRED, INTERNAL_ERROR }

    private static DomainException invalid() { return new DomainException("INVALID_SERVICE_TASK_OPERATION", "Service task operation state or observation is inconsistent"); }
    private static DomainException conflict() { return new DomainException("SERVICE_TASK_OPERATION_CONFLICT", "Service task operation is no longer executable with this claim"); }
}
