package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 独立冲正已确认的预算实际占用，原冻结、消费命令及其回执保持不变。
 * @author owlzhangfq@gmail.com
 */
public record BudgetConsumptionReversalCommand(UUID id, UUID adjustmentId, BudgetCommand source, BudgetObservation consumed,
        AccountingPeriodPort.OpenPeriod period, String authorizedBy, String evidenceReference, String reason, Instant createdAt, Instant expiresAt) {
    public static final Duration MAX_AUTHORIZATION_AGE = Duration.ofMinutes(5);

    /** 原消费、完整分摊、新开放期间和独立调整授权必须同时成立。 */
    public BudgetConsumptionReversalCommand {
        if (id == null || adjustmentId == null || source == null || id.equals(source.id()) || source.action() != BudgetCommand.Action.CONSUME
                || consumed == null || consumed.status() != BudgetObservation.Status.APPLIED || createdAt == null
                || !consumed.matches(source, false, createdAt) || consumed.ledgerRevision() == Long.MAX_VALUE
                || !text(consumed.reference(), 128) || period == null || !text(authorizedBy, 128)
                || authorizedBy.equals(source.position().employeeId()) || !text(evidenceReference, 128) || !text(reason, 2000)
                || expiresAt == null || !expiresAt.isAfter(createdAt) || expiresAt.isAfter(createdAt.plus(MAX_AUTHORIZATION_AGE))
                || expiresAt.isAfter(period.validUntil())) throw invalid();
        var request = period.request(); var original = source.position();
        if (!request.legalEntityId().equals(original.legalEntityId()) || !request.currency().equals(original.baseCurrency())
                || request.accountingDate().isBefore(original.accountingDate()) || !period.matches(request, createdAt)) throw invalid();
    }

    /** 稳定摘要固定原消费回执、完整分摊摘要、冲正期间和人工决定，重发不能换日期或金额。 */
    public String digest() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            add(digest, "agentflow-budget-consumption-reversal-1", id.toString(), adjustmentId.toString(), source.digest(),
                    consumed.operationId().toString(), consumed.commandDigest(), consumed.status().name(), consumed.ledgerRevision().toString(),
                    consumed.reference(), consumed.appliedAt().toString(), period.request().legalEntityId().toString(), period.request().currency(),
                    period.request().accountingDate().toString(), period.periodReference(), period.sourceVersion(), period.startsOn().toString(),
                    period.endsOn().toString(), period.observedAt().toString(), period.validUntil().toString(), authorizedBy, evidenceReference,
                    reason, createdAt.toString(), expiresAt.toString());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    /** 授权失效后仍可查询原操作，但不能发送新的冲正写入。 */
    public void requireSendAt(Instant now) {
        if (now == null || now.isBefore(createdAt) || !now.isBefore(expiresAt)) {
            throw new DomainException("BUDGET_REVERSAL_AUTHORIZATION_EXPIRED", "Budget reversal authorization and accounting evidence have expired");
        }
    }

    @Override public String toString() { return "BudgetConsumptionReversalCommand[id=" + id + ", sourceId=" + source.id() + "]"; }
    private static void add(MessageDigest digest, String... values) {
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array()); digest.update(bytes);
        }
    }
    private static boolean text(String value, int length) { return StringUtils.isNotBlank(value) && value.equals(value.trim()) && value.length() <= length && value.chars().noneMatch(Character::isISOControl); }
    private static DomainException invalid() { return new DomainException("INVALID_BUDGET_REVERSAL_COMMAND", "Budget reversal requires original consumed occupation, an open period and independent adjustment authorization"); }
}
