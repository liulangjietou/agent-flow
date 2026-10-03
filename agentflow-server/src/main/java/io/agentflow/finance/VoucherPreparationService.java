package io.agentflow.finance;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
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
 * 最终批准只登记准备任务；外部依据通过事务外读取，再按原双版本登记实际凭证。
 * @author owlzhangfq@gmail.com
 */
@Service
public class VoucherPreparationService {
    private final ApplicationRepository applications;
    private final VoucherSources sources;
    private final JdbcVoucherPreparationRepository preparations;
    private final JdbcVoucherOperationRepository operations;
    private final VoucherOperationService execution;
    private final FinanceGatewayConfiguration configuration;
    private final AccountMappingPreparation mappings;
    private final Duration lease;
    /** 两次只读 HTTP 共用有界准备租约，与实际过账租约分开。 */
    public VoucherPreparationService(ApplicationRepository applications, VoucherSources sources, JdbcVoucherPreparationRepository preparations,
            JdbcVoucherOperationRepository operations, VoucherOperationService execution, FinanceGatewayConfiguration configuration, AccountMappingPreparation mappings,
            @Value("${agentflow.vouchers.preparation-lease-seconds:150}") int leaseSeconds) {
        if (leaseSeconds < 15 || leaseSeconds > 300) throw new IllegalArgumentException("Voucher preparation lease must be between 15 and 300 seconds");
        this.applications = applications; this.sources = sources; this.preparations = preparations; this.operations = operations;
        this.execution = execution; this.configuration = configuration; this.mappings = mappings; this.lease = Duration.ofSeconds(leaseSeconds);
    }
    /** 与真实最终批准同事务，普通表单、事前申请与中间节点不生成会计准备。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void approved(Application application, String actor) {
        if (application.status() != ApplicationStatus.APPROVED || application.businessReference() == null) return;
        var type = application.businessReference().type();
        if (type != BusinessReference.Type.ADVANCE_REQUEST && type != BusinessReference.Type.EXPENSE) return;
        var source = sources.reference(application); sources.lock(source);
        if (preparations.latest(source).isPresent() || operations.forRound(source.tenantId(), source.applicationId(), source.roundNo(), source.kind()).isPresent()) return;
        enqueue(source, 1, actor, time(Instant.now()));
    }
    /** 已保存的成功付款只登记只读准备，ERP 或会计规则暂不可用不回滚真实银行成功。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void paid(PaymentOperation payment) {
        var source = sources.reference(payment); sources.lock(source);
        if (preparations.latest(source).isPresent() || operations.forRound(source.tenantId(), source.applicationId(), source.roundNo(), source.kind()).isPresent()) return;
        enqueue(source, 1, payment.input().command().authorization().executedBy(), time(Instant.now()));
    }
    /** 付款准备重试保留原回单修订；不能借重试刷新或替换支付命令。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public VoucherPreparation retryPayment(String tenant, UUID applicationId, int roundNo, String actor, Instant now) {
        var original = preparations.latest(tenant, applicationId, roundNo, VoucherCommand.Kind.PAYMENT).orElseThrow(VoucherPreparationService::notFound);
        sources.lock(original.input().source());
        var current = preparations.latest(tenant, applicationId, roundNo, VoucherCommand.Kind.PAYMENT).orElseThrow(VoucherPreparationService::notFound);
        if (operations.forRound(tenant, applicationId, roundNo, VoucherCommand.Kind.PAYMENT).isPresent()) {
            throw new DomainException("VOUCHER_OPERATION_EXISTS", "Reconcile the original payment voucher before preparing another command");
        }
        if (current.active()) return current;
        return enqueue(current.input().source(), Math.incrementExact(current.input().attempt()), actor, time(now));
    }
    /** 财务操作入口只允许重做失败的只读准备，已登记凭证不能换编号再准备。 */
    @Transactional
    public VoucherPreparation retry(String tenant, UUID applicationId, long expectedVersion, String actor, Instant now) {
        var initial = applications.findById(tenant, applicationId).orElseThrow(VoucherPreparationService::notFound);
        var source = sources.reference(initial); sources.lock(source);
        var application = applications.findById(tenant, applicationId).orElseThrow(VoucherPreparationService::notFound);
        if (application.version() != expectedVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Approval version changed");
        source = sources.reference(application);
        if (operations.forRound(tenant, applicationId, source.roundNo(), source.kind()).isPresent()) {
            throw new DomainException("VOUCHER_OPERATION_EXISTS", "Reconcile the original voucher operation before preparing another command");
        }
        var latest = preparations.latest(source).orElse(null);
        if (latest != null && (latest.active() || latest.status() == VoucherPreparation.Status.NOT_REQUIRED)) return latest;
        return enqueue(source, latest == null ? 1 : Math.incrementExact(latest.input().attempt()), actor, time(now));
    }
    /** 已过期领取只结束准备；未开始的任务按实际源和当时目标产生可见结果。 */
    @Transactional
    public Work claim(String tenant, UUID id, Instant now) {
        var initial = preparations.find(tenant, id).orElse(null); if (initial == null) return null;
        sources.lock(initial.input().source()); var current = preparations.find(tenant, id).orElseThrow(VoucherPreparationService::notFound); now = time(now);
        if (current.expired(now)) { preparations.update(current.finish(VoucherPreparation.Result.unavailable("LEASE_EXPIRED"), now)); return null; }
        if (current.status() != VoucherPreparation.Status.QUEUED) return null;
        VoucherSource.Plan source; AccountMappingPort.Request request;
        try {
            source = sources.derive(current.input().source());
            request = mappings.select(tenant, source.mappingRequest(), current.input().targetDigest());
        } catch (DomainException problem) {
            var failed = current.start(now, now.plus(lease)); preparations.update(failed);
            preparations.update(failed.finish(classify(problem), now)); return null;
        }
        var started = current.start(now, now.plus(lease), request); preparations.update(started);
        return new Work(started, source);
    }
    /** 准备完成和实际凭证登记原子提交；过期或失效来源不留下可发送任务。 */
    @Transactional
    public void finish(VoucherPreparation claimed, VoucherCommand command, VoucherPreparation.Result problem, Instant now) {
        sources.lock(claimed.input().source()); var current = preparations.find(claimed.input().source().tenantId(), claimed.input().id()).orElseThrow(VoucherPreparationService::notFound);
        if (current.status() != VoucherPreparation.Status.RUNNING || !current.equals(claimed)) return;
        now = time(now);
        if (current.expired(now)) { preparations.update(current.finish(VoucherPreparation.Result.unavailable("LEASE_EXPIRED"), now)); return; }
        if (problem != null) { preparations.update(current.finish(problem, now)); return; }
        if (command == null || !command.id().equals(current.input().id()) || !sources.derive(current.input().source()).matches(command)) {
            throw new DomainException("VOUCHER_SOURCE_CHANGED", "Prepared command no longer matches original approval");
        }
        VoucherPreparation.Result mappingFailure = null;
        try { mappings.requireCurrent(current, command); }
        catch (DomainException changed) { mappingFailure = classify(changed); }
        now = completionTime(now);
        if (mappingFailure != null || current.expired(now)) {
            preparations.update(current.finish(mappingFailure == null ? VoucherPreparation.Result.unavailable("LEASE_EXPIRED") : mappingFailure, now)); return;
        }
        execution.register(command, current.input().targetDigest(), now);
        preparations.update(current.finish(VoucherPreparation.Result.ready(command.id()), now));
    }
    private VoucherPreparation enqueue(VoucherPreparation.Source source, long attempt, String actor, Instant now) {
        String target = configuration.destination(source.tenantId()).map(destination -> destination.digest(source.tenantId())).orElse(null);
        var value = VoucherPreparation.queue(new VoucherPreparation.Input(UUID.randomUUID(), source, attempt, actor, target), now);
        preparations.create(value); return value;
    }
    /** 仅稳定领域分类对外可见，不把异常消息、财务正文或凭据写入任务。 */
    public static VoucherPreparation.Result classify(DomainException problem) {
        return switch (problem.code()) {
            case "VOUCHER_SOURCE_CHANGED", "VOUCHER_SOURCE_MISMATCH", "NOT_FOUND" -> VoucherPreparation.Result.voided();
            case "VOUCHER_ZERO_AMOUNT" -> VoucherPreparation.Result.notRequired();
            case "VOUCHER_EVIDENCE_EXPIRED", "NOT_CONFIGURED", "TARGET_CHANGED", "ACCOUNT_MAPPING_CHANGED", "ACCOUNT_MAPPING_SELECTION_MISSING" -> VoucherPreparation.Result.unavailable(problem.code());
            default -> VoucherPreparation.Result.blocked(problem.code());
        };
    }
    private static Instant time(Instant now) { return now.truncatedTo(ChronoUnit.MICROS); }
    /** 锁后重新计时，保留调用方已观察到的更晚时刻，锁等待不能延长租约。 */
    private static Instant completionTime(Instant requested) { var observed = Instant.now(); return time(observed.isAfter(requested) ? observed : requested); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Voucher preparation or approved application not found"); }
    /**
     * 实际源在领取事务内派生，执行器只持有不可变数据且不携带数据库锁。
     * @author owlzhangfq@gmail.com
     */
    public record Work(VoucherPreparation preparation, VoucherSource.Plan source) { }
}
