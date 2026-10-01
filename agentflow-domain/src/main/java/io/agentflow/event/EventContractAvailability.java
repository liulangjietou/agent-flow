package io.agentflow.event;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 精确版本的可用性与契约正文分离。停用不删除版本，也不能把旧引用切换到新版本。
 * @author owlzhangfq@gmail.com
 */
public record EventContractAvailability(String tenantId, String key, long contractVersion, long revision,
                                        boolean enabled, String changedBy, Instant changedAt, String reason) {
    /** 发布时形成第一条可追溯的启用事实。 */
    public static EventContractAvailability published(EventContract contract) {
        return new EventContractAvailability(contract.tenantId(), contract.key(), contract.version(), 1, true,
                contract.publishedBy(), contract.publishedAt(), contract.publicationReason());
    }

    /** 只修改当前明确选择的版本；相同状态不制造新的审计修订。 */
    public EventContractAvailability change(long expectedRevision, boolean enabled, String actor, String reason, Instant now) {
        if (revision != expectedRevision || revision == Long.MAX_VALUE) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Event contract availability revision changed");
        }
        if (this.enabled == enabled) throw new DomainException("EVENT_CONTRACT_AVAILABILITY_UNCHANGED", "Event contract availability is unchanged");
        return new EventContractAvailability(tenantId, key, contractVersion, revision + 1, enabled,
                actor, now.truncatedTo(ChronoUnit.MICROS), EventContract.requireReason(reason));
    }
}
