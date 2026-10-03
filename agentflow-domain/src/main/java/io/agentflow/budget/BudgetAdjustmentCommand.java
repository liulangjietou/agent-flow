package io.agentflow.budget;

import io.agentflow.common.DomainException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 独立财务确认后的单次预算指令，调拨两端必须由原系统在一个原子操作中应用。
 * @author owlzhangfq@gmail.com
 */
public record BudgetAdjustmentCommand(UUID id, ApprovedBudgetAdjustment source, BudgetLedgerPort.Snapshot ledger,
        List<BudgetAdjustmentContent.Change> changes, String authorizedBy, String reason, Instant authorizedAt, Instant expiresAt) {
    public static final Duration MAX_AUTHORIZATION_AGE = Duration.ofMinutes(5);

    /** 固定实际批准、最新台账和独立财务身份，禁止修改金额、预算方向或已用占用。 */
    public BudgetAdjustmentCommand {
        if (id == null || source == null || id.equals(source.requestId()) || id.equals(source.applicationId())
                || invalidText(authorizedBy, 128) || authorizedBy.equals(source.employeeId()) || invalidText(reason, 2000)
                || authorizedAt == null || expiresAt == null || !expiresAt.isAfter(authorizedAt)
                || expiresAt.isAfter(authorizedAt.plus(MAX_AUTHORIZATION_AGE)) || ledger == null
                || expiresAt.isAfter(ledger.validUntil()) || expiresAt.isAfter(ledger.observedAt().plus(BudgetLedgerPort.MAX_EVIDENCE_AGE))) throw invalid();
        source.requireCurrentLedger(ledger, authorizedAt);
        if (changes == null || !changes.equals(source.round().content().changes(ledger))) throw invalid();
        changes = List.copyOf(changes);
    }

    /** 授权有效期受当前台账限制，不能靠重新点击授权延长旧读取证据。 */
    public static BudgetAdjustmentCommand authorize(UUID id, ApprovedBudgetAdjustment source, BudgetLedgerPort.Snapshot ledger,
            String actor, String reason, Instant now) {
        Instant expires = now.plus(MAX_AUTHORIZATION_AGE);
        if (ledger.validUntil().isBefore(expires)) expires = ledger.validUntil();
        if (ledger.observedAt().plus(BudgetLedgerPort.MAX_EVIDENCE_AGE).isBefore(expires)) expires = ledger.observedAt().plus(BudgetLedgerPort.MAX_EVIDENCE_AGE);
        return new BudgetAdjustmentCommand(id, source, ledger, source.round().content().changes(ledger), actor, reason, now, expires);
    }

    public String tenantId() { return source.tenantId(); }
    public String targetDigest() { return source.round().targetDigest(); }

    /** 到期只禁止新的写入；已经发送的未知结果始终保留原编号查询。 */
    public void requireSendAt(Instant now) {
        if (now == null || now.isBefore(authorizedAt) || !now.isBefore(expiresAt)) {
            throw new DomainException("BUDGET_ADJUSTMENT_AUTHORIZATION_EXPIRED", "Budget adjustment authorization or ledger evidence has expired");
        }
    }

    /** 长度前缀覆盖完整批准依据、当前台账、两端变化及具名决定，排列无关的台账按预算引用规范化。 */
    public String digest() {
        try {
            var digest = MessageDigest.getInstance("SHA-256"); var round = source.round(); var content = round.content(); var entity = round.legalEntity();
            add(digest, "agentflow-budget-adjustment-command-1", id, source.tenantId(), source.requestId(), source.applicationId(), source.employeeId(),
                    source.approvedRequestVersion(), round.roundNo(), round.submittedRequestVersion(), round.submittedBy(), round.submittedAt(),
                    content.legalEntityId(), content.title(), content.purpose(), content.type(), content.accountingDate(), content.sourceBudgetReference(),
                    content.targetBudgetReference(), content.amount().value().toPlainString(), content.amount().currency(),
                    entity.id(), entity.name(), entity.baseCurrency(), entity.paperReceiptRequired(), entity.sourceVersion(), entity.timeZone(), round.catalogVersion(), round.targetDigest(),
                    source.approval().roundNo(), source.approval().applicationVersion(), source.approval().approvedBy(), source.approval().approvedAt());
            ledgerDigest(digest, round.ledger()); ledgerDigest(digest, ledger);
            add(digest, changes.size());
            for (var change : changes) add(digest, change.budgetReference(), change.expectedVersion(), change.beforeLimit().value().toPlainString(),
                    change.beforeLimit().currency(), change.afterLimit().value().toPlainString(), change.afterLimit().currency());
            add(digest, authorizedBy, reason, authorizedAt, expiresAt);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    private static void ledgerDigest(MessageDigest digest, BudgetLedgerPort.Snapshot snapshot) {
        var request = snapshot.request();
        add(digest, request.legalEntityId(), request.employeeId(), request.accountingDate(), request.budgetReferences().size(),
                snapshot.sourceVersion(), snapshot.observedAt(), snapshot.validUntil());
        for (var reference : request.budgetReferences()) {
            var position = snapshot.position(reference);
            add(digest, position.legalEntityId(), position.reference(), position.name(), position.version(), position.periodReference(),
                    position.periodStart(), position.periodEnd(), position.periodStatus(), position.limit().value().toPlainString(), position.limit().currency(),
                    position.committed().value().toPlainString(), position.committed().currency(), position.consumed().value().toPlainString(), position.consumed().currency());
        }
    }
    private static void add(MessageDigest digest, Object... values) {
        for (Object value : values) {
            byte[] bytes = value == null ? new byte[0] : value.toString().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value == null ? -1 : bytes.length).array()); digest.update(bytes);
        }
    }
    private static boolean invalidText(String value, int maximum) {
        return StringUtils.isBlank(value) || value.length() > maximum || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl);
    }
    private static DomainException invalid() { return new DomainException("INVALID_BUDGET_ADJUSTMENT_COMMAND", "Budget adjustment requires exact approved intent, fresh ledger evidence and independent financial authorization"); }
    /** 不向普通日志展开预算、金额或授权原因。 */
    @Override public String toString() { return "BudgetAdjustmentCommand[id=" + id + "]"; }
}
