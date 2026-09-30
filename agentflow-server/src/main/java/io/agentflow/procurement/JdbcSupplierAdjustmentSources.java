package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 调整只能引用实际回款修订、实际原核销和已完成前次调整；历史恢复与当前办理分别校验。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSupplierAdjustmentSources {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final ProcurementPaymentRepository requests;
    private final JdbcSupplierPaymentReturnsRepository returns;
    private final JdbcProcurementPayableReservationRepository reservations;
    private final JdbcSupplierPaymentOperationRepository payments;
    private final JdbcSupplierAdjustmentCompletions completions;
    private final JdbcSupplierPaymentReturnCheckRepository checks;

    /** 历史原件从各自的持久修订恢复，输入中的合法领域对象不能代替实际业务记录。 */
    public JdbcSupplierAdjustmentSources(JdbcTemplate jdbc, JsonUtil json, ProcurementPaymentRepository requests,
            JdbcSupplierPaymentReturnsRepository returns, JdbcProcurementPayableReservationRepository reservations, JdbcSupplierPaymentOperationRepository payments,
            JdbcSupplierAdjustmentCompletions completions, JdbcSupplierPaymentReturnCheckRepository checks) {
        this.jdbc = jdbc; this.json = json; this.requests = requests; this.returns = returns; this.reservations = reservations; this.payments = payments;
        this.completions = completions; this.checks = checks;
    }

    /** 原申请优先、同应付银行其次，和原付款、核销及回款登记保持一致的加锁顺序。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPaymentReturns lock(String tenant, UUID paymentId) {
        var ledger = returns.find(tenant, paymentId).orElseThrow(JdbcSupplierAdjustmentSources::changed);
        requests.lock(tenant, ledger.request().command().holdCommand().authorization().source().reservation().source().requestId());
        return returns.locked(tenant, paymentId);
    }

    /** 财务只选择当前银行和回款版本；原核销首次成功、前次完成及累计资金全部从实际修订派生。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPayableAdjustmentSource current(String tenant, UUID paymentId, long paymentVersion, long returnVersion) {
        var ledger = lock(tenant, paymentId); var bank = payments.find(tenant, paymentId).orElseThrow(JdbcSupplierAdjustmentSources::changed);
        if (bank.version() != paymentVersion || ledger.version() != returnVersion) throw changed();
        return source(ledger, bank);
    }

    /** 页面快照不领取资金锁；写入入口仍须通过 current 锁定并复核精确版本。 */
    public SupplierPayableAdjustmentSource snapshot(String tenant, UUID paymentId) {
        return source(returns.find(tenant, paymentId).orElseThrow(JdbcSupplierAdjustmentSources::changed),
                payments.find(tenant, paymentId).orElseThrow(JdbcSupplierAdjustmentSources::changed));
    }

    private SupplierPayableAdjustmentSource source(SupplierPaymentReturns ledger, SupplierPaymentOperation bank) {
        var tenant = bank.command().tenantId(); var paymentId = bank.command().id();
        var original = activeSettlement(tenant, paymentId);
        var settlement = original == null ? null : firstSettlement(original);
        SupplierPayableAdjustmentSource.Previous previous = null;
        if (ledger.accounting() != null) {
            var completed = completions.find(tenant, ledger.accounting().operationId()).orElseThrow(JdbcSupplierAdjustmentSources::changed).operation();
            previous = new SupplierPayableAdjustmentSource.Previous(paymentId, bank.command().digest(), settlement == null ? null : settlement.command().id(),
                    completed.version(), completed.command().source().returns().entries(), completed.observation());
        }
        var source = new SupplierPayableAdjustmentSource(ledger, settlement, previous); requireFinancialState(source); return source;
    }

    /** 读取结束后保留所有已知银行和当前 ERP 修订，旧的成功快照不能越过更新的本地事实。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireEvidence(SupplierPayableAdjustmentSource source, SupplierPayableAdjustmentEvidence evidence) {
        var payment = source.returns().request().command(); var tenant = payment.tenantId();
        var current = payments.find(tenant, payment.id()).orElseThrow(JdbcSupplierAdjustmentSources::changed);
        if (!evidence.bank().matchesCurrentBank(current)
                || checks.history(tenant, payment.id()).stream().anyMatch(check -> !evidence.bank().continues(check.receipt()))
                || returns.accountingReceipts(source.returns()).stream().anyMatch(prior -> !evidence.bank().continues(prior))) throw changed();
        if (source.settlement() != null) {
            var settled = activeSettlement(tenant, payment.id()); var observed = evidence.settlement();
            if (settled == null || !settled.settled() || observed == null || observed.revision() < settled.observation().revision()
                    || observed.observedAt().isBefore(settled.observation().observedAt()) || !observed.posting().equals(settled.observation().posting())) throw changed();
        }
        if (source.previous() != null) {
            var previous = jdbc.query("SELECT state_json FROM supplier_payable_adjustment_operation WHERE tenant_id=? AND id=?",
                    (row, index) -> json.read(row.getString("state_json"), SupplierPayableAdjustmentOperation.class), tenant, source.previous().observation().operationId().toString());
            var observed = evidence.previous();
            if (previous.size() != 1 || !previous.get(0).adjusted() || observed == null || observed.revision() < previous.get(0).observation().revision()
                    || observed.observedAt().isBefore(previous.get(0).observation().observedAt()) || !observed.posting().equals(previous.get(0).observation().posting())) throw changed();
        }
    }

    /** 已保存准备恢复只核对当时原件，不能因之后追加回款或进入查询而改写旧输入。 */
    public void requireRecorded(SupplierPayableAdjustmentSource source) {
        var ledger = source.returns(); var bank = ledger.request().command(); var tenant = bank.tenantId();
        if (!ledger.equals(returns.revision(tenant, bank.id(), ledger.version()))) throw changed();
        if (source.settlement() != null) {
            var original = source.settlement();
            var saved = settlementRevision(tenant, original.command().id(), original.version());
            if (!saved.settled() || !saved.command().equals(original.command()) || !saved.observation().equals(original.observation())) throw changed();
        }
        if (source.previous() != null) {
            var previous = source.previous();
            var saved = jdbc.query("""
                    SELECT o.state_json FROM supplier_adjustment_completion c
                    JOIN supplier_payable_adjustment_revision o ON o.tenant_id=c.tenant_id AND o.operation_id=c.operation_id AND o.version=c.operation_version
                    WHERE c.tenant_id=? AND c.operation_id=? AND c.operation_version=? AND c.payment_id=?
                    """, (row, index) -> json.read(row.getString("state_json"), SupplierPayableAdjustmentOperation.class),
                    tenant, previous.observation().operationId().toString(), previous.version(), bank.id().toString());
            if (saved.size() != 1) throw changed();
            var operation = saved.get(0); var old = operation.command().source();
            if (!operation.adjusted() || operation.version() != previous.version() || !operation.observation().equals(previous.observation())
                    || !operation.command().tenantId().equals(tenant) || !operation.command().id().equals(previous.observation().operationId())
                    || !old.returns().request().equals(ledger.request()) || !old.returns().entries().equals(previous.entries())
                    || !java.util.Objects.equals(old.settlement(), source.settlement())) throw changed();
        }
    }

    /** 新准备和正式发送要求当前账本不变，所有既有记账无争议，原核销已停止新发送。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireCurrent(SupplierPayableAdjustmentSource source) {
        var bank = source.returns().request().command(); var tenant = bank.tenantId();
        if (!lock(tenant, bank.id()).equals(source.returns())) throw changed();
        requireFinancialState(source);
    }

    /** ERP 已成功时允许之后登记的新入款保留待办，原核销、前次调整和银行状态仍须可确认。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPaymentReturns currentForCompletion(SupplierPayableAdjustmentSource source) {
        var bank = source.returns().request().command();
        var current = lock(bank.tenantId(), bank.id()); requireFinancialState(source); return current;
    }

    private void requireFinancialState(SupplierPayableAdjustmentSource source) {
        var bank = source.returns().request().command(); var tenant = bank.tenantId();
        requireRecorded(source);
        var currentBank = payments.find(tenant, bank.id()).orElseThrow(JdbcSupplierAdjustmentSources::changed);
        if (!currentBank.command().equals(bank) || currentBank.conflictingObservation() != null
                || !currentBank.settleable() && currentBank.status() != SupplierPaymentOperation.Status.REVERSED) throw changed();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM supplier_settlement_preparation WHERE tenant_id=? AND active_payment_id=?", Integer.class, tenant, bank.id().toString()) != 0) throw changed();
        var activeSettlement = activeSettlement(tenant, bank.id());
        if (source.settlement() == null ? activeSettlement != null : activeSettlement == null) throw changed();
        if (source.settlement() != null) {
            var current = activeSettlement; var original = source.settlement();
            if (!current.settled() || !current.command().equals(original.command()) || current.version() < original.version()
                    || current.observation().revision() < original.observation().revision()
                    || !current.observation().posting().equals(original.observation().posting())) throw changed();
        }
        // 已完成旧调整进入未知或争议时，新调整不能越过它继续记账。
        if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM supplier_payable_adjustment_operation WHERE tenant_id=? AND payment_id=?
                AND completed_version IS NOT NULL AND status<>'ADJUSTED'
                """, Integer.class, tenant, bank.id().toString()) != 0) throw changed();
        var original = bank.holdCommand().authorization().source().reservation();
        var current = reservations.find(tenant, original.id()).orElseThrow(JdbcSupplierAdjustmentSources::changed);
        if (!current.source().equals(original.source()) || current.release() != null
                || source.recognizesOriginalPayment() && !current.equals(original)
                || source.previous() != null && current.held()
                || current.settlement() != null && (source.settlement() == null
                    || !current.settlement().operationId().equals(source.settlement().command().id()))) throw changed();
    }

    private SupplierPayableSettlementOperation settlementRevision(String tenant, UUID id, long version) {
        List<SupplierPayableSettlementOperation> saved = jdbc.query("SELECT state_json FROM supplier_payable_settlement_revision WHERE tenant_id=? AND operation_id=? AND version=?",
                (row, index) -> json.read(row.getString("state_json"), SupplierPayableSettlementOperation.class), tenant, id.toString(), version);
        if (saved.size() != 1) throw changed(); var value = saved.get(0);
        if (!value.command().tenantId().equals(tenant) || !value.command().id().equals(id) || value.version() != version) throw changed();
        return value;
    }
    private SupplierPayableSettlementOperation activeSettlement(String tenant, UUID paymentId) {
        var values = jdbc.query("SELECT state_json FROM supplier_payable_settlement_operation WHERE tenant_id=? AND active_payment_id=?",
                (row, index) -> json.read(row.getString("state_json"), SupplierPayableSettlementOperation.class), tenant, paymentId.toString());
        if (values.size() > 1) throw changed(); return values.isEmpty() ? null : values.get(0);
    }
    private SupplierPayableAdjustmentSource.OriginalSettlement firstSettlement(SupplierPayableSettlementOperation current) {
        var command = current.command();
        return jdbc.query("SELECT version,state_json FROM supplier_payable_settlement_revision WHERE tenant_id=? AND operation_id=? ORDER BY version", (row, index) -> {
            var value = json.read(row.getString("state_json"), SupplierPayableSettlementOperation.class);
            if (value.version() != row.getLong("version") || !value.command().equals(command)) throw changed(); return value;
        }, command.tenantId(), command.id().toString()).stream().filter(SupplierPayableSettlementOperation::settled).findFirst()
                .map(value -> new SupplierPayableAdjustmentSource.OriginalSettlement(value.version(), value.command(), value.observation())).orElse(null);
    }
    private static DomainException changed() { return new DomainException("SUPPLIER_ADJUSTMENT_SOURCE_CHANGED", "Registered supplier returns, original settlement or previous adjustment changed"); }
}
