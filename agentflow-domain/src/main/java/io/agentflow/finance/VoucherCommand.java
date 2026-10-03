package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 固定原单、科目、会计期间和借贷明细的凭证命令；ERP 保持总账事实源。
 * @author owlzhangfq@gmail.com
 */
public record VoucherCommand(UUID id, String tenantId, Kind kind, Binding binding, UUID legalEntityId, String employeeId,
                             LocalDate accountingDate, Totals totals, AccountingPeriodPort.OpenPeriod period,
                             AccountMappingPort.Mapping mapping, List<Line> lines, PaymentProof payment,
                             Instant createdAt, Instant expiresAt) {
    private static final int MAX_LINES = io.agentflow.expense.ExpenseContent.MAX_LINES * io.agentflow.expense.ExpenseLine.MAX_ALLOCATIONS * 2
            + io.agentflow.expense.ExpenseContent.MAX_ADVANCES + 2;

    /** 领域层同时校验借贷平衡和业务科目用途，平衡但记错方向的凭证也不能推送。 */
    public VoucherCommand {
        if (id == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || kind == null || binding == null
                || legalEntityId == null || invalidText(employeeId) || accountingDate == null || totals == null || period == null || mapping == null
                || createdAt == null || expiresAt == null || !expiresAt.isAfter(createdAt)
                || expiresAt.isAfter(period.validUntil()) || expiresAt.isAfter(mapping.validUntil())
                || CollectionUtils.isEmpty(lines) || lines.size() > MAX_LINES || lines.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
        String currency = totals.gross().currency();
        if (!period.matches(new AccountingPeriodPort.Request(legalEntityId, currency, accountingDate), createdAt)
                || !mapping.request().legalEntityId().equals(legalEntityId) || !mapping.request().currency().equals(currency)
                || !mapping.matches(mapping.request(), createdAt)
                || mapping.request().managedMapping() != null && !tenantId.equals(mapping.request().managedMapping().tenantId())) throw invalid();
        lines = lines.stream().sorted(Comparator.comparingInt(Line::lineNo)).toList();
        var keys = new HashSet<AccountMappingPort.Key>(); var identities = new HashSet<List<Object>>();
        var offsets = new HashSet<UUID>();
        Money debit = Money.zero(currency), credit = Money.zero(currency);
        for (int index = 0; index < lines.size(); index++) {
            var line = lines.get(index); var role = line.account().role();
            if (line.lineNo() != index + 1 || line.side() != expectedSide(kind, role)
                    || !identities.add(List.of(line.account(), line.sourceLineNo(), empty(line.costCenter()), empty(line.projectCode()), empty(line.advanceId())))) throw invalid();
            keys.add(line.account()); mapping.account(line.account());
            if (role == AccountMappingPort.Role.EMPLOYEE_RECEIVABLE) {
                if (line.advanceId() == null || !offsets.add(line.advanceId())
                        || kind == Kind.EMPLOYEE_ADVANCE && !line.advanceId().equals(binding.businessId())) throw invalid();
            }
            if (line.side() == Side.DEBIT) debit = debit.plus(line.amount()); else credit = credit.plus(line.amount());
        }
        if (!keys.equals(Set.copyOf(mapping.request().keys())) || !debit.equals(totals.gross()) || !credit.equals(debit)) throw invalid();
        if (kind == Kind.EXPENSE_ACCRUAL) {
            requireTotal(lines, AccountMappingPort.Role.EXPENSE, totals.gross().minus(totals.tax()));
            requireTotal(lines, AccountMappingPort.Role.DEDUCTIBLE_TAX, totals.tax());
            requireTotal(lines, AccountMappingPort.Role.EMPLOYEE_RECEIVABLE, totals.offset());
            requireTotal(lines, AccountMappingPort.Role.EMPLOYEE_PAYABLE, totals.payable());
        } else {
            if (totals.tax().value().signum() != 0 || totals.offset().value().signum() != 0) throw invalid();
            requireTotal(lines, AccountMappingPort.Role.EMPLOYEE_PAYABLE, totals.gross());
            requireTotal(lines, kind == Kind.EMPLOYEE_ADVANCE ? AccountMappingPort.Role.EMPLOYEE_RECEIVABLE : AccountMappingPort.Role.BANK, totals.gross());
        }
        if (kind == Kind.PAYMENT) {
            if (payment == null || !payment.matches(tenantId, binding, legalEntityId, employeeId, totals.gross(), createdAt)
                    || lines.stream().filter(line -> line.account().role() == AccountMappingPort.Role.BANK)
                    .anyMatch(line -> !line.account().selector().equals(payment.command().debitAccountReference()))) throw invalid();
        } else if (payment != null) throw invalid();
    }

    /** 原编号只对应原内容，字符串按 UTF-8 长度编码，避免分隔符和 JSON 属性顺序歧义。 */
    public String digest() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            add(digest, "agentflow-voucher-command-1", id.toString(), tenantId, kind.name(), binding.businessId().toString(),
                    binding.applicationId().toString(), Integer.toString(binding.roundNo()), Long.toString(binding.applicationVersion()),
                    Long.toString(binding.businessVersion()), legalEntityId.toString(), employeeId, accountingDate.toString(),
                    totals.gross().currency(), totals.gross().value().toPlainString(), totals.tax().value().toPlainString(), totals.offset().value().toPlainString(),
                    period.periodReference(), period.sourceVersion(), period.startsOn().toString(), period.endsOn().toString(),
                    period.observedAt().toString(), period.validUntil().toString(), mapping.sourceVersion(), mapping.observedAt().toString(), mapping.validUntil().toString(),
                    Integer.toString(mapping.entries().size()));
            for (var entry : mapping.entries()) add(digest, entry.key().role().name(), entry.key().selector(), entry.accountCode());
            add(digest, Integer.toString(lines.size()));
            for (var line : lines) add(digest, Integer.toString(line.lineNo()), line.account().role().name(), line.account().selector(), line.side().name(),
                    line.amount().value().toPlainString(), Integer.toString(line.sourceLineNo()), empty(line.costCenter()), empty(line.projectCode()), empty(line.advanceId()));
            add(digest, payment == null ? "0" : "1");
            if (payment != null) {
                var receipt = payment.receipt();
                add(digest, payment.command().digest(), receipt.status().name(), Long.toString(receipt.revision()), receipt.observedAt().toString(),
                        receipt.paymentReference(), receipt.completedAt().toString(), receipt.receiptReference());
            }
            add(digest, createdAt.toString(), expiresAt.toString());
            // 只为新管理映射追加版本证据，旧凭证字节序列和摘要保持原协议。
            var managed = mapping.request().managedMapping();
            if (managed != null) {
                var selection = managed.selection();
                add(digest, "agentflow-managed-account-mapping-1", selection.mappingId().toString(), Long.toString(selection.mappingVersion()),
                        Long.toString(selection.categoryRevision()), Long.toString(selection.activeRevision()), selection.definitionDigest(), selection.targetDigest());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    /** 过期只禁止新推送，原凭证查询仍须允许。 */
    public void requireSendAt(Instant now) {
        if (now.isBefore(createdAt) || !now.isBefore(expiresAt)) throw new DomainException("VOUCHER_EVIDENCE_EXPIRED", "Voucher evidence is outside its sending window");
    }

    /** 凭证摘要日志不携带员工、账户、金额或辅助核算。 */
    @Override public String toString() { return "VoucherCommand[id=" + id + ", kind=" + kind + "]"; }

    private static Side expectedSide(Kind kind, AccountMappingPort.Role role) {
        return switch (kind) {
            case EMPLOYEE_ADVANCE -> switch (role) { case EMPLOYEE_RECEIVABLE -> Side.DEBIT; case EMPLOYEE_PAYABLE -> Side.CREDIT; default -> throw invalid(); };
            case EXPENSE_ACCRUAL -> switch (role) { case EXPENSE, DEDUCTIBLE_TAX -> Side.DEBIT; case EMPLOYEE_RECEIVABLE, EMPLOYEE_PAYABLE -> Side.CREDIT; default -> throw invalid(); };
            case PAYMENT -> switch (role) { case EMPLOYEE_PAYABLE -> Side.DEBIT; case BANK -> Side.CREDIT; default -> throw invalid(); };
        };
    }
    private static void requireTotal(List<Line> lines, AccountMappingPort.Role role, Money expected) {
        var total = lines.stream().filter(line -> line.account().role() == role).map(Line::amount).reduce(Money.zero(expected.currency()), Money::plus);
        if (!total.equals(expected)) throw invalid();
    }
    private static void add(MessageDigest digest, String... values) {
        for (String value : values) { byte[] bytes = value.getBytes(StandardCharsets.UTF_8); digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array()); digest.update(bytes); }
    }
    private static String empty(Object value) { return value == null ? "" : value.toString(); }
    private static boolean invalidText(String value) { return StringUtils.isBlank(value) || value.length() > 128; }
    private static DomainException invalid() { return new DomainException("INVALID_VOUCHER_COMMAND", "Voucher must preserve its business basis, balanced posting roles and versioned ERP evidence"); }

    /**
     * 挂账与实际付款凭证分开，付款凭证必须已有成功到账依据。
     * @author owlzhangfq@gmail.com
     */
    public enum Kind { EMPLOYEE_ADVANCE, EXPENSE_ACCRUAL, PAYMENT }
    /**
     * 借贷方向不可由正负金额隐式表达。
     * @author owlzhangfq@gmail.com
     */
    public enum Side { DEBIT, CREDIT }
    /**
     * 同轮同类型的有效凭证唯一性由结算仓储维护，命令始终绑定实际双版本。
     * @author owlzhangfq@gmail.com
     */
    public record Binding(UUID businessId, UUID applicationId, int roundNo, long applicationVersion, long businessVersion) {
        /** 原审批和业务版本必须同时保留。 */
        public Binding { if (businessId == null || applicationId == null || roundNo < 1 || applicationVersion < 1 || businessVersion < 1) throw invalid(); }
    }
    /**
     * 核定含税、可抵扣税与借款冲销总额，净应付由实际核定事实计算。
     * @author owlzhangfq@gmail.com
     */
    public record Totals(Money gross, Money tax, Money offset) {
        /** 零影响业务不生成虚构的零金额凭证。 */
        public Totals { if (gross == null || gross.value().signum() <= 0 || tax == null || offset == null || tax.compareTo(gross) > 0 || offset.compareTo(gross) > 0) throw invalid(); }
        /** 借款冲销减少员工应付，不减少费用含税占用。 */
        public Money payable() { return gross.minus(offset); }
    }
    /**
     * 凭证明细携带原费用行、成本对象和借款引用；完整科目取自映射快照。
     * @author owlzhangfq@gmail.com
     */
    public record Line(int lineNo, AccountMappingPort.Key account, Side side, Money amount, int sourceLineNo,
                       String costCenter, String projectCode, UUID advanceId) {
        /** 金额均为正额，辅助核算只能出现在对应的业务用途上。 */
        public Line {
            if (lineNo < 1 || lineNo > MAX_LINES || account == null || side == null || amount == null || amount.value().signum() <= 0) throw invalid();
            boolean expense = account.role() == AccountMappingPort.Role.EXPENSE || account.role() == AccountMappingPort.Role.DEDUCTIBLE_TAX;
            if (expense ? sourceLineNo < 1 || sourceLineNo > io.agentflow.expense.ExpenseContent.MAX_LINES || invalidText(costCenter) || projectCode != null && invalidText(projectCode)
                    : sourceLineNo != 0 || costCenter != null || projectCode != null) throw invalid();
            if (account.role() != AccountMappingPort.Role.EMPLOYEE_RECEIVABLE && advanceId != null) throw invalid();
        }
    }
    /**
     * 付款凭证绑定原支付命令及完整成功回单，待处理或退回不能伪装为付款入账。
     * @author owlzhangfq@gmail.com
     */
    public record PaymentProof(PaymentCommand command, PaymentObservation receipt) {
        /** 构造时即拒绝其他支付、不同摘要和非成功事实。 */
        public PaymentProof {
            if (command == null || receipt == null || receipt.status() != PaymentObservation.Status.SUCCEEDED
                    || !receipt.matches(command, true, receipt.observedAt())) throw invalid();
        }
        private boolean matches(String tenantId, Binding binding, UUID entity, String employee, Money gross, Instant now) {
            var source = command.binding();
            return tenantId.equals(command.tenantId()) && binding.businessId().equals(source.businessId()) && binding.applicationId().equals(source.applicationId())
                    && binding.roundNo() == source.roundNo() && binding.applicationVersion() == source.applicationVersion() && binding.businessVersion() == source.businessVersion()
                    && command.payee().legalEntityId().equals(entity) && command.payee().employeeId().equals(employee)
                    && command.amount().equals(gross) && !receipt.observedAt().isAfter(now);
        }
    }
}
