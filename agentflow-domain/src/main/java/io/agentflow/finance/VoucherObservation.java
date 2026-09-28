package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * ERP 对原凭证命令的权威事实，接收请求和实际过账分别记录。
 * @author owlzhangfq@gmail.com
 */
public record VoucherObservation(UUID operationId, String commandDigest, Status status, Long revision, Instant observedAt,
                                 String postingReference, String voucherReference, String periodReference, LocalDate accountingDate,
                                 Money debitTotal, Money creditTotal, Instant postedAt, Failure failure) {
    /** 完成凭证必须有真实凭证号、期间、金额与过账时刻。 */
    public VoucherObservation {
        if (operationId == null || commandDigest == null || !commandDigest.matches("[a-f0-9]{64}") || status == null || revision == null || observedAt == null
                || (status == Status.NOT_FOUND ? revision != 0 || postingReference != null : revision < 1 || invalidText(postingReference))) throw invalid();
        boolean posted = status == Status.POSTED || status == Status.REVERSED;
        if (posted) {
            if (invalidText(voucherReference) || invalidText(periodReference) || accountingDate == null || debitTotal == null || creditTotal == null
                    || debitTotal.value().signum() <= 0 || !debitTotal.equals(creditTotal) || postedAt == null || postedAt.isAfter(observedAt) || failure != null) throw invalid();
        } else if (voucherReference != null || periodReference != null || accountingDate != null || debitTotal != null || creditTotal != null || postedAt != null
                || (status == Status.FAILED) != (failure != null)) throw invalid();
    }

    /** 不接受换日期、换期间、部分过账或另一个命令的成功结果。 */
    public boolean matches(VoucherCommand command, boolean queried, Instant now) {
        return operationId.equals(command.id()) && commandDigest.equals(command.digest()) && !observedAt.isAfter(now) && !observedAt.isBefore(command.createdAt())
                && (status != Status.NOT_FOUND || queried) && (status != Status.POSTED && status != Status.REVERSED
                || command.totals().gross().equals(debitTotal) && command.period().periodReference().equals(periodReference)
                && command.accountingDate().equals(accountingDate) && !postedAt.isBefore(command.createdAt()));
    }
    private static boolean invalidText(String value) { return StringUtils.isBlank(value) || value.length() > 128; }
    private static DomainException invalid() { return new DomainException("INVALID_VOUCHER_OBSERVATION", "Voucher observation must identify the original command and complete posting facts"); }

    /**
     * 已冲销须进入对账，不能当作仍有效过账或静默删除原记录。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { PENDING, POSTED, FAILED, REVERSED, NOT_FOUND }
    /**
     * 真实且绑定原操作的业务失败，网络超时不能构造为其中一种。
     * @author owlzhangfq@gmail.com
     */
    public enum Failure { ACCOUNTING_PERIOD_CLOSED, ACCOUNT_MAPPING_CHANGED, APPROVAL_CHANGED, COST_OBJECT_UNAVAILABLE, VOUCHER_REJECTED }
}
