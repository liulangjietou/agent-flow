package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 发票聚合区分查验与占用状态；任何查验失败都不能释放他人占用或制造可核销额度。
 * @author owlzhangfq@gmail.com
 */
public final class Invoice {
    private final UUID id;
    private final String tenantId;
    private final String ownerId;
    private final UUID originalFileId;
    private final String originalDigest;
    private long version = 1;
    private Verification verification = Verification.PENDING;
    private Occupation occupation = Occupation.AVAILABLE;
    private VerifiedFacts facts;
    private String failureCode;
    private Instant checkedAt;
    private ExpenseUse use;
    private List<ConsumptionReversal> reversals = List.of();

    /** 原件由存储服务验证后建立票夹记录，此时尚无已查验事实。 */
    public static Invoice uploaded(UUID id, String tenantId, String ownerId, UUID originalFileId, String originalDigest) {
        return new Invoice(id, tenantId, ownerId, originalFileId, originalDigest);
    }

    private Invoice(UUID id, String tenantId, String ownerId, UUID originalFileId, String originalDigest) {
        this.id = Objects.requireNonNull(id); this.originalFileId = Objects.requireNonNull(originalFileId);
        if (StringUtils.isBlank(tenantId) || tenantId.length() > 64 || StringUtils.isBlank(ownerId) || ownerId.length() > 128
                || originalDigest == null || !originalDigest.matches("[a-f0-9]{64}")) throw invalid();
        this.tenantId = tenantId; this.ownerId = ownerId; this.originalDigest = originalDigest;
    }

    /** 只接受与原件摘要绑定的查验结果，已确认的票面身份和金额不能被后续响应换掉。 */
    public void verified(long expectedVersion, VerifiedFacts result) {
        requireVersion(expectedVersion);
        if (result == null || !originalDigest.equals(result.originalDigest())) throw invalid();
        requireCheckTime(result.verifiedAt());
        if (facts != null && (!facts.key().equals(result.key()) || !facts.legalEntityId().equals(result.legalEntityId())
                || !facts.gross().equals(result.gross()) || !facts.tax().equals(result.tax()) || !facts.issueDate().equals(result.issueDate()))) {
            throw new DomainException("INVOICE_FACTS_CHANGED", "Verified invoice identity or amounts changed for the same original file");
        }
        facts = result; verification = Verification.VERIFIED; failureCode = null; checkedAt = result.verifiedAt(); version++;
    }

    /** 真实无效结果保留原事实和占用；服务不可用不调用此方法伪造查验结论。 */
    public void invalidated(long expectedVersion, String code, Instant at) {
        requireVersion(expectedVersion); requireCheckTime(at);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) throw invalid();
        verification = Verification.FAILED; failureCode = code; checkedAt = at; version++;
    }

    /** 申请人、法人和查验有效期在本次占用时核对，跨发票唯一性由仓储同时保证。 */
    public void occupy(long expectedVersion, ExpenseUse target, String employeeId, UUID legalEntityId, Instant at) {
        requireVersion(expectedVersion); requireVerified(at);
        if (target == null || target.lineNo() < 1) throw invalid();
        if (!ownerId.equals(employeeId)) throw new DomainException("INVOICE_OWNER_MISMATCH", "Invoice belongs to another employee");
        if (!facts.legalEntityId().equals(legalEntityId)) throw new DomainException("INVOICE_TITLE_MISMATCH", "Invoice buyer does not match the legal entity");
        if (occupation != Occupation.AVAILABLE) throw new DomainException("INVOICE_OCCUPIED", "Invoice already has an active occupation");
        if (reversals.stream().anyMatch(value -> value.use().equals(target))) throw reversalConflict();
        occupation = Occupation.OCCUPIED; use = target; version++;
    }

    /** 重提只迁移同一报销单的原轮次；新法人和本人身份仍由占用事实限制。 */
    public void move(long expectedVersion, ExpenseUse previous, ExpenseUse next, UUID legalEntityId, Instant at) {
        requireVersion(expectedVersion); requireUse(previous); requireVerified(at);
        if (next == null || next.lineNo() < 1 || !previous.reportId().equals(next.reportId()) || next.roundNo() != previous.roundNo() + 1
                || !facts.legalEntityId().equals(legalEntityId)) throw invalid();
        if (reversals.stream().anyMatch(value -> value.use().equals(next))) throw reversalConflict();
        use = next; version++;
    }

    /** 只有完整匹配的现行轮次才能释放尚未核销的发票。 */
    public void release(long expectedVersion, ExpenseUse expectedUse) {
        requireVersion(expectedVersion); requireUse(expectedUse);
        occupation = Occupation.AVAILABLE; use = null; version++;
    }

    /** 结算后保留占用键作为已核销事实，不再允许释放回个人票夹。 */
    public void consume(long expectedVersion, ExpenseUse expectedUse, Instant at) {
        requireVersion(expectedVersion); requireUse(expectedUse); requireVerified(at);
        occupation = Occupation.CONSUMED; version++;
    }

    /** 独立调整释放已核销原件并保留原归属；再次使用必须取得调整后的真实查验结果。 */
    public void reverseConsumption(long expectedVersion, ExpenseUse expectedUse, UUID adjustmentId, Instant at) {
        requireVersion(expectedVersion); requireCheckTime(at);
        if (occupation != Occupation.CONSUMED || !Objects.equals(use, expectedUse)
                || reversals.stream().anyMatch(value -> value.use().equals(expectedUse))) throw reversalConflict();
        var updated = new ArrayList<>(reversals); updated.add(new ConsumptionReversal(adjustmentId, expectedUse, at));
        reversals = List.copyOf(updated); occupation = Occupation.AVAILABLE; use = null;
        verification = Verification.PENDING; failureCode = null; version++;
    }

    /** 返回当前有效查验事实，过期边界为右开区间。 */
    public VerifiedFacts requireVerified(Instant at) {
        if (verification != Verification.VERIFIED || facts == null || at == null || at.isBefore(facts.verifiedAt()) || !at.isBefore(facts.validUntil())) {
            throw new DomainException("INVOICE_VERIFICATION_REQUIRED", "A current successful invoice verification is required");
        }
        return facts;
    }

    private void requireUse(ExpenseUse expectedUse) {
        if (occupation != Occupation.OCCUPIED || !Objects.equals(use, expectedUse)) {
            throw new DomainException("INVOICE_OCCUPATION_CHANGED", "Invoice occupation no longer matches the expected report round");
        }
    }
    private void requireVersion(long expectedVersion) { if (version != expectedVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Invoice version has changed"); }
    private void requireCheckTime(Instant at) {
        if (at == null || checkedAt != null && at.isBefore(checkedAt)
                || reversals.stream().anyMatch(value -> at.isBefore(value.reversedAt()))) throw new DomainException("INVALID_INVOICE_TIME", "Invoice verification time is out of order");
    }
    private static DomainException reversalConflict() { return new DomainException("CONSUMPTION_REVERSAL_CONFLICT", "Invoice adjustment must retain the original consumed report round"); }
    private static DomainException invalid() { return new DomainException("INVALID_INVOICE", "Invoice identity, original file or verified facts are invalid"); }

    /** 恢复时核对查验与占用维度的一致性，原件身份始终不可变。 */
    public static Invoice restore(State state) {
        var result = uploaded(state.id(), state.tenantId(), state.ownerId(), state.originalFileId(), state.originalDigest());
        if (state.version() < 1 || state.verification() == null || state.occupation() == null
                || state.occupation() == Occupation.AVAILABLE && state.use() != null
                || state.occupation() != Occupation.AVAILABLE && (state.use() == null || state.facts() == null || state.use().lineNo() < 1)
                || state.verification() == Verification.VERIFIED && (state.facts() == null || state.checkedAt() == null || state.failureCode() != null)
                || state.verification() == Verification.FAILED && (state.failureCode() == null || state.checkedAt() == null)
                || state.facts() != null && !state.originalDigest().equals(state.facts().originalDigest())) throw invalid();
        var reversals = state.reversals() == null ? List.<ConsumptionReversal>of() : List.copyOf(state.reversals());
        if (reversals.stream().map(ConsumptionReversal::use).distinct().count() != reversals.size()
                || !reversals.isEmpty() && state.facts() == null || reversals.stream().anyMatch(value -> value.use().equals(state.use()))) throw invalid();
        for (int index = 1; index < reversals.size(); index++) {
            if (reversals.get(index).reversedAt().isBefore(reversals.get(index - 1).reversedAt())) throw invalid();
        }
        if (state.verification() == Verification.VERIFIED && reversals.stream().anyMatch(value -> state.facts().verifiedAt().isBefore(value.reversedAt()))) throw invalid();
        result.version = state.version(); result.verification = state.verification(); result.occupation = state.occupation();
        result.facts = state.facts(); result.failureCode = state.failureCode(); result.checkedAt = state.checkedAt(); result.use = state.use();
        result.reversals = reversals;
        return result;
    }

    /** 保存当前发票状态及完整原件绑定，由仓储追加版本证据。 */
    public State state() { return new State(id, tenantId, ownerId, originalFileId, originalDigest, version, verification, occupation, facts, failureCode, checkedAt, use, reversals); }

    public UUID id() { return id; }
    public String tenantId() { return tenantId; }
    public String ownerId() { return ownerId; }
    public UUID originalFileId() { return originalFileId; }
    public String originalDigest() { return originalDigest; }
    public long version() { return version; }
    public Verification verification() { return verification; }
    public Occupation occupation() { return occupation; }
    public VerifiedFacts facts() { return facts; }
    public String failureCode() { return failureCode; }
    public Instant checkedAt() { return checkedAt; }
    public ExpenseUse use() { return use; }
    public List<ConsumptionReversal> reversals() { return reversals; }

    /**
     * 发票可恢复状态，不把票面确认或占用归属暴露为客户端更新字段。
     * @author owlzhangfq@gmail.com
     */
    public record State(UUID id, String tenantId, String ownerId, UUID originalFileId, String originalDigest, long version,
                         Verification verification, Occupation occupation, VerifiedFacts facts, String failureCode,
                         Instant checkedAt, ExpenseUse use, List<ConsumptionReversal> reversals) {
        /** 兼容旧发票快照；历史没有调整时不制造核销冲回。 */
        public State(UUID id, String tenantId, String ownerId, UUID originalFileId, String originalDigest, long version,
                Verification verification, Occupation occupation, VerifiedFacts facts, String failureCode, Instant checkedAt, ExpenseUse use) {
            this(id, tenantId, ownerId, originalFileId, originalDigest, version, verification, occupation, facts, failureCode, checkedAt, use, List.of());
        }
        /** 快照不共享调用方可变集合，缺失字段只用于旧版本兼容。 */
        public State { reversals = reversals == null ? List.of() : List.copyOf(reversals); }
    }

    /**
     * 原件退出一笔已核销报销的独立事实，与原件身份及后续占用分别保存。
     * @author owlzhangfq@gmail.com
     */
    public record ConsumptionReversal(UUID adjustmentId, ExpenseUse use, Instant reversedAt) {
        /** 发票核销归属必须指向实际报销行。 */
        public ConsumptionReversal {
            if (adjustmentId == null || use == null || use.lineNo() < 1 || reversedAt == null) throw invalid();
        }
    }

    /**
     * 发票查验状态不等同于可报销状态。
     * @author owlzhangfq@gmail.com
     */
    public enum Verification { PENDING, VERIFIED, FAILED }
    /**
     * 已核销占用不自动恢复为可用。
     * @author owlzhangfq@gmail.com
     */
    public enum Occupation { AVAILABLE, OCCUPIED, CONSUMED }

    /**
     * 可信查验的票面与时效，绑定实际原件字节摘要。
     * @author owlzhangfq@gmail.com
     */
    public record VerifiedFacts(InvoiceKey key, UUID legalEntityId, Money gross, Money tax, LocalDate issueDate,
                                 String originalDigest, String reference, Instant verifiedAt, Instant validUntil) {
        /** 票面税额与币种必须自洽，验证响应必须明确有效期。 */
        public VerifiedFacts {
            if (key == null || legalEntityId == null || gross == null || gross.value().signum() <= 0 || tax == null
                    || issueDate == null || originalDigest == null || !originalDigest.matches("[a-f0-9]{64}")
                    || StringUtils.isBlank(reference) || reference.length() > 128 || verifiedAt == null || validUntil == null
                    || !validUntil.isAfter(verifiedAt) || tax.compareTo(gross) > 0) throw invalid();
        }
    }
}
