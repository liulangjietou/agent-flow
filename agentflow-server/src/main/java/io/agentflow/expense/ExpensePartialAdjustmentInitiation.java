package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 独立财务先刷新原件，再从真实前次完成和已登记整笔回款建立新的部分调整意图。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePartialAdjustmentInitiation {
    private final CurrentActor actors;
    private final ExpenseResourceAdjustmentAccess access;
    private final ExpenseReportRepository reports;
    private final JdbcExpenseSettlementRepository settlements;
    private final JdbcExpensePartialAdjustmentRepository adjustments;
    private final ExpensePartialAdjustmentSources sources;
    private final JdbcExpensePaymentReturnsRepository returns;
    private final JdbcPaymentOperationRepository payments;
    private final JdbcVoucherOperationRepository vouchers;
    private final PaymentOperationService paymentQueries;
    private final VoucherOperationService voucherQueries;
    private final ExpensePartialAdjustmentGuard guard;
    private final ExpensePartialAdjustmentAudit audit;

    /** 原财务查询复用既有持久队列，新意图和旧整单调整共用锁与互斥。 */
    public ExpensePartialAdjustmentInitiation(CurrentActor actors, ExpenseResourceAdjustmentAccess access, ExpenseReportRepository reports,
            JdbcExpenseSettlementRepository settlements, JdbcExpensePartialAdjustmentRepository adjustments, ExpensePartialAdjustmentSources sources,
            JdbcExpensePaymentReturnsRepository returns, JdbcPaymentOperationRepository payments, JdbcVoucherOperationRepository vouchers,
            PaymentOperationService paymentQueries, VoucherOperationService voucherQueries, ExpensePartialAdjustmentGuard guard, ExpensePartialAdjustmentAudit audit) {
        this.actors = actors; this.access = access; this.reports = reports; this.settlements = settlements; this.adjustments = adjustments; this.sources = sources;
        this.returns = returns; this.payments = payments; this.vouchers = vouchers; this.paymentQueries = paymentQueries; this.voucherQueries = voucherQueries; this.guard = guard; this.audit = audit;
    }

    /** 只安排原号查询，不重发任何付款或凭证；有活动部分调整时必须采用其独立准备。 */
    @Transactional
    public ExpensePartialAdjustmentAudit.Receipt refresh(UUID id, RefreshInput input) {
        var report = locked(id, input.roundNo(), input.applicationVersion(), input.businessVersion());
        var settlement = settlement(report, input.settlementVersion()); requireNoActive(report);
        var tenant = report.tenantId(); var accrual = vouchers.find(tenant, settlement.input().voucherOperationId()).orElseThrow(ExpensePartialAdjustmentInitiation::conflict);
        var bank = settlement.input().payment() == null ? null : payments.find(tenant, settlement.input().payment().operationId()).orElseThrow(ExpensePartialAdjustmentInitiation::conflict);
        var paymentVoucher = bank == null ? null : vouchers.forRound(tenant, report.applicationId(), input.roundNo(), VoucherCommand.Kind.PAYMENT).orElseThrow(ExpensePartialAdjustmentInitiation::conflict);
        if (accrual.version() != input.accrualVersion() || (bank == null ? 0 : bank.version()) != input.paymentVersion()
                || (paymentVoucher == null ? 0 : paymentVoucher.version()) != input.paymentVoucherVersion()) throw conflict();
        var now = now();
        if (bank != null) paymentQueries.query(tenant, bank.input().command().id(), bank.version(), now);
        voucherQueries.query(tenant, accrual.input().command().id(), accrual.version(), now);
        if (paymentVoucher != null) voucherQueries.query(tenant, paymentVoucher.input().command().id(), paymentVoucher.version(), now);
        var event = audit.record(report, id, report.version(), ExpensePartialAdjustmentAudit.Action.ORIGINAL_QUERY, input.comment(), now);
        return new ExpensePartialAdjustmentAudit.Receipt(id, input.roundNo(), null, null, null, null, event);
    }

    /** 页面仅指定剩余额和整笔入款身份；前次成功、已用回款及全部原命令由服务端恢复。 */
    @Transactional
    public ExpensePartialAdjustmentAudit.Receipt create(UUID id, CreateInput input) {
        var report = locked(id, input.roundNo(), input.applicationVersion(), input.businessVersion()); settlement(report, input.settlementVersion()); requireNoActive(report);
        var tenant = report.tenantId(); var previous = adjustments.latestCompleted(tenant, id).orElse(null);
        if (!Objects.equals(input.previousId(), previous == null ? null : previous.id()) || input.previousVersion() != (previous == null ? 0 : previous.version())) throw conflict();
        var ledger = returns.find(tenant, id).orElse(null); if (input.returnsVersion() != (ledger == null ? 0 : ledger.version())) throw conflict();
        var used = previous == null ? List.<ExpensePaymentReturns.Entry>of() : previous.input().basis().usedReturns();
        var selectedIds = Set.copyOf(input.returnIds());
        var selected = ledger == null ? List.<ExpensePaymentReturns.Entry>of() : ledger.entries().stream().filter(value -> selectedIds.contains(value.proof().fundsIdentity())).toList();
        if (selectedIds.size() != input.returnIds().size() || selected.size() != selectedIds.size() || selected.stream().anyMatch(used::contains)) {
            throw new DomainException("INVALID_PARTIAL_ADJUSTMENT_RETURNS", "Select distinct whole registered returns not already used by a completed adjustment");
        }
        var before = previous == null ? ExpenseAdjustmentAmounts.from(report) : previous.input().basis().funding().financial().change().after();
        var currency = before.gross().currency();
        var change = before.reduce(input.lines().stream().map(line -> new ExpenseReport.Reduction(line.lineNo(), new Money(line.remainingGross(), currency), new Money(line.remainingTax(), currency))).toList());
        var basis = ExpensePartialAdjustmentBasis.from(sources.find(tenant, change, used, selected), previous); var now = now();
        var value = ExpensePartialAdjustment.begin(new ExpensePartialAdjustment.Input(UUID.randomUUID(), basis, actors.actor().userId(), input.evidenceReference(), input.reason(), now));
        adjustments.create(value); var event = audit.record(report, value.id(), value.version(), ExpensePartialAdjustmentAudit.Action.CREATE, input.reason(), now);
        return new ExpensePartialAdjustmentAudit.Receipt(id, input.roundNo(), value.id(), value.version(), null, null, event);
    }
    private ExpenseReport locked(UUID report, int round, long applicationVersion, long businessVersion) {
        var context = access.locked(report, round); access.requireVersions(context, round, applicationVersion, businessVersion);
        return reports.find(actors.actor().tenantId(), report).orElseThrow(ExpensePartialAdjustmentInitiation::conflict);
    }
    private ExpenseSettlement settlement(ExpenseReport report, long version) {
        var value = settlements.find(report.tenantId(), report.id()).orElseThrow(ExpensePartialAdjustmentInitiation::conflict);
        if (!value.resourcesConsumed() || value.version() != version) throw conflict(); value.requireReport(report); return value;
    }
    private void requireNoActive(ExpenseReport report) {
        guard.requirePartialAllowed(report.tenantId(), report.id());
        if (adjustments.active(report.tenantId(), report.id()).isPresent()) throw new DomainException("EXPENSE_PARTIAL_ADJUSTMENT_PENDING", "Use the active adjustment preparation instead of refreshing its originals or creating another intent");
    }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Displayed original finance or completed partial adjustment changed"); }

    /**
     * 三种原件的显示修订分别校验，零应付的两个付款版本固定为零。
     * @author owlzhangfq@gmail.com
     */
    public record RefreshInput(@Positive int roundNo, @Positive long applicationVersion, @Positive long businessVersion, @Positive long settlementVersion,
            @Positive long accrualVersion, @NotNull @Min(0) Long paymentVersion, @NotNull @Min(0) Long paymentVoucherVersion,
            @NotBlank @Size(max = 2000) @Pattern(regexp = "[^\\p{Cntrl}]+") String comment) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown partial original query field"); }
    }
    /**
     * 原报销、前次净额和退回账本的版本均来自已展示事实，金额只接受精确十进制文本。
     * @author owlzhangfq@gmail.com
     */
    public record CreateInput(@Positive int roundNo, @Positive long applicationVersion, @Positive long businessVersion, @Positive long settlementVersion,
            UUID previousId, @NotNull @Min(0) Long previousVersion, @NotNull @Min(0) Long returnsVersion,
            @NotEmpty @Size(max = ExpenseContent.MAX_LINES) List<@NotNull @Valid LineInput> lines,
            @NotNull @Size(max = ExpensePaymentReturnPort.MAX_RETURN_ENTRIES) List<@NotBlank @Size(max = 128) @Pattern(regexp = "[^\\p{Cntrl}]+") String> returnIds,
            @NotBlank @Size(max = 128) @Pattern(regexp = "[^\\p{Cntrl}]+") String evidenceReference,
            @NotBlank @Size(max = 2000) @Pattern(regexp = "[^\\p{Cntrl}]+") String reason) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown partial adjustment creation field"); }
    }
    /**
     * 只调整原行的剩余含税额和税额，不允许夹带票据、账户或新成本位置。
     * @author owlzhangfq@gmail.com
     */
    public record LineInput(@Positive int lineNo, @NotNull @JsonDeserialize(using = FinanceJsonConfiguration.DecimalAmountDeserializer.class) BigDecimal remainingGross,
            @NotNull @JsonDeserialize(using = FinanceJsonConfiguration.DecimalAmountDeserializer.class) BigDecimal remainingTax) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown partial adjustment amount field"); }
    }
}
