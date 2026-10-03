package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.AccountingPeriodPort;
import io.agentflow.finance.PaymentObservation;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 财务依据原成功银行交易登记独立 ERP 应付结算，不重新挂账或消费采购预算。
 * @author owlzhangfq@gmail.com
 */
public record SupplierPayableSettlementCommand(UUID id, SupplierPaymentCommand payment, long paymentVersion, PaymentObservation paid,
        AccountingPeriodPort.OpenPeriod period, String financeActor, Instant registeredAt) {
    /** 原成功回单、指定记账日期和独立财务固定到命令；付款授权到期不撤销已发生的资金事实。 */
    public SupplierPayableSettlementCommand {
        if (id == null || payment == null || paymentVersion < 1 || paid == null || period == null || registeredAt == null
                || paid.status() != PaymentObservation.Status.SUCCEEDED || !payment.matches(paid, true, registeredAt)
                || !paid.observedAt().plus(ProcurementPayablePort.MAX_EVIDENCE_AGE).isAfter(registeredAt)
                || !period.matches(new AccountingPeriodPort.Request(payment.payee().legalEntityId(), payment.amount().currency(), period.request().accountingDate()), registeredAt)
                || !period.observedAt().plus(ProcurementPayablePort.MAX_EVIDENCE_AGE).isAfter(registeredAt)
                || period.request().accountingDate().isBefore(paid.completedAt().atZone(ZoneId.of(payment.holdCommand().authorization().source().reservation().source().round().legalEntity().timeZone())).toLocalDate())
                || StringUtils.isBlank(financeActor) || financeActor.length() > 128 || !financeActor.equals(financeActor.trim()) || financeActor.chars().anyMatch(Character::isISOControl)
                || financeActor.equals(payment.cashier()) || financeActor.equals(payment.holdCommand().authorization().source().reservation().source().employeeId())) throw invalid();
    }

    /** 只能从实际无争议银行成功修订登记，新的原号查询必须保留同一笔金额、账户和回单。 */
    public static SupplierPayableSettlementCommand register(UUID id, SupplierPaymentOperation original, PaymentObservation verified,
            AccountingPeriodPort.OpenPeriod period, String financeActor, Instant now) {
        if (original == null || !original.settleable() || now == null || now.isBefore(original.updatedAt())
                || !samePaid(original.observation(), verified)) throw invalid();
        return new SupplierPayableSettlementCommand(id, original.command(), original.version(), verified, period, financeActor, now);
    }

    public String tenantId() { return payment.tenantId(); }
    public String targetDigest() { return payment.targetDigest(); }

    /** 每次结算尝试有独立固定编号，摘要同时绑定原银行付款、回单、财务和指定期间。 */
    public String digest() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            add(digest, "agentflow-supplier-payable-settlement-command-1", id, payment.digest(), paymentVersion, paid.revision(), paid.observedAt(),
                    paid.paymentReference(), paid.paidAmount().value().toPlainString(), paid.paidAmount().currency(), paid.accountDigest(), paid.completedAt(), paid.receiptReference(),
                    period.request().legalEntityId(), period.request().currency(), period.request().accountingDate(), period.periodReference(), period.sourceVersion(),
                    period.startsOn(), period.endsOn(), period.observedAt(), period.validUntil(), financeActor, registeredAt);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    /** 历史持久关联检查原银行修订，之后的查询可以追加观察但不能改写该来源。 */
    public boolean registeredFrom(SupplierPaymentOperation original) {
        return original != null && original.version() == paymentVersion && original.settleable() && original.command().equals(payment)
                && !registeredAt.isBefore(original.updatedAt()) && samePaid(original.observation(), paid);
    }

    /** 发送时重新核对当前本地银行事实，不能越过期间新发现的退回或争议。 */
    public boolean matchesCurrentPayment(SupplierPaymentOperation current, PaymentObservation verified, Instant now) {
        return current != null && current.settleable() && current.command().equals(payment) && now != null && !now.isBefore(current.updatedAt())
                && samePaid(current.observation(), verified) && matchesPaid(verified, now);
    }

    /** 银行查询须是同一成功回单的新鲜观察，变号、退回或修订倒退均不能继续核销。 */
    public boolean matchesPaid(PaymentObservation verified, Instant now) {
        return now != null && samePaid(paid, verified) && payment.matches(verified, true, now)
                && verified.observedAt().plus(ProcurementPayablePort.MAX_EVIDENCE_AGE).isAfter(now);
    }

    /** 结算必须精确消耗原预留，已结余额增量等于原付款，并保留对应银行回单和会计期间。 */
    public boolean matches(SupplierPayableSettlementObservation value, boolean queried, Instant now) {
        if (value == null || now == null || !value.operationId().equals(id()) || !value.commandDigest().equals(digest())
                || value.observedAt().isBefore(registeredAt) || value.observedAt().isAfter(now)
                || value.status() == SupplierPayableSettlementObservation.Status.NOT_FOUND && !queried) return false;
        if (value.status() != SupplierPayableSettlementObservation.Status.SETTLED) return true;
        var posting = value.posting();
        return posting.holdReference().equals(payment.held().holdReference()) && posting.settledAmount().equals(payment.amount())
                && posting.settledAfter().compareTo(payment.holdCommand().authorization().payable().gross()) <= 0
                && posting.bankPaymentReference().equals(paid.paymentReference()) && posting.bankReceiptReference().equals(paid.receiptReference())
                && posting.periodReference().equals(period.periodReference()) && posting.accountingDate().equals(period.request().accountingDate())
                && !posting.settledAt().isBefore(registeredAt);
    }

    private static boolean samePaid(PaymentObservation original, PaymentObservation current) {
        return original != null && current != null && original.status() == PaymentObservation.Status.SUCCEEDED && current.status() == original.status()
                && current.authorizationId().equals(original.authorizationId()) && current.commandDigest().equals(original.commandDigest())
                && current.revision() >= original.revision() && !current.observedAt().isBefore(original.observedAt())
                && current.paymentReference().equals(original.paymentReference()) && current.paidAmount().equals(original.paidAmount())
                && current.accountDigest().equals(original.accountDigest()) && current.completedAt().equals(original.completedAt()) && current.receiptReference().equals(original.receiptReference());
    }
    private static void add(MessageDigest digest, Object... values) {
        for (Object value : values) {
            byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array()); digest.update(bytes);
        }
    }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_PAYABLE_SETTLEMENT_COMMAND", "Original paid bank evidence, an open accounting date and an independent finance actor are required"); }
    /** 日志不展开原采购资料和银行回单。 */
    @Override public String toString() { return "SupplierPayableSettlementCommand[id=" + id() + "]"; }
}
