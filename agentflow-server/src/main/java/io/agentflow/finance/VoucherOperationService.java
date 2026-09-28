package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 凭证执行的短事务编排，按申请和财务聚合锁串行化本轮副作用。
 * @author owlzhangfq@gmail.com
 */
@Service
public class VoucherOperationService {
    private final ApprovedVoucherSources sources;
    private final JdbcVoucherOperationRepository operations;
    private final ApplicationEventPublisher events;
    private final Duration lease;

    /** 领取租约有界，网络调用不进入本服务的事务。 */
    public VoucherOperationService(ApprovedVoucherSources sources, JdbcVoucherOperationRepository operations,
            ApplicationEventPublisher events, @Value("${agentflow.vouchers.lease-seconds:90}") int leaseSeconds) {
        if (leaseSeconds < 15 || leaseSeconds > 300) throw new IllegalArgumentException("Voucher lease must be between 15 and 300 seconds");
        this.sources = sources;
        this.operations = operations; this.events = events; this.lease = Duration.ofSeconds(leaseSeconds);
    }

    /** 结算准备服务在已有事务内登记；重新从真实批准聚合派生全部金额和分摊，不信任传入命令自身的声明。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public VoucherOperation register(VoucherCommand command, String targetDigest, Instant now) {
        lock(command);
        var input = new VoucherOperation.Input(command, targetDigest);
        var existing = operations.forRound(command.tenantId(), command.binding().applicationId(), command.binding().roundNo(), command.kind()).orElse(null);
        if (existing != null) {
            if (existing.input().equals(input)) return existing;
            throw new DomainException("VOUCHER_OPERATION_EXISTS", "This business round already has an original voucher operation");
        }
        requireSource(command);
        var operation = VoucherOperation.queue(input, time(now)); operations.create(operation); return operation;
    }

    /** 领取后重新读取当前依据；原批准已变的未发送命令停止发送，未知结果仍查询原操作。 */
    @Transactional
    public VoucherOperation claim(String tenant, UUID id, Instant now) {
        var initial = operations.find(tenant, id).orElse(null); if (initial == null) return null;
        lock(initial.input().command()); var current = operations.find(tenant, id).orElseThrow(VoucherOperationService::notFound); now = time(now);
        if (current.expired(now)) { operations.update(current.expire(now)); return null; }
        if (current.running() || current.nextAttemptAt() == null || now.isBefore(current.nextAttemptAt())) return null;
        if (current.status() == VoucherOperation.Status.QUEUED) {
            try { requireSource(current.input().command()); }
            catch (DomainException changed) { operations.update(current.voidBeforeSend(now)); return null; }
        }
        var claimed = current.claim(now, lease); operations.update(claimed); return claimed.running() ? claimed : null;
    }

    /** 有效领取结果和事件同事务落地，事件消费者失败会回滚本地确认，再通过原操作查询恢复。 */
    @Transactional
    public void finish(VoucherOperation claimed, FinanceResult<VoucherObservation> result, Instant now) {
        var current = currentClaim(claimed); if (current == null) return;
        complete(current, current.complete(result, time(now)));
    }

    /** 本地错误只记录稳定分类，不能认定 ERP 未过账。 */
    @Transactional
    public void fail(VoucherOperation claimed, VoucherOperation.Failure failure, Instant now) {
        var current = currentClaim(claimed); if (current == null) return;
        complete(current, current.unavailable(failure, time(now)));
    }

    /** 已获业务权限的财务入口显式复查原操作，不修改原命令或既有事实。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public VoucherOperation query(String tenant, UUID id, long expectedVersion, Instant now) {
        var current = currentVersion(tenant, id, expectedVersion); var next = current.requestQuery(time(now));
        complete(current, next); return next;
    }
    /** 权威查无后仍复核实际批准与发送时效；只重发相同编号和内容。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public VoucherOperation resend(String tenant, UUID id, long expectedVersion, Instant now) {
        var current = currentVersion(tenant, id, expectedVersion); requireSource(current.input().command());
        var next = current.retryNotFound(time(now)); complete(current, next); return next;
    }
    private VoucherOperation currentVersion(String tenant, UUID id, long expectedVersion) {
        var initial = operations.find(tenant, id).orElseThrow(VoucherOperationService::notFound); lock(initial.input().command());
        var current = operations.find(tenant, id).orElseThrow(VoucherOperationService::notFound);
        if (current.version() != expectedVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Voucher operation version changed");
        return current;
    }

    private VoucherOperation currentClaim(VoucherOperation claimed) {
        var command = claimed.input().command(); lock(command);
        var current = operations.find(command.tenantId(), command.id()).orElseThrow(VoucherOperationService::notFound);
        return current.version() == claimed.version() && current.running() && current.status() == claimed.status() && current.input().equals(claimed.input()) ? current : null;
    }
    private void complete(VoucherOperation previous, VoucherOperation value) {
        operations.update(value); events.publishEvent(new VoucherOperationChanged(previous, value));
    }
    private void lock(VoucherCommand command) { sources.lock(sources.reference(command)); }
    private void requireSource(VoucherCommand command) {
        if (!sources.derive(sources.reference(command)).matches(command)) {
            throw new DomainException("VOUCHER_SOURCE_MISMATCH", "Voucher command no longer matches the approved financial source");
        }
    }
    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Voucher financial source or operation not found"); }
}
