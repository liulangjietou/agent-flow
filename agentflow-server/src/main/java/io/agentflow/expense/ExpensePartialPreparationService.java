package io.agentflow.expense;

import org.springframework.context.ApplicationEventPublisher;

import io.agentflow.common.DomainException;
import io.agentflow.finance.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单侧准备、原件观察接受和明确授权由应用层编排；网络读取和财务写入分别交给工作器。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePartialPreparationService {
    private final ApplicationEventPublisher events;
    private static final Duration LEASE = Duration.ofSeconds(90);
    private final ExpenseReportRepository reports;
    private final JdbcExpensePartialAdjustmentRepository adjustments;
    private final JdbcExpensePartialPreparationRepository preparations;
    private final ExpensePartialAdjustmentSources sources;
    private final PaymentPersonnel personnel;
    private final FinanceGatewayConfiguration gateway;
    private final PaymentOperationService payments;
    private final VoucherOperationService vouchers;

    /** 复用原付款和凭证的领域转换及事件，读取新观察不能绕过原争议规则。 */
    public ExpensePartialPreparationService(ExpenseReportRepository reports, JdbcExpensePartialAdjustmentRepository adjustments,
            JdbcExpensePartialPreparationRepository preparations, ExpensePartialAdjustmentSources sources, PaymentPersonnel personnel,
            FinanceGatewayConfiguration gateway, PaymentOperationService payments, VoucherOperationService vouchers, ApplicationEventPublisher events) {
        this.events = events;
        this.reports = reports; this.adjustments = adjustments; this.preparations = preparations; this.sources = sources;
        this.personnel = personnel; this.gateway = gateway; this.payments = payments; this.vouchers = vouchers;
    }

    /** 外层办理入口先检查角色和字段；这里只从实际显示修订建立独立财务读取意图。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExpensePartialAdjustmentPreparation register(String tenant, UUID id, long expected, ExpensePartialAdjustmentPreparation.Side side,
            LocalDate date, String actor, String evidence, String reason, Instant at) {
        var current = adjustment(tenant, id); if (current.version() != expected) throw conflict(); var now = time(at);
        var value = ExpensePartialAdjustmentPreparation.queue(new ExpensePartialAdjustmentPreparation.Input(UUID.randomUUID(), current, side, date, actor, evidence, reason, now));
        requireAvailable(value, now);
        if (preparations.latest(tenant, id, side, actor).filter(ExpensePartialAdjustmentPreparation::active).isPresent()) {
            throw new DomainException("PARTIAL_ADJUSTMENT_PREPARATION_PENDING", "Current finance actor already has a pending preparation for this side");
        }
        preparations.create(value); return value;
    }

    /** 原办理人明确采用最新就绪证据；消费、原操作和证明同事务，工作器不能调用本方法自动授权。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExpensePartialAdjustment authorize(String tenant, UUID id, long expected, UUID preparationId, long preparationVersion, String actor, Instant at) {
        var current = adjustment(tenant, id); var value = preparations.find(tenant, preparationId).orElseThrow(ExpensePartialPreparationService::conflict);
        if (current.version() != expected || !value.input().adjustment().id().equals(id) || value.version() != preparationVersion || !value.input().requestedBy().equals(actor)) throw conflict();
        // 授权按原始证据的精确截止时刻判定，数据库时间精度由 JDBC 仓储处理。
        requireAvailable(value, at); var authorized = value.authorize(current, at); sources.requireCurrent(value.evidence().source());
        return preparations.consume(authorized);
    }

    /** 页面只预览本人最新准备的当前能力，不消费证据，也不刷新有效期或财务状态。 */
    public String authorizationIssue(ExpensePartialAdjustmentPreparation value, Instant at) {
        if (!value.usable(at)) return "PARTIAL_ADJUSTMENT_PREPARATION_CONFLICT";
        var input = value.input(); var tenant = input.adjustment().input().basis().tenantId();
        if (!preparations.latest(tenant, input.adjustment().id(), input.side(), input.requestedBy()).filter(value::equals).isPresent()) return "CONCURRENCY_CONFLICT";
        try {
            requireAvailable(value, at);
            value.authorize(adjustments.find(tenant, input.adjustment().id()).orElseThrow(ExpensePartialPreparationService::conflict), at);
            sources.requireCurrent(value.evidence().source()); return null;
        } catch (DomainException unavailable) { return unavailable.code(); }
    }

    /** 领取只固定读取来源，原付款和凭证在网络等待期间保持当前事实。 */
    @Transactional
    public ExpensePartialAdjustmentPreparation claim(String tenant, UUID id, Instant at) {
        var value = locked(tenant, id); if (value == null) return null; var now = time(at);
        if (value.expired(now)) { save(value.fail("TIMEOUT", now)); return null; }
        if (value.status() != ExpensePartialAdjustmentPreparation.Status.QUEUED) return null;
        ExpenseAdjustmentFundingSource source;
        try { source = requireAvailable(value, now); }
        catch (DomainException changed) { save(value.voidSource(now)); return null; }
        var claimed = value.claim(source, now, LEASE); save(claimed); return claimed;
    }

    /** 先核对领取时原修订仍有效，再通过原状态机接受观察；非法或变化的事实不会生成就绪授权。 */
    @Transactional
    public void finish(ExpensePartialAdjustmentPreparation claimed, ExpensePartialPreparationReader.Snapshot result, Instant at) {
        var current = currentClaim(claimed); if (current == null) return; var now = time(at);
        if (current.expired(now)) { save(current.fail("TIMEOUT", now)); return; }
        try { requireAvailable(current, now); sources.requireCurrent(current.readSource()); }
        catch (DomainException changed) { save(current.voidSource(now)); return; }
        var original = current.readSource();
        if (original.payment() != null) acceptPayment(original.payment(), bankResult(result.bank()), now);
        acceptVoucher(original.financial().accrual(), result.accrual(), now);
        if (original.paymentVoucher() != null) acceptVoucher(original.paymentVoucher(), result.paymentVoucher(), now);
        ExpensePartialAdjustmentPreparation next;
        try {
            var fresh = sources.current(original); now = time(now);
            if (!(result.period() instanceof FinanceResult.Success<AccountingPeriodPort.OpenPeriod> period)) next = current.fail(failure(result.period()), now);
            else if (original.payment() != null && !(result.bank() instanceof FinanceResult.Success<ExpensePaymentReturnPort.Receipt>)) next = current.fail(failure(result.bank()), now);
            else {
                var bank = result.bank() instanceof FinanceResult.Success<ExpensePaymentReturnPort.Receipt> received ? received.value() : null;
                next = current.ready(new ExpensePartialAdjustmentPreparation.Evidence(fresh, bank, period.value(), now), now);
            }
        } catch (DomainException changed) { next = current.fail("SOURCE_CHANGED", now); }
        save(next);
    }

    /** 读取或保存异常保留原意图与稳定分类，不伪造已取得原件。 */
    @Transactional
    public void fail(ExpensePartialAdjustmentPreparation claimed, Instant at) {
        var current = currentClaim(claimed); if (current != null) save(current.fail("INTERNAL_ERROR", time(at)));
    }

    private ExpenseAdjustmentFundingSource requireAvailable(ExpensePartialAdjustmentPreparation value, Instant at) {
        var input = value.input(); var basis = input.adjustment().input().basis(); var current = adjustments.find(basis.tenantId(), input.adjustment().id()).orElseThrow(ExpensePartialPreparationService::conflict);
        value.requireCurrent(current); var source = adjustments.dispatchSource(current); source.requireAuthorization(input.requestedBy(), at);
        personnel.requireEligible(basis.tenantId(), input.requestedBy(), source.financial().accrual().input().command().legalEntityId());
        var target = input.side() == ExpensePartialAdjustmentPreparation.Side.BUDGET ? source.financial().consumption().input().targetDigest() : source.financial().accrual().input().targetDigest();
        if (gateway.destination(basis.tenantId()).filter(destination -> destination.digest(basis.tenantId()).equals(target)).isEmpty()) {
            throw new DomainException("FINANCE_TARGET_CHANGED", "Original partial adjustment target is unavailable");
        }
        return source;
    }
    private void acceptPayment(PaymentOperation original, FinanceResult<PaymentObservation> result, Instant at) {
        var command = original.input().command(); payments.query(command.tenantId(), command.id(), original.version(), at);
        var claimed = payments.claim(command.tenantId(), command.id(), at); if (claimed == null || claimed.status() != PaymentOperation.Status.QUERYING) throw conflict();
        payments.finish(claimed, result, at);
    }
    private void acceptVoucher(VoucherOperation original, FinanceResult<VoucherObservation> result, Instant at) {
        var command = original.input().command(); vouchers.query(command.tenantId(), command.id(), original.version(), at);
        var claimed = vouchers.claim(command.tenantId(), command.id(), at); if (claimed == null || claimed.status() != VoucherOperation.Status.QUERYING) throw conflict();
        vouchers.finish(claimed, result, at);
    }
    private ExpensePartialAdjustment adjustment(String tenant, UUID id) {
        var before = adjustments.find(tenant, id).orElseThrow(ExpensePartialPreparationService::conflict); reports.lock(tenant, before.input().basis().reportId());
        return adjustments.find(tenant, id).orElseThrow(ExpensePartialPreparationService::conflict);
    }
    private ExpensePartialAdjustmentPreparation locked(String tenant, UUID id) {
        var before = preparations.find(tenant, id).orElse(null); if (before == null) return null;
        reports.lock(tenant, before.input().adjustment().input().basis().reportId()); return preparations.find(tenant, id).orElseThrow(ExpensePartialPreparationService::conflict);
    }
    private ExpensePartialAdjustmentPreparation currentClaim(ExpensePartialAdjustmentPreparation claimed) {
        var current = locked(claimed.input().adjustment().input().basis().tenantId(), claimed.input().id());
        return current != null && current.equals(claimed) && current.status() == ExpensePartialAdjustmentPreparation.Status.RUNNING ? current : null;
    }
    private static FinanceResult<PaymentObservation> bankResult(FinanceResult<ExpensePaymentReturnPort.Receipt> result) {
        if (result instanceof FinanceResult.Success<ExpensePaymentReturnPort.Receipt> success && success.value().current() != null) return new FinanceResult.Success<>(success.value().current());
        if (result instanceof FinanceResult.Unavailable<?> unavailable) return new FinanceResult.Unavailable<>(unavailable.failure());
        if (result instanceof FinanceResult.Rejected<?> rejected) return new FinanceResult.Rejected<>(rejected.reason());
        return new FinanceResult.Unavailable<>(FinanceResult.Failure.INVALID_RESPONSE);
    }
    private static String failure(FinanceResult<?> result) {
        if (result instanceof FinanceResult.Unavailable<?> unavailable) return unavailable.failure().name();
        if (result instanceof FinanceResult.Rejected<?> rejected) return rejected.reason().name(); return "INVALID_RESPONSE";
    }
    private static Instant time(Instant at) { var now = Instant.now(); return (at.isAfter(now) ? at : now).truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Partial adjustment preparation or its original source changed"); }
    private void save(ExpensePartialAdjustmentPreparation value) {
        preparations.update(value); events.publishEvent(new ExpensePartialAdjustmentChanged.Preparation(value));
    }

}
