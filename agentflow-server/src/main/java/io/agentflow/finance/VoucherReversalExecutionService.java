package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 独立冲销执行的短事务边界，原件停用、命令、执行结果及原凭证查询意图原子保存。
 * @author owlzhangfq@gmail.com
 */
@Service
public class VoucherReversalExecutionService {
    private static final Duration LEASE = Duration.ofSeconds(90);
    private final VoucherReversalSources sources;
    private final JdbcVoucherReversalOperationRepository operations;
    private final JdbcVoucherReversalPreparationRepository preparations;
    private final JdbcVoucherOperationRepository originals;
    private final JdbcVoucherReversalRecordRepository records;
    private final PaymentPersonnel personnel;
    private final ApplicationEventPublisher events;
    /** 首次发送和未知结果恢复分别处理，查询不要求已过期的授权重新生效。 */
    public VoucherReversalExecutionService(VoucherReversalSources sources, JdbcVoucherReversalOperationRepository operations,
            JdbcVoucherReversalPreparationRepository preparations, JdbcVoucherOperationRepository originals,
            JdbcVoucherReversalRecordRepository records, PaymentPersonnel personnel, ApplicationEventPublisher events) {
        this.sources = sources; this.operations = operations; this.preparations = preparations; this.originals = originals;
        this.records = records; this.personnel = personnel; this.events = events;
    }
    /** 调用方完成当前身份授权并消费准备，命令与原件冻结必须在同一个事务内。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public VoucherReversalOperation register(VoucherReversalPreparation prepared, Instant at) {
        var input = prepared.input(); var command = prepared.command(); var original = input.source().command(); var now = time(at);
        var source = sources.locked(original.tenantId(), original.id());
        requireAvailable(prepared, source, false); var next = VoucherReversalOperation.queue(new VoucherReversalOperation.Input(input.operationVersion(), command, input.targetDigest()), now);
        operations.create(next);
        var held = source.current().requestReversal(command.id(), now); originals.update(held); events.publishEvent(new VoucherOperationChanged(source.current(), held));
        return next;
    }
    /** 领取新写命令前重新核对原件与人员；租约过期只进入原编号查询。 */
    @Transactional
    public VoucherReversalOperation claim(String tenant, UUID id, Instant at) {
        var initial = operations.find(tenant, id).orElse(null); if (initial == null) return null;
        var original = initial.input().command().source().command(); var source = sources.locked(tenant, original.id());
        var current = operations.find(tenant, id).orElseThrow(); var now = time(at);
        if (current.expired(now)) { persist(current, current.expire(now)); return null; }
        if (current.running() || current.nextAttemptAt() == null || now.isBefore(current.nextAttemptAt())) return null;
        if (current.status() == VoucherReversalOperation.Status.QUEUED) {
            try { requireAvailable(preparations.find(tenant, id).orElseThrow(), source, true); }
            catch (DomainException changed) { persist(current, current.voidBeforeSend(now)); return null; }
        }
        var claimed = current.claim(now, LEASE); persist(current, claimed); return claimed.running() ? claimed : null;
    }
    /** 保存真实结果后安排原凭证复查，不把反向命令回执冒充原凭证查询或银行回款。 */
    @Transactional
    public void finish(VoucherReversalOperation claimed, FinanceResult<VoucherReversalObservation> result, Instant at) {
        var current = current(claimed); if (current == null) return; var now = time(at);
        var next = current.complete(result, now); persist(current, next);
        if (next.status() == VoucherReversalOperation.Status.POSTED) {
            var command = next.input().command(); var original = originals.find(command.source().command().tenantId(), command.source().command().id()).orElseThrow();
            if (!original.running() && original.status() != VoucherOperation.Status.QUEUED) {
                var query = original.requestQuery(now); originals.update(query); events.publishEvent(new VoucherOperationChanged(original, query));
            }
        }
    }
    /** 本地错误也按结果未知保存，原件冻结继续保持。 */
    @Transactional
    public void fail(VoucherReversalOperation claimed, VoucherReversalOperation.Failure failure, Instant at) {
        var current = current(claimed); if (current != null) persist(current, current.unavailable(failure, time(at)));
    }
    /** 已授权财务可明确查询，原命令、原编号和全部历史保持。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public VoucherReversalOperation query(String tenant, UUID id, long version, Instant at) {
        var value = version(tenant, id, version); var next = value.requestQuery(time(at)); persist(value, next); return next;
    }
    /** 权威查无后重新核对来源，再人工重发原命令；不能创建替代编号。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public VoucherReversalOperation resend(String tenant, UUID id, long version, Instant at) {
        var value = version(tenant, id, version); var source = sources.find(tenant, value.input().command().source().command().id());
        requireAvailable(preparations.find(tenant, id).orElseThrow(), source, true);
        var next = value.retryNotFound(time(at)); persist(value, next); return next;
    }
    /** 页面只提示当前能否重发，实际写入仍在锁内复核相同条件。 */
    public String resendIssue(VoucherReversalOperation value, VoucherReversalSources.Source source, Instant now) {
        try {
            var command = value.input().command(); requireAvailable(preparations.find(command.source().command().tenantId(), command.id()).orElseThrow(), source, true);
            value.retryNotFound(now); return null;
        } catch (DomainException issue) { return issue.code(); }
    }
    private void requireAvailable(VoucherReversalPreparation prepared, VoucherReversalSources.Source source, boolean held) {
        var input = prepared.input(); var command = input.source().command(); var original = source.current();
        if (prepared.status() != VoucherReversalPreparation.Status.AUTHORIZED || !source.request().equals(input.source()) || source.originalVersion() != input.originalVersion()
                || !original.input().targetDigest().equals(input.targetDigest()) || original.status() != VoucherOperation.Status.POSTED
                || (held ? !prepared.command().id().equals(original.reversalId()) : !original.usablePosted() || original.version() != input.operationVersion())
                || original.highestRevision() > prepared.command().verifiedOriginal().revision()
                || !prepared.command().source().matchesOriginal(original.observation()) || records.forOperation(command.tenantId(), command.id()).isPresent()) {
            throw new DomainException("VOUCHER_REVERSAL_SOURCE_CHANGED", "Original posting or reversal authorization changed before first send");
        }
        sources.requireVersions(input); personnel.requireEligible(command.tenantId(), input.requestedBy(), command.legalEntityId());
    }
    private VoucherReversalOperation current(VoucherReversalOperation claimed) {
        var command = claimed.input().command(); var tenant = command.source().command().tenantId(); sources.locked(tenant, command.source().command().id());
        return operations.find(tenant, command.id()).filter(value -> value.equals(claimed) && value.running()).orElse(null);
    }
    private VoucherReversalOperation version(String tenant, UUID id, long version) {
        var initial = operations.find(tenant, id).orElseThrow(VoucherReversalExecutionService::conflict); sources.locked(tenant, initial.input().command().source().command().id());
        var current = operations.find(tenant, id).orElseThrow();
        if (current.version() != version || operations.retirement(tenant, id).isPresent()) throw conflict(); return current;
    }
    private void persist(VoucherReversalOperation previous, VoucherReversalOperation next) {
        operations.update(next); events.publishEvent(new VoucherReversalOperationChanged(previous, next));
    }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Reversal operation or displayed version changed"); }
    private static Instant time(Instant at) { return at.truncatedTo(ChronoUnit.MICROS); }
}
