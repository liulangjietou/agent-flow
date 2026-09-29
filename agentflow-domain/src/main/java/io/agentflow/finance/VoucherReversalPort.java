package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 核对 ERP 已实际过账的独立冲销凭证，原凭证的撤销状态本身不能代替反向分录。
 * @author owlzhangfq@gmail.com
 */
public interface VoucherReversalPort {
    Duration MAX_EVIDENCE_AGE = Duration.ofMinutes(5);
    /** 只读取固定原目标，不发送过账、退款或解除占用的命令。 */
    FinanceResult<Receipt> query(String tenantId, String targetDigest, Request request);

    /**
     * 原命令与首次接受的过账事实固定在本地修订中，不能随当前科目映射变化。
     * @author owlzhangfq@gmail.com
     */
    record Request(VoucherCommand command, VoucherObservation original) {
        /** 曾接受的真实原凭证允许已经显示冲销，但必须保留完整的原过账身份。 */
        public Request {
            if (command == null || original == null || original.status() != VoucherObservation.Status.POSTED && original.status() != VoucherObservation.Status.REVERSED
                    || !original.matches(command, true, original.observedAt())) throw invalid();
        }
        @Override public String toString() { return "VoucherReversalRequest[operationId=" + command.id() + "]"; }
    }

    /**
     * 原凭证当前已冲销与另一张真实反向凭证同时成立，才形成可采纳的证据。
     * @author owlzhangfq@gmail.com
     */
    record Receipt(Request request, Status status, long revision, Instant observedAt, Instant validUntil,
                   VoucherObservation current, Posting reversal) {
        /** 五分钟内的证据必须完整保留原金额、科目、业务行和辅助核算，不接受部分或合并冲销。 */
        public Receipt {
            if (request == null || status == null || revision < 1 || observedAt == null || validUntil == null
                    || !validUntil.isAfter(observedAt) || validUntil.isAfter(observedAt.plus(MAX_EVIDENCE_AGE))) throw invalid();
            if (current != null && (!current.matches(request.command(), true, observedAt)
                    || current.observedAt().isBefore(request.original().observedAt()) || validUntil.isAfter(current.observedAt().plus(MAX_EVIDENCE_AGE)))) throw invalid();
            if (status == Status.UNRESOLVED) {
                if (reversal != null) throw invalid();
            } else if (current == null || current.status() != VoucherObservation.Status.REVERSED || current.revision() < request.original().revision()
                    || !samePosting(current, request.original()) || reversal == null || !reversal.matches(request, observedAt)) throw invalid();
        }
        /** 证据只供同一原意图在有效期内明确采用。 */
        public boolean matches(Request expected, Instant now) { return request.equals(expected) && now != null && !now.isBefore(observedAt) && now.isBefore(validUntil); }
        /** 后续读取不能把此前已见的另一张冲销凭证换成新的事实。 */
        public boolean preservesReversal(Receipt previous) {
            return previous != null && request.equals(previous.request) && (previous.reversal == null || Objects.equals(reversal, previous.reversal));
        }
        /** 本地原凭证必须已经独立确认冲销；新的证明不能绕过原凭证争议处理。 */
        public boolean matchesCurrent(VoucherObservation original) {
            return current != null && original != null && original.status() == VoucherObservation.Status.REVERSED
                    && original.revision() <= current.revision() && !original.observedAt().isAfter(current.observedAt()) && samePosting(current, original);
        }
        @Override public String toString() { return "VoucherReversalReceipt[operationId=" + request.command().id() + ", status=" + status + ", revision=" + revision + "]"; }
    }

    /**
     * 独立凭证保存真实过账号、期间、日期及全部反向分录，不能回写原凭证。
     * @author owlzhangfq@gmail.com
     */
    record Posting(String postingReference, String voucherReference, String periodReference, LocalDate accountingDate,
                   Instant postedAt, List<Line> lines) {
        /** 分录按原行号标准化，顺序变化不产生新的会计事实。 */
        public Posting {
            if (invalidText(postingReference) || invalidText(voucherReference) || invalidText(periodReference) || accountingDate == null || postedAt == null
                    || CollectionUtils.isEmpty(lines) || lines.stream().anyMatch(Objects::isNull)) throw invalid();
            if (lines.stream().map(Line::entryReference).distinct().count() != lines.size()
                    || lines.stream().map(Line::originalLineNo).distinct().count() != lines.size()) throw invalid();
            lines = lines.stream().sorted(Comparator.comparingInt(Line::originalLineNo)).toList();
        }
        private boolean matches(Request request, Instant observedAt) {
            var command = request.command(); var original = request.original();
            if (postingReference.equals(original.postingReference()) || voucherReference.equals(original.voucherReference())
                    || accountingDate.isBefore(command.accountingDate()) || postedAt.isBefore(original.postedAt()) || postedAt.isAfter(observedAt)
                    || lines.size() != command.lines().size()) return false;
            for (int index = 0; index < lines.size(); index++) {
                var line = lines.get(index); var source = command.lines().get(index);
                if (line.originalLineNo() != source.lineNo() || !line.accountCode().equals(command.mapping().account(source.account()))
                        || line.side() == source.side() || !line.amount().equals(source.amount()) || line.sourceLineNo() != source.sourceLineNo()
                        || !Objects.equals(line.costCenter(), source.costCenter()) || !Objects.equals(line.projectCode(), source.projectCode())
                        || !Objects.equals(line.advanceId(), source.advanceId())) return false;
            }
            return true;
        }
        @Override public String toString() { return "VoucherReversalPosting[lineCount=" + lines.size() + "]"; }
    }

    /**
     * 每条分录明确指出被反转的原行，金额为正，借贷方向必须相反。
     * @author owlzhangfq@gmail.com
     */
    record Line(String entryReference, int originalLineNo, String accountCode, VoucherCommand.Side side, Money amount,
                int sourceLineNo, String costCenter, String projectCode, UUID advanceId) {
        /** 入口保留原始辅助核算，只有与原命令逐项相等时才能形成有效冲销证明。 */
        public Line {
            if (invalidText(entryReference) || originalLineNo < 1 || invalidText(accountCode) || side == null || amount == null || amount.value().signum() <= 0
                    || sourceLineNo < 0 || costCenter != null && invalidText(costCenter) || projectCode != null && invalidText(projectCode)) throw invalid();
        }
    }

    /**
     * 未核清不携带可登记的反向凭证，已核实仍须独立财务明确采用。
     * @author owlzhangfq@gmail.com
     */
    enum Status { UNRESOLVED, VERIFIED }
    private static boolean samePosting(VoucherObservation left, VoucherObservation right) {
        return left.operationId().equals(right.operationId()) && left.commandDigest().equals(right.commandDigest())
                && Objects.equals(left.postingReference(), right.postingReference()) && Objects.equals(left.voucherReference(), right.voucherReference())
                && Objects.equals(left.periodReference(), right.periodReference()) && Objects.equals(left.accountingDate(), right.accountingDate())
                && Objects.equals(left.debitTotal(), right.debitTotal()) && Objects.equals(left.creditTotal(), right.creditTotal()) && Objects.equals(left.postedAt(), right.postedAt());
    }
    private static boolean invalidText(String value) { return StringUtils.isBlank(value) || value.length() > 128; }
    private static DomainException invalid() { return new DomainException("INVALID_VOUCHER_REVERSAL_RECEIPT", "Voucher reversal requires the original posting and a distinct fully posted reverse voucher with matching line dimensions"); }
}
