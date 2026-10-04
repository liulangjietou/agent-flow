package io.agentflow.finance;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 出纳筛选使用已固定付款账户的内部标识，版本及显示名称变化不拆分同一账户。
 * @author owlzhangfq@gmail.com
 */
final class CashierPaymentAccountKey {
    private CashierPaymentAccountKey() { }

    /** 未完成账户复查的选择没有实际付款命令，不伪造已固定账户。 */
    static String of(PaymentAuthorization authorization) {
        if (authorization.execution() == null) return null;
        var terms = authorization.terms();
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : new String[] {"agentflow-cashier-debit-account-1", terms.tenantId(), terms.targetDigest(),
                    terms.payee().legalEntityId().toString(), terms.amount().currency(), authorization.execution().debitAccount().reference()}) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 is unavailable", unavailable); }
    }
}
