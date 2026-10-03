package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 独立冲销写命令固定历史科目、完整反向分录、当前开放期间及独立财务的明确授权。
 * @author owlzhangfq@gmail.com
 */
public record VoucherReversalCommand(UUID id, VoucherReversalPort.Request source, VoucherObservation verifiedOriginal,
                                     AccountingPeriodPort.OpenPeriod period, String authorizedBy, String evidenceReference,
                                     String reason, Instant createdAt, Instant expiresAt) {
    /** 历史凭证不可编辑；新日期由财务明确选择，原期间关闭时不自动改期。 */
    public VoucherReversalCommand {
        if (id == null || source == null || id.equals(source.command().id()) || verifiedOriginal == null
                || verifiedOriginal.status() != VoucherObservation.Status.POSTED || !source.matchesOriginal(verifiedOriginal)
                || period == null || invalidText(authorizedBy, 128) || !VoucherDisputeResolution.independent(source.command(), authorizedBy)
                || invalidText(evidenceReference, 128) || evidenceReference.chars().anyMatch(Character::isISOControl)
                || invalidText(reason, 2000) || createdAt == null || expiresAt == null || !expiresAt.isAfter(createdAt)
                || createdAt.isBefore(verifiedOriginal.observedAt()) || expiresAt.isAfter(verifiedOriginal.observedAt().plus(VoucherReversalPort.MAX_EVIDENCE_AGE))
                || expiresAt.isAfter(createdAt.plus(VoucherReversalPort.MAX_EVIDENCE_AGE)) || expiresAt.isAfter(period.validUntil())) throw invalid();
        var original = source.command(); var request = period.request();
        if (!request.legalEntityId().equals(original.legalEntityId()) || !request.currency().equals(original.totals().gross().currency())
                || request.accountingDate().isBefore(original.accountingDate()) || !period.matches(request, createdAt)) throw invalid();
    }

    /** 每行金额和辅助核算取自原命令，只有借贷方向翻转；不重新查询科目映射。 */
    public List<Line> lines() {
        var command = source.command();
        return command.lines().stream().map(line -> new Line(line.lineNo(), command.mapping().account(line.account()),
                line.side() == VoucherCommand.Side.DEBIT ? VoucherCommand.Side.CREDIT : VoucherCommand.Side.DEBIT,
                line.amount(), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId())).toList();
    }

    /** 稳定摘要以长度前缀编码全部来源、当前复核和授权，避免字符串分隔符歧义。 */
    public String digest() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            add(digest, "agentflow-voucher-reversal-command-1", id.toString(), source.command().digest());
            addOriginal(digest, source.original()); addOriginal(digest, verifiedOriginal);
            add(digest, period.request().legalEntityId().toString(), period.request().currency(), period.request().accountingDate().toString(),
                    period.periodReference(), period.sourceVersion(), period.startsOn().toString(), period.endsOn().toString(),
                    period.observedAt().toString(), period.validUntil().toString(), authorizedBy, evidenceReference, reason,
                    createdAt.toString(), expiresAt.toString());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    /** 只禁止新发送；过期后仍查询相同编号与摘要，不能另造冲销命令。 */
    public void requireSendAt(Instant now) {
        if (now == null || now.isBefore(createdAt) || !now.isBefore(expiresAt)) throw new DomainException("VOUCHER_REVERSAL_EVIDENCE_EXPIRED", "Reversal authorization and accounting evidence are outside their sending window");
    }

    /** 独立结果必须核对固定日期和期间，ERP 不能静默改期。 */
    public boolean matchesPosting(VoucherReversalPort.Receipt receipt, Instant observedAt) {
        if (receipt == null || receipt.status() != VoucherReversalPort.Status.VERIFIED || !receipt.request().equals(source)
                || !receipt.observedAt().equals(observedAt) || receipt.current().revision() <= verifiedOriginal.revision()
                || receipt.current().observedAt().isBefore(verifiedOriginal.observedAt())) return false;
        var posting = receipt.reversal();
        return posting.periodReference().equals(period.periodReference()) && posting.accountingDate().equals(period.request().accountingDate())
                && !posting.postedAt().isBefore(createdAt);
    }

    @Override public String toString() { return "VoucherReversalCommand[id=" + id + ", originalId=" + source.command().id() + "]"; }
    private static void addOriginal(MessageDigest digest, VoucherObservation value) {
        add(digest, value.status().name(), value.revision().toString(), value.observedAt().toString(), value.postingReference(), value.voucherReference(),
                value.periodReference(), value.accountingDate().toString(), value.debitTotal().currency(), value.debitTotal().value().toPlainString(),
                value.creditTotal().value().toPlainString(), value.postedAt().toString());
    }
    private static void add(MessageDigest digest, String... values) {
        for (String value : values) { byte[] bytes = value.getBytes(StandardCharsets.UTF_8); digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array()); digest.update(bytes); }
    }
    private static boolean invalidText(String value, int max) { return StringUtils.isBlank(value) || value.length() > max; }
    private static DomainException invalid() { return new DomainException("INVALID_VOUCHER_REVERSAL_COMMAND", "Reversal must bind a freshly verified original posting, an explicit open period and an independent finance authorization"); }

    /**
     * 实际传输的反向分录；行内容只能由原凭证派生，ERP 返回时补充真实分录编号。
     * @author owlzhangfq@gmail.com
     */
    public record Line(int originalLineNo, String accountCode, VoucherCommand.Side side, Money amount, int sourceLineNo,
                       String costCenter, String projectCode, UUID advanceId) { }
}
