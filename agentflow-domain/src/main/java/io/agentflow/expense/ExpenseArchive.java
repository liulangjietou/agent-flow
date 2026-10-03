package io.agentflow.expense;

import io.agentflow.approval.history.HistoryEvent;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.common.DomainException;
import io.agentflow.finance.*;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 已完成报销的不可变档案；后续争议属于新事实，不能覆盖封存时的原依据。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseArchive(int formatVersion, Instant archivedAt, Manifest manifest) {
    public static final int FORMAT_VERSION = 1;
    /** 归档时刻不能先于最后确认的结算、审批或凭证。 */
    public ExpenseArchive {
        if (formatVersion != FORMAT_VERSION || archivedAt == null || manifest == null
                || archivedAt.isBefore(manifest.settlement().updatedAt()) || archivedAt.isBefore(manifest.approval().completedAt())
                || archivedAt.isBefore(manifest.budget().appliedAt())
                || manifest.vouchers().stream().anyMatch(value -> archivedAt.isBefore(value.observation().observedAt()))) throw invalid();
    }
    /** 文件已经逐个核对后，应用服务在锁内复核完整清单并封存。 */
    public static ExpenseArchive seal(Manifest manifest, Instant at) { return new ExpenseArchive(FORMAT_VERSION, at, manifest); }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_ARCHIVE", "Archive must contain complete and matching original expense evidence"); }

    /**
     * 只保存业务证据，不复制外部凭据、网关配置或原始付款命令。
     * @author owlzhangfq@gmail.com
     */
    public record Manifest(String businessNo, ExpenseRound expense, SubmissionRound approval, List<HistoryEvent> history,
                           ExpenseSubmissionControl control, ExpenseSettlement settlement, BudgetObservation budget,
                           List<Voucher> vouchers, List<Original> originals) {
        /** 全额核减和全额冲销保留真实路径，不要求不存在的零额凭证或付款。 */
        public Manifest {
            if (org.apache.commons.lang3.StringUtils.isBlank(businessNo) || expense == null || approval == null || control == null
                    || settlement == null || budget == null || history == null || vouchers == null || originals == null) throw invalid();
            history = List.copyOf(history); vouchers = List.copyOf(vouchers); originals = List.copyOf(originals);
            var input = settlement.input(); var source = input.source(); var submitted = control.input();
            if (settlement.status() != ExpenseSettlement.Status.SETTLED || !settlement.resourcesConsumed()
                    || source.kind() != VoucherCommand.Kind.EXPENSE_ACCRUAL
                    || !source.tenantId().equals(approval.tenantId()) || !source.applicationId().equals(approval.applicationId())
                    || source.roundNo() != approval.roundNo() || source.roundNo() != expense.roundNo()
                    || approval.status() != SubmissionRound.Status.APPROVED || approval.completedAt() == null
                    || !source.tenantId().equals(submitted.tenantId()) || !source.businessId().equals(submitted.reportId())
                    || !source.applicationId().equals(submitted.applicationId()) || !source.employeeId().equals(submitted.employeeId())
                    || source.roundNo() != submitted.roundNo() || submitted.submittedFinancialVersion() != expense.submittedFinancialVersion() + 1
                    || !control.paperReady() || !input.gross().equals(expense.approvedGross()) || !input.offsets().equals(expense.offsetTotal())
                    || budget.status() != BudgetObservation.Status.APPLIED || !budget.operationId().equals(settlement.budgetOperationId())) throw invalid();
            var expectedKinds = input.payment() != null ? Set.of(VoucherCommand.Kind.EXPENSE_ACCRUAL, VoucherCommand.Kind.PAYMENT)
                    : input.voucherOperationId() != null ? Set.of(VoucherCommand.Kind.EXPENSE_ACCRUAL) : Set.<VoucherCommand.Kind>of();
            if (vouchers.size() != expectedKinds.size() || !vouchers.stream().map(Voucher::kind).collect(Collectors.toSet()).equals(expectedKinds)) throw invalid();
            for (var voucher : vouchers) {
                var observed = voucher.observation();
                if (voucher.kind() == VoucherCommand.Kind.EXPENSE_ACCRUAL
                        ? !observed.operationId().equals(input.voucherOperationId()) || !observed.commandDigest().equals(input.voucherDigest()) || !observed.debitTotal().equals(input.gross())
                        : !observed.debitTotal().equals(input.payable())) throw invalid();
            }
            var invoiceIds = expense.originalLines().stream().flatMap(line -> line.original().invoiceIds().stream()).collect(Collectors.toSet());
            if (originals.size() != invoiceIds.size() || !originals.stream().map(value -> value.file().invoiceId()).collect(Collectors.toSet()).equals(invoiceIds)
                    || originals.stream().anyMatch(value -> !value.file().tenantId().equals(source.tenantId()) || !value.file().ownerId().equals(source.employeeId())
                        || !value.receipt().facts().legalEntityId().equals(expense.content().legalEntityId()))) throw invalid();
        }
        public VoucherPreparation.Source source() { return settlement.input().source(); }
    }
    /**
     * 原会计操作本地修订和真实 ERP 凭据，省略账户及外部执行配置。
     * @author owlzhangfq@gmail.com
     */
    public record Voucher(VoucherCommand.Kind kind, long version, VoucherObservation observation) {
        /** 只有无争议的实际过账可以成为初始归档依据。 */
        public Voucher { if (kind == null || version < 1 || observation == null || observation.status() != VoucherObservation.Status.POSTED) throw invalid(); }
    }
    /**
     * 原件沿用正式提交采用的查验凭据，后续重验或其他报销不能替换它。
     * @author owlzhangfq@gmail.com
     */
    public record Original(InvoiceOriginal file, ExpensePrecheckEvidence.InvoiceReceipt receipt) {
        /** 字节摘要、原件编号和发票必须属于同一项冻结证据。 */
        public Original {
            if (file == null || receipt == null || file.status() != InvoiceOriginal.Status.READY
                    || !file.id().equals(receipt.originalId()) || !file.invoiceId().equals(receipt.invoiceId())
                    || !file.sha256().equals(receipt.facts().originalDigest())) throw invalid();
        }
        /** ZIP 条目只由固定 UUID 和格式生成，上传文件名不参与路径。 */
        public String entryName() { return "originals/" + file.id() + "." + file.format().name().toLowerCase(java.util.Locale.ROOT); }
    }
}
