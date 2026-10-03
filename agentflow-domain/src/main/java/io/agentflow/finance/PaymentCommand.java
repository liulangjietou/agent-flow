package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 一份付款授权对应一条不可改写的付款命令，金额、账户、凭证和执行身份共同参与摘要。
 * @author owlzhangfq@gmail.com
 */
public record PaymentCommand(UUID id, String tenantId, Purpose purpose, Binding binding, Money amount,
                             String debitAccountReference, EmployeeAccountSnapshot payee, String voucherReference,
                             Authorization authorization) {
    /** 仅支付正额，申请人、授权人和执行人必须分离；主数据引用不能由付款页填写账号替代。 */
    public PaymentCommand {
        if (id == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || purpose == null || binding == null
                || amount == null || amount.value().signum() <= 0 || invalidReference(debitAccountReference) || payee == null
                || invalidReference(voucherReference) || authorization == null
                || payee.employeeId().equals(authorization.authorizedBy()) || payee.employeeId().equals(authorization.executedBy())) throw invalid();
    }

    /** 同一授权号不能换金额、账户、审批版本或执行人；外部按相同 UTF-8 长度编码复算摘要。 */
    public String digest() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            add(digest, "agentflow-payment-command-1", id.toString(), tenantId, purpose.name(), binding.businessId().toString(),
                    binding.applicationId().toString(), Integer.toString(binding.roundNo()), Long.toString(binding.applicationVersion()),
                    Long.toString(binding.businessVersion()), amount.value().toPlainString(), amount.currency(), debitAccountReference,
                    payee.legalEntityId().toString(), payee.employeeId(), payee.accountReference(), payee.maskedAccount(),
                    payee.accountDigest(), payee.sourceVersion(), voucherReference, authorization.authorizedBy(), authorization.executedBy(),
                    authorization.authorizedAt().toString(), authorization.expiresAt().toString());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    /** 有效期只限制新发送；过期后的原交易查询仍必须允许。 */
    public void requireSendAt(Instant now) {
        if (now.isBefore(authorization.authorizedAt()) || !now.isBefore(authorization.expiresAt())) {
            throw new DomainException("PAYMENT_AUTHORIZATION_EXPIRED", "Payment authorization is outside its sending window");
        }
    }

    private static void add(MessageDigest digest, String... values) {
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array()); digest.update(bytes);
        }
    }
    private static boolean invalidReference(String value) { return StringUtils.isBlank(value) || value.length() > 128; }
    private static DomainException invalid() { return new DomainException("INVALID_PAYMENT_COMMAND", "Payment command requires immutable approval, account and separated authorization evidence"); }

    /** 日志表示不带账户引用、凭证或人员信息。 */
    @Override public String toString() { return "PaymentCommand[id=" + id + ", purpose=" + purpose + "]"; }

    /**
     * 当前真实业务范围：员工借款放款和审批后费用付款。
     * @author owlzhangfq@gmail.com
     */
    public enum Purpose { EMPLOYEE_ADVANCE, EXPENSE_REIMBURSEMENT }

    /**
     * 同时绑定业务内容版本和批准申请版本，付款不能追随随后修改的内容。
     * @author owlzhangfq@gmail.com
     */
    public record Binding(UUID businessId, UUID applicationId, int roundNo, long applicationVersion, long businessVersion) {
        /** 绑定必须来自已保存的真实申请及业务单据。 */
        public Binding { if (businessId == null || applicationId == null || roundNo < 1 || applicationVersion < 1 || businessVersion < 1) throw invalid(); }
    }

    /**
     * 持久化授权及本次执行身份，重试不能改变 maker-checker 证据。
     * @author owlzhangfq@gmail.com
     */
    public record Authorization(String authorizedBy, String executedBy, Instant authorizedAt, Instant expiresAt) {
        /** 到期时间为排他上限，授权人不能执行自己的付款授权。 */
        public Authorization {
            if (invalidReference(authorizedBy) || invalidReference(executedBy) || authorizedBy.equals(executedBy)
                    || authorizedAt == null || expiresAt == null || !expiresAt.isAfter(authorizedAt)) throw invalid();
        }
    }
}
