package io.agentflow.signature;

import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.auth.DeferredActorAuthentication;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 签署授权和领取采用短事务；首次权限失效只取消未发送操作，已发送恢复保留原身份。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SignatureOperationService {
    private static final Set<String> REVOKED_ACCESS = Set.of("FORBIDDEN", "NOT_FOUND", "SIGNATURE_SOURCE_CHANGED", "ATTACHMENT_NOT_READY");
    private final CurrentActor actors;
    private final ApplicationRepository applications;
    private final JdbcSignatureOperationRepository operations;
    private final JdbcSignatureLoginRepository logins;
    private final JdbcSignatureEvidenceRepository evidence;
    private final DeferredActorAuthentication authentication;
    private final SignatureAccess access;
    private final SignatureAudit audit;
    private final Duration lease;

    /** 领取租约留出网络和持久化余量；HTTP 调用由独立执行器在事务结束后发起。 */
    public SignatureOperationService(CurrentActor actors, ApplicationRepository applications, JdbcSignatureOperationRepository operations,
            JdbcSignatureLoginRepository logins, JdbcSignatureEvidenceRepository evidence, DeferredActorAuthentication authentication,
            SignatureAccess access, SignatureAudit audit, @Value("${agentflow.signatures.lease-seconds:90}") int leaseSeconds) {
        if (leaseSeconds < 15 || leaseSeconds > 300) throw new IllegalArgumentException("Signature lease must be between 15 and 300 seconds");
        this.actors = actors; this.applications = applications; this.operations = operations; this.logins = logins; this.evidence = evidence;
        this.authentication = authentication; this.access = access; this.audit = audit; this.lease = Duration.ofSeconds(leaseSeconds);
    }

    /** 申请锁先于操作锁；原登录、原件与审计和创建结果一起提交，不执行网络或文件传输。 */
    @Transactional
    public SignatureOperation create(UUID application, SignatureAccess.CreateInput command, HttpServletRequest http, Instant now) {
        var actor = actors.actor();
        applications.lockById(actor.tenantId(), application).orElseThrow(SignatureOperationService::notFound);
        var input = access.prepare(actor, application, command, now);
        var login = authentication.capture(http, actor, now);
        var operation = SignatureOperation.queue(input, now);
        operations.create(operation); logins.insert(operation, login);
        audit.record(operation, SignatureAudit.Action.SIGNATURE_AUTHORIZED, actor.userId());
        return operation;
    }

    /** 每次读取复核当前字段权限，原授权失效不改变其他当前合法读取者的权限。 */
    public SignatureOperation get(UUID application, UUID id) {
        var actor = actors.actor();
        var operation = operations.find(actor.tenantId(), id).filter(value -> value.input().request().source().applicationId().equals(application))
                .orElseThrow(SignatureOperationService::notFound);
        access.requireReadable(actor, operation);
        return operation;
    }

    /** 只允许原发起人取消尚未发送的授权，不把未知远端结果当作可以本地撤销。 */
    @Transactional
    public SignatureOperation cancel(UUID application, UUID id, long expectedVersion, Instant now) {
        var current = get(application, id); lockApplication(current);
        current = operations.lock(actors.actor().tenantId(), id).orElseThrow(SignatureOperationService::notFound);
        access.requireReadable(actors.actor(), current);
        if (!current.input().request().authorization().actor().equals(actors.actor().userId())) throw new DomainException("FORBIDDEN", "Only the original signature authorizer may cancel");
        if (current.version() != expectedVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Signature operation changed");
        var cancelled = current.cancelUnsent(now); operations.update(cancelled);
        audit.record(cancelled, SignatureAudit.Action.SIGNATURE_CANCELLED, actors.actor().userId());
        return cancelled;
    }

    /** 只对首次发送复核原授权；原登录失效后仍可查询已经发出的原号和保存签署结果。 */
    @Transactional
    public SignatureOperation claim(String tenant, UUID id, Instant now) {
        var initial = operations.find(tenant, id).orElse(null); if (initial == null) return null;
        lockApplication(initial);
        var current = operations.lock(tenant, id).orElseThrow(SignatureOperationService::notFound);
        if (current.terminal()) return null;
        if (current.expired(now)) { save(current.expire(now), SignatureAudit.Action.SIGNATURE_LEASE_EXPIRED); return null; }
        if (current.running() || now.isBefore(current.nextAttemptAt())) return null;
        if (current.status() == SignatureOperation.Status.QUEUED && now.isBefore(current.input().request().authorization().validUntil()) && !authorized(current, now)) {
            save(current.cancelUnsent(now), SignatureAudit.Action.SIGNATURE_AUTHORIZATION_REVOKED); return null;
        }
        var claim = current.claim(now, lease);
        save(claim, claim.terminal() ? SignatureAudit.Action.SIGNATURE_EXPIRED : SignatureAudit.Action.SIGNATURE_CLAIMED);
        return claim.running() ? claim : null;
    }

    /** 接受状态、文件预留和完整签名证据在同一事务中保存；迟到领取不能覆盖新事实。 */
    @Transactional
    public void finish(SignatureOperation claim, SignatureGateway.ReceiptResult result, Instant now) {
        var current = currentClaim(claim); if (current == null) return;
        var completed = result instanceof SignatureGateway.Observed observed ? current.complete(observed.verified().receipt(), now)
                : current.unavailable(result instanceof SignatureGateway.Unavailable unavailable ? unavailable.failure() : SignatureOperation.Failure.INVALID_RESPONSE, now);
        save(completed, result instanceof SignatureGateway.Observed ? SignatureAudit.Action.SIGNATURE_OBSERVED : SignatureAudit.Action.SIGNATURE_RETRY);
        if (result instanceof SignatureGateway.Observed observed && accepted(completed, observed.verified().receipt())) evidence.append(completed, observed.verified().evidence());
    }

    /** 每份结果只确认本次有效领取；已保存的文件也必须先经存储层重新检查实际字节。 */
    @Transactional
    public boolean confirmFile(SignatureOperation claim, SignatureOperation.StoredArtifact file, Instant now) {
        var current = currentClaim(claim);
        if (current == null) return false;
        if (current.expired(now)) { save(current.expire(now), SignatureAudit.Action.SIGNATURE_LEASE_EXPIRED); return false; }
        operations.markArtifactReady(current, file, now);
        return true;
    }

    /** 所有逐份确认提交后才完成本地签署；数据库再核对全部结果 READY。 */
    @Transactional
    public void finishFiles(SignatureOperation claim, SignatureOperation.Failure failure, Instant now) {
        var current = currentClaim(claim); if (current == null) return;
        save(failure == null ? current.completeFiles(current.artifacts(), now) : current.unavailable(failure, now),
                failure == null ? SignatureAudit.Action.SIGNATURE_SAVED : SignatureAudit.Action.SIGNATURE_RETRY);
    }

    private boolean authorized(SignatureOperation operation, Instant now) {
        var request = operation.input().request();
        var actor = logins.find(operation).flatMap(login -> authentication.resolve(login, request.tenantId(), request.authorization().actor(), now));
        if (actor.isEmpty()) return false;
        try { access.requireInitialSend(actor.get(), operation); return true; }
        catch (DomainException revoked) { if (REVOKED_ACCESS.contains(revoked.code())) return false; throw revoked; }
    }

    private SignatureOperation currentClaim(SignatureOperation claim) {
        lockApplication(claim);
        var request = claim.input().request(); var current = operations.lock(request.tenantId(), request.id()).orElseThrow(SignatureOperationService::notFound);
        return current.equals(claim) && current.running() ? current : null;
    }
    private void lockApplication(SignatureOperation operation) {
        var request = operation.input().request(); applications.lockById(request.tenantId(), request.source().applicationId()).orElseThrow(SignatureOperationService::notFound);
    }
    private void save(SignatureOperation operation, SignatureAudit.Action action) {
        operations.update(operation); audit.record(operation, action, SignatureAudit.WORKER);
    }
    private static boolean accepted(SignatureOperation operation, SignatureReceipt receipt) {
        return receipt.status() == SignatureReceipt.Status.NOT_FOUND
                ? operation.status() == SignatureOperation.Status.UNKNOWN && operation.failure() == SignatureOperation.Failure.NOT_FOUND
                : operation.receipt() != null && operation.receipt().equals(receipt);
    }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Signature operation or application not found"); }
}
