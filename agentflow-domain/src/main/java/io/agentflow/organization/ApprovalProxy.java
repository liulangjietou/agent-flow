package io.agentflow.organization;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 一个已发布流程版本中的有期限代理；原范围不可修改，提前结束只追加撤销事实。
 * @author owlzhangfq@gmail.com
 */
public record ApprovalProxy(UUID id, UUID definitionId, UUID principalId, UUID substituteId,
                            Instant startsAt, Instant endsAt, String reason, String createdBy,
                            Instant createdAt, long revision, Revocation revocation) {
    private static final int MAX_REASON_LENGTH = 1000;
    private static final int MAX_SUBJECT_LENGTH = 128;
    private static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59.999999Z");

    /** 时间统一到数据库微秒精度，避免边界在保存前后发生变化。 */
    public ApprovalProxy {
        if (id == null || definitionId == null || principalId == null || substituteId == null
                || principalId.equals(substituteId)) throw invalid();
        startsAt = time(startsAt); endsAt = time(endsAt); createdAt = time(createdAt);
        reason = reason(reason); subject(createdBy);
        if (!startsAt.isBefore(endsAt) || !createdAt.isBefore(endsAt)
                || revision != (revocation == null ? 1 : 2)
                || revocation != null && revocation.at().isBefore(createdAt)) throw invalid();
    }

    /** 生效包含开始时刻，到期不包含结束时刻；创建前不产生追溯授权。 */
    public Status statusAt(Instant now) {
        if (revocation != null) return Status.REVOKED;
        if (!now.isBefore(endsAt)) return Status.EXPIRED;
        return now.isBefore(startsAt) || now.isBefore(createdAt) ? Status.SCHEDULED : Status.ACTIVE;
    }

    /** 撤销是终态；更换人员、范围或期限必须创建另一条有独立依据的记录。 */
    public ApprovalProxy revoke(long expectedRevision, String actor, String reason, Instant now) {
        OrganizationRevision.require(revision, expectedRevision);
        if (revocation != null) throw new DomainException("APPROVAL_PROXY_REVOKED", "Approval proxy has already been revoked");
        return new ApprovalProxy(id, definitionId, principalId, substituteId, startsAt, endsAt,
                this.reason, createdBy, createdAt, revision + 1, new Revocation(actor, reason, now));
    }

    /**
     * 状态由原期限和撤销事实计算，不依赖定时器修改授权。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { SCHEDULED, ACTIVE, EXPIRED, REVOKED }

    /**
     * 具名撤销保留原创建原因，不覆盖授权当时的事实。
     * @author owlzhangfq@gmail.com
     */
    public record Revocation(String actor, String reason, Instant at) {
        /** 撤销主体来自认证上下文，时间由服务端提供。 */
        public Revocation { subject(actor); reason = ApprovalProxy.reason(reason); at = time(at); }
    }

    private static Instant time(Instant value) {
        if (value == null || value.isBefore(EARLIEST) || value.isAfter(LATEST)) throw invalid();
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    private static String reason(String value) {
        if (StringUtils.isBlank(value) || value.length() > MAX_REASON_LENGTH
                || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return value.strip();
    }

    private static void subject(String value) {
        if (StringUtils.isBlank(value) || value.length() > MAX_SUBJECT_LENGTH
                || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
    }

    private static DomainException invalid() {
        return new DomainException("INVALID_APPROVAL_PROXY", "Approval proxy requires distinct people, an explicit scope, a finite period and a reason");
    }
}
