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
 * 原付款执行的短事务编排；账户读取和资金调用只在事务提交后进行。
 * @author owlzhangfq@gmail.com
 */
@Service
public class PaymentOperationService {
    private final ApprovedPaymentSources sources;
    private final JdbcPaymentAuthorizationRepository authorizations;
    private final JdbcPaymentOperationRepository operations;
    private final PaymentPersonnel personnel;
    private final ApplicationEventPublisher events;
    private final Duration lease;
    /** 租约覆盖有界账户检查和资金请求，过期结果由原操作查询恢复。 */
    public PaymentOperationService(ApprovedPaymentSources sources, JdbcPaymentAuthorizationRepository authorizations, JdbcPaymentOperationRepository operations,
                                   PaymentPersonnel personnel, ApplicationEventPublisher events, @Value("${agentflow.payments.lease-seconds:90}") int leaseSeconds) {
        if (leaseSeconds < 15 || leaseSeconds > 300) throw new IllegalArgumentException("Payment lease must be between 15 and 300 seconds");
        this.sources = sources; this.authorizations = authorizations; this.operations = operations; this.personnel = personnel; this.events = events; this.lease = Duration.ofSeconds(leaseSeconds);
    }
    /** 已获当前财务与出纳入口授权的执行登记和队列原子保存，不能凭构造命令跳过实际来源。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentOperation register(PaymentAuthorization authorization, Instant now) {
        sources.lock(authorization); var current = authorization(authorization.terms().tenantId(), authorization.terms().id());
        if (!current.equals(authorization) || current.status() != PaymentAuthorization.Status.EXECUTION_REGISTERED) throw conflict();
        requireSource(current, time(now));
        var queued = PaymentOperation.queue(current, time(now)); var existing = operations.find(current.terms().tenantId(), current.terms().id()).orElse(null);
        if (existing != null) {
            if (existing.input().equals(queued.input())) return existing;
            throw conflict();
        }
        operations.create(queued); return queued;
    }
    /** 查询领取不再依赖仍获批准，避免撤销或停用人员后无法追踪已经发送的交易。 */
    @Transactional
    public PaymentOperation claim(String tenant, UUID id, Instant now) {
        var current = locked(tenant, id); if (current == null) return null; now = time(now);
        if (current.expired(now)) { complete(current, current.expire(now)); return null; }
        if (current.running() || current.nextAttemptAt() == null || now.isBefore(current.nextAttemptAt())) return null;
        if (current.status() == PaymentOperation.Status.QUEUED) {
            try { requireSource(authorization(tenant, id), now); }
            catch (DomainException changed) { complete(current, current.voidBeforeSend(PaymentOperation.Failure.SOURCE_CHANGED, now)); return null; }
        }
        var claimed = current.claim(now, lease); complete(current, claimed); return claimed.running() ? claimed : null;
    }
    /** 账户查询已在事务外完成，此处重新核对当前批准、凭证和人员后登记可能发送。 */
    @Transactional
    public PaymentOperation readyToSend(PaymentOperation checking, PaymentAccountsPort.Directory directory, EmployeeAccountPort.Account account, Instant now) {
        var current = currentClaim(checking); if (current == null) return null; now = time(now);
        if (current.expired(now)) { complete(current, current.expire(now)); return null; }
        try { requireSource(authorization(current.input().command().tenantId(), current.input().command().id()), now); }
        catch (DomainException changed) { complete(current, current.voidBeforeSend(PaymentOperation.Failure.SOURCE_CHANGED, now)); return null; }
        var sending = current.readyToSend(directory, account, now); complete(current, sending);
        return sending.status() == PaymentOperation.Status.SENDING ? sending : null;
    }
    /** 账户读取的明确拒绝停止新发送，网络不可用只重新检查，二者都不是银行付款失败。 */
    @Transactional
    public void checkFailed(PaymentOperation checking, PaymentOperation.Failure failure, boolean accountsChanged, Instant now) {
        var current = currentClaim(checking); if (current == null) return; now = time(now);
        complete(current, current.expired(now) ? current.expire(now) : accountsChanged
                ? current.voidBeforeSend(PaymentOperation.Failure.ACCOUNT_CHANGED, now) : current.unavailableBeforeSend(failure, now));
    }
    /** 资金事实和本地结算事件同事务落地；消费者失败会回滚后通过原交易恢复。 */
    @Transactional
    public void finish(PaymentOperation claimed, FinanceResult<PaymentObservation> result, Instant now) {
        var current = currentClaim(claimed); if (current != null) complete(current, current.complete(result, time(now)));
    }
    /** 进入可能发送后不再声称未付款，只记录稳定失败分类并恢复查询。 */
    @Transactional
    public void fail(PaymentOperation claimed, PaymentOperation.Failure failure, Instant now) {
        var current = currentClaim(claimed); if (current != null) complete(current, current.unavailable(failure, time(now)));
    }
    /** 已通过真实岗位和业务范围校验的入口显式复查原付款。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentOperation query(String tenant, UUID id, long expectedVersion, Instant now) {
        var current = currentVersion(tenant, id, expectedVersion); var next = current.requestQuery(time(now)); complete(current, next); return next;
    }
    /** 已验签且绑定原命令的回调只能安排查询；旧授权安全结束后也保留原银行的迟到事实。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentOperation callbackQuery(String tenant, UUID id, long expectedVersion, Instant now) {
        var current = locked(tenant, id); if (current == null || current.version() != expectedVersion) throw conflict();
        var next = current.requestCallbackQuery(time(now)); complete(current, next); return next;
    }
    /** 显式重发仍受原有效期、真实批准和当前人员约束，不能改变命令或另造授权号。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentOperation resend(String tenant, UUID id, long expectedVersion, Instant now) {
        var current = currentVersion(tenant, id, expectedVersion); requireSource(authorization(tenant, id), time(now));
        var next = current.retryNotFound(time(now)); complete(current, next); return next;
    }
    /** 只在财务结束事务内停止未发送队列，迟到账户复查无法继续发送旧授权。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentOperation stopForRetirement(String tenant, UUID id, long expectedVersion, Instant now) {
        var current = currentVersion(tenant, id, expectedVersion); var next = current.stopForRetirement(time(now));
        if (!next.equals(current)) complete(current, next);
        return next;
    }
    private PaymentOperation currentVersion(String tenant, UUID id, long expected) {
        var current = locked(tenant, id);
        if (current == null || current.version() != expected || authorization(tenant, id).status() != PaymentAuthorization.Status.EXECUTION_REGISTERED) throw conflict();
        return current;
    }
    private PaymentOperation locked(String tenant, UUID id) {
        var current = operations.find(tenant, id).orElse(null); if (current == null) return null;
        sources.lock(authorization(tenant, id)); return operations.find(tenant, id).orElseThrow(PaymentOperationService::conflict);
    }
    private PaymentOperation currentClaim(PaymentOperation claimed) {
        var current = locked(claimed.input().command().tenantId(), claimed.input().command().id());
        return current != null && current.running() && current.version() == claimed.version() && current.status() == claimed.status() && current.input().equals(claimed.input()) ? current : null;
    }
    private void requireSource(PaymentAuthorization authorization, Instant now) {
        sources.requireCurrent(authorization, now); var terms = authorization.terms();
        personnel.requireEligible(terms.tenantId(), authorization.decision().authorizedBy(), terms.payee().legalEntityId());
        if (authorization.execution() != null) personnel.requireEligible(terms.tenantId(), authorization.execution().command().authorization().executedBy(), terms.payee().legalEntityId());
    }
    private PaymentAuthorization authorization(String tenant, UUID id) { return authorizations.find(tenant, id).orElseThrow(PaymentOperationService::conflict); }
    private void complete(PaymentOperation previous, PaymentOperation current) { operations.update(current); events.publishEvent(new PaymentOperationChanged(previous, current)); }
    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Original payment authorization or execution version changed"); }
}
