package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 付款前的账户依据来自固定资金系统：出纳可用出款账户与申请人当前本人收款账户。
 * @author owlzhangfq@gmail.com
 */
public interface PaymentAccountsPort {
    int MAX_DEBIT_ACCOUNTS = 200;

    /** 出款目录限定真实出纳、法人和币种，不能使用其他操作者的历史目录。 */
    FinanceResult<Directory> debitAccounts(String tenantId, String targetDigest, Request request);

    /** 执行前重新读取本人账户，始终使用原授权保存的财务目标。 */
    FinanceResult<EmployeeAccountPort.Account> currentPayee(String tenantId, String targetDigest, PayeeRequest request);

    /**
     * 资金系统按出纳身份返回可实际使用的账户；本地仍独立复核出纳岗位。
     * @author owlzhangfq@gmail.com
     */
    record Request(UUID legalEntityId, String currency, String cashierId) {
        /** 法人、币种与出纳共同组成授权查询范围。 */
        public Request {
            if (legalEntityId == null || invalidText(cashierId)) throw invalid();
            Money.zero(currency);
        }
    }

    /**
     * 付款前查询必须仍为原申请人和原法人，不能由出纳替换收款人。
     * @author owlzhangfq@gmail.com
     */
    record PayeeRequest(UUID legalEntityId, String employeeId) {
        /** 查询身份由原授权生成，不接收完整银行账号。 */
        public PayeeRequest { if (legalEntityId == null || invalidText(employeeId)) throw invalid(); }
    }

    /**
     * 稳定账户引用由资金系统维护，掩码供人工核对，原引用不得改指另一银行账户。
     * @author owlzhangfq@gmail.com
     */
    record DebitAccount(String reference, String displayName, String maskedAccount, String currency, String sourceVersion) {
        /** 拒绝完整卡号、缺版本或隐式币种，页面不能把手填卡号当作账户引用。 */
        public DebitAccount {
            if (invalidText(reference) || invalidText(displayName) || invalidText(sourceVersion) || invalidText(maskedAccount)
                    || !maskedAccount.matches("[0-9*•xX -]+") || !maskedAccount.matches(".*(?:\\*{2,}|•{2,}|[xX]{2,}).*")
                    || maskedAccount.matches(".*[0-9]{5,}.*") || maskedAccount.chars().filter(Character::isDigit).count() > 8) throw invalid();
            Money.zero(currency);
        }
        /** 日志不打印账户引用和展示信息。 */
        @Override public String toString() { return "DebitAccount[currency=" + currency + "]"; }
    }

    /**
     * 空目录表示当前没有可用账户；不自动选取默认账户，不截断超限或不完整目录。
     * @author owlzhangfq@gmail.com
     */
    record Directory(Request request, String sourceVersion, Instant observedAt, Instant validUntil, List<DebitAccount> accounts) {
        /** 同一范围只允许唯一引用，所有账户必须符合请求币种。 */
        public Directory {
            if (request == null || invalidText(sourceVersion) || observedAt == null || validUntil == null || !validUntil.isAfter(observedAt)
                    || accounts == null || accounts.size() > MAX_DEBIT_ACCOUNTS || accounts.stream().anyMatch(java.util.Objects::isNull)
                    || accounts.stream().map(DebitAccount::reference).distinct().count() != accounts.size()
                    || accounts.stream().anyMatch(account -> !account.currency().equals(request.currency()))) throw invalid();
            accounts = List.copyOf(accounts);
        }
        /** 查询结果仅在原身份范围内且未过期时可用于新的付款准备。 */
        public boolean matches(Request expected, Instant now) {
            return request.equals(expected) && !observedAt.isAfter(now) && validUntil.isAfter(now);
        }
        /** 选中的引用必须在本次有效目录内，不接受其他账户或过期选项。 */
        public DebitAccount account(String reference, Instant now) {
            if (!matches(request, now)) throw new DomainException("PAYMENT_ACCOUNT_EVIDENCE_EXPIRED", "Payment account directory is outside its validity window");
            return accounts.stream().filter(account -> account.reference().equals(reference)).findFirst()
                    .orElseThrow(() -> new DomainException("PAYMENT_DEBIT_ACCOUNT_UNAVAILABLE", "Selected debit account is not available to this cashier"));
        }
        /** 目录日志只保留条数，不输出资金账户或操作者。 */
        @Override public String toString() { return "PaymentAccountDirectory[accounts=" + accounts.size() + "]"; }
    }

    private static boolean invalidText(String value) { return StringUtils.isBlank(value) || value.length() > 128; }
    private static DomainException invalid() { return new DomainException("INVALID_PAYMENT_ACCOUNTS", "Versioned payment accounts must match the original cashier, legal entity and currency"); }
}
