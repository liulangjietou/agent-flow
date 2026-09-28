package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 出纳意图、事务外复查与原付款登记之间的短事务编排，不在幂等请求中等待账户 HTTP。
 * @author owlzhangfq@gmail.com
 */
@Service
public class PaymentExecutionRequestService {
    private final ApprovedPaymentSources sources;
    private final JdbcPaymentAuthorizationRepository authorizations;
    private final JdbcPaymentExecutionRequestRepository requests;
    private final PaymentOperationService payments;
    private final PaymentPersonnel personnel;
    private final Duration lease;
    /** 请求租约只覆盖只读检查，崩溃后始终可以恢复同一选择。 */
    public PaymentExecutionRequestService(ApprovedPaymentSources sources, JdbcPaymentAuthorizationRepository authorizations, JdbcPaymentExecutionRequestRepository requests,
                                          PaymentOperationService payments, PaymentPersonnel personnel, @Value("${agentflow.payments.request-lease-seconds:90}") int leaseSeconds) {
        if (leaseSeconds < 15 || leaseSeconds > 300) throw new IllegalArgumentException("Payment request lease must be between 15 and 300 seconds");
        this.sources = sources; this.authorizations = authorizations; this.requests = requests; this.payments = payments; this.personnel = personnel; this.lease = Duration.ofSeconds(leaseSeconds);
    }
    /** 认证入口已校验 CASHIER 角色，锁内重新核对原授权及业务，不接受另一份账户选择。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentExecutionRequest register(PaymentAuthorization expected, String cashier, String debitReference, String debitVersion, Instant now) {
        sources.lock(expected); now = time(now); var current = authorization(expected.terms().tenantId(), expected.terms().id());
        if (!current.equals(expected)) throw conflict();
        if (requests.forAuthorization(current.terms().tenantId(), current.terms().id()).isPresent()) throw new DomainException("PAYMENT_EXECUTION_ALREADY_REQUESTED", "Original authorization already has a fixed cashier request");
        requireSource(current, cashier, now);
        var request = PaymentExecutionRequest.queue(UUID.randomUUID(), current, cashier, debitReference, debitVersion, now); requests.create(request); return request;
    }
    /** 只领取还在原授权窗口内的请求，失效来源关闭请求但不能生成资金结果。 */
    @Transactional
    public Work claim(String tenant, UUID id, Instant now) {
        var current = locked(tenant, id); if (current == null || !current.active()) return null; now = time(now);
        if (current.leaseExpired(now)) { requests.update(current.expireLease(now)); return null; }
        if (current.status() == PaymentExecutionRequest.Status.RUNNING || now.isBefore(current.nextAttemptAt())) return null;
        var authorization = available(current, now); if (authorization == null) return null;
        var claimed = current.claim(now, lease); requests.update(claimed); return new Work(claimed, authorization);
    }
    /** 两端账户通过复查后，原授权执行登记、付款队列和请求 READY 在同一事务生效。 */
    @Transactional
    public void finish(Work work, PaymentAccountsPort.Directory directory, EmployeeAccountPort.Account payee, Instant now) {
        var current = currentClaim(work); if (current == null) return; now = time(now);
        if (current.leaseExpired(now)) { requests.update(current.expireLease(now)); return; }
        var authorization = available(current, now); if (authorization == null) return;
        PaymentAuthorization executed;
        try { executed = current.register(authorization, directory, payee, sources.requireCurrent(authorization, now), now); }
        catch (DomainException changed) {
            if (!"PAYMENT_ACCOUNT_CHANGED".equals(changed.code()) && !"PAYMENT_DEBIT_ACCOUNT_UNAVAILABLE".equals(changed.code())) throw changed;
            requests.update(current.block(now)); return;
        }
        authorizations.update(executed); payments.register(executed, now); requests.update(current.ready(executed, now));
    }
    /** 业务拒绝与暂时不可用分别落库，失效租约只恢复读取；从不伪造资金失败。 */
    @Transactional
    public void fail(Work work, PaymentExecutionRequest.Failure failure, boolean accountChanged, Instant now) {
        var current = currentClaim(work); if (current == null) return; now = time(now);
        if (current.leaseExpired(now)) { requests.update(current.expireLease(now)); return; }
        if (available(current, now) == null) return;
        requests.update(accountChanged ? current.block(now) : current.unavailable(failure, now));
    }
    private PaymentAuthorization available(PaymentExecutionRequest request, Instant now) {
        var input = request.input(); var value = authorization(input.tenantId(), input.authorizationId());
        if (value.status() != PaymentAuthorization.Status.AUTHORIZED || value.version() != input.authorizationVersion()) {
            requests.update(request.voidSource(now)); return null;
        }
        if (!now.isBefore(value.decision().expiresAt())) {
            authorizations.update(value.expire(now)); requests.update(request.expireAuthorization(now)); return null;
        }
        try { requireSource(value, input.cashier(), now); }
        catch (DomainException changed) { requests.update(request.voidSource(now)); return null; }
        return value;
    }
    private void requireSource(PaymentAuthorization authorization, String cashier, Instant now) {
        sources.requireCurrent(authorization, now); var terms = authorization.terms();
        personnel.requireEligible(terms.tenantId(), authorization.decision().authorizedBy(), terms.payee().legalEntityId());
        personnel.requireEligible(terms.tenantId(), cashier, terms.payee().legalEntityId());
    }
    private PaymentExecutionRequest locked(String tenant, UUID id) {
        var initial = requests.find(tenant, id).orElse(null); if (initial == null) return null;
        sources.lock(authorization(tenant, initial.input().authorizationId())); return requests.find(tenant, id).orElseThrow(PaymentExecutionRequestService::conflict);
    }
    private PaymentExecutionRequest currentClaim(Work work) {
        var claimed = work.request(); var current = locked(claimed.input().tenantId(), claimed.input().id());
        return current != null && current.status() == PaymentExecutionRequest.Status.RUNNING && current.version() == claimed.version() && current.input().equals(claimed.input()) ? current : null;
    }
    private PaymentAuthorization authorization(String tenant, UUID id) { return authorizations.find(tenant, id).orElseThrow(PaymentExecutionRequestService::conflict); }
    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Payment request or original authorization changed"); }
    /**
     * 事务外读取只使用持久授权与原选择，不能由 HTTP 回执改写财务目标。
     * @author owlzhangfq@gmail.com
     */
    public record Work(PaymentExecutionRequest request, PaymentAuthorization authorization) { }
}
