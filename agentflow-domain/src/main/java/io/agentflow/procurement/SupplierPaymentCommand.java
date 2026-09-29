package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import io.agentflow.finance.PaymentAccountsPort;
import io.agentflow.finance.PaymentObservation;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 出纳对已预留原应付登记的单次银行指令；金额与收款账户始终来自原财务授权。
 * @author owlzhangfq@gmail.com
 */
public record SupplierPaymentCommand(SupplierPayableHoldCommand holdCommand, SupplierPayableHoldObservation held,
        String cashier, PaymentAccountsPort.DebitAccount debitAccount, Instant registeredAt) {
    /** 原预留、出纳和出款账户在登记时固定，后续查询或重试不能改写。 */
    public SupplierPaymentCommand {
        if (holdCommand == null || held == null || debitAccount == null || registeredAt == null
                || held.status() != SupplierPayableHoldObservation.Status.HELD || !held.matches(holdCommand, true, registeredAt)
                || !held.observedAt().plus(ProcurementPayablePort.MAX_EVIDENCE_AGE).isAfter(registeredAt)
                || !debitAccount.currency().equals(holdCommand.authorization().source().amount().currency())) throw invalid();
        holdCommand.authorization().requireCashier(cashier, registeredAt);
    }

    /** 实际无争议预留与刚查询的同一预留同时成立，才接受当前目录中的出纳选择。 */
    public static SupplierPaymentCommand register(SupplierPayableHoldOperation original, SupplierPayableHoldObservation verified,
            PaymentAccountsPort.Directory directory, String debitReference, String cashier, Instant now) {
        if (original == null || original.status() != SupplierPayableHoldOperation.Status.HELD || now == null
                || now.isBefore(original.updatedAt()) || !sameHold(original.observation(), verified)
                || verified.observedAt().isBefore(original.observation().observedAt())) throw invalid();
        var authorization = original.command().authorization();
        var request = new PaymentAccountsPort.Request(authorization.payable().request().legalEntityId(), authorization.source().amount().currency(), cashier);
        if (directory == null || !directory.matches(request, now)
                || !directory.observedAt().plus(ProcurementPayablePort.MAX_EVIDENCE_AGE).isAfter(now)) throw invalid();
        return new SupplierPaymentCommand(original.command(), verified, cashier, directory.account(debitReference, now), now);
    }

    public UUID id() { return holdCommand.id(); }
    public String tenantId() { return holdCommand.tenantId(); }
    public String targetDigest() { return holdCommand.targetDigest(); }
    public Money amount() { return holdCommand.authorization().source().amount(); }
    public SupplierAccountSnapshot payee() { return holdCommand.authorization().payable().account(); }

    /** 原预留摘要绑定完整采购批准；新增摘要继续固定原预留凭据、出纳、出款账户及登记时点。 */
    public String digest() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            add(digest, "agentflow-supplier-payment-command-1", holdCommand.digest(), held.revision(), held.observedAt(), held.holdReference(),
                    held.ledgerVersion(), held.heldAt(), cashier, debitAccount.reference(), debitAccount.displayName(), debitAccount.maskedAccount(),
                    debitAccount.currency(), debitAccount.sourceVersion(), registeredAt);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    /** 仅限制新的银行发送，原交易查询和后续结算不依赖授权仍有效。 */
    public void requireSendAt(Instant now) {
        if (now == null || now.isBefore(registeredAt)) throw invalid();
        holdCommand.authorization().requireExecutionAt(now);
    }

    /** 银行必须返回本指令的精确金额和原供应商账户；登记前到账、部分到账及写入查无均不成立。 */
    public boolean matches(PaymentObservation value, boolean queried, Instant now) {
        if (value == null || now == null || !value.authorizationId().equals(id()) || !value.commandDigest().equals(digest())
                || value.observedAt().isBefore(registeredAt) || value.observedAt().isAfter(now)
                || value.status() == PaymentObservation.Status.NOT_FOUND && !queried) return false;
        return value.status() != PaymentObservation.Status.SUCCEEDED && value.status() != PaymentObservation.Status.REVERSED
                || value.paidAmount().equals(amount()) && value.accountDigest().equals(payee().accountDigest()) && !value.completedAt().isBefore(registeredAt);
    }

    /** 原预留允许同一事实的新版本与新观察时间，不允许换预留号、账本、金额或建立时点。 */
    public boolean matchesHold(SupplierPayableHoldObservation current, Instant now) {
        return sameHold(held, current) && current.matches(holdCommand, true, now) && !current.observedAt().isBefore(held.observedAt())
                && current.observedAt().plus(ProcurementPayablePort.MAX_EVIDENCE_AGE).isAfter(now);
    }

    private static boolean sameHold(SupplierPayableHoldObservation original, SupplierPayableHoldObservation current) {
        return original != null && current != null && current.status() == SupplierPayableHoldObservation.Status.HELD
                && original.status() == SupplierPayableHoldObservation.Status.HELD && current.authorizationId().equals(original.authorizationId())
                && current.commandDigest().equals(original.commandDigest()) && current.revision() >= original.revision()
                && current.holdReference().equals(original.holdReference()) && current.ledgerVersion().equals(original.ledgerVersion())
                && current.heldAmount().equals(original.heldAmount()) && current.accountDigest().equals(original.accountDigest()) && current.heldAt().equals(original.heldAt());
    }

    private static void add(MessageDigest digest, Object... values) {
        for (Object value : values) {
            byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array()); digest.update(bytes);
        }
    }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_PAYMENT_COMMAND", "Supplier payment requires a fresh confirmed original hold and an independent cashier account selection"); }

    /** 日志不展开供应商、出纳、金额或账户。 */
    @Override public String toString() { return "SupplierPaymentCommand[id=" + id() + "]"; }
}
