package io.agentflow.organization;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.UUID;
import static io.agentflow.organization.OrganizationSyncKey.*;

/**
 * 一个租户只注册一个可信组织来源；只有本地应用成功才推进其连续游标。
 * @author owlzhangfq@gmail.com
 */
public record OrganizationSyncSource(String tenantId, String sourceKey, long appliedRevision, long version,
                                     UUID lastAppliedBatchId, String registeredBy, Instant registeredAt) {
    /** 注册后保持来源身份，切换地址不能隐式创建第二套映射或重置游标。 */
    public OrganizationSyncSource {
        text(tenantId, 64); source(sourceKey); text(registeredBy, 128);
        if (appliedRevision < 0 || version < 1 || registeredAt == null
                || version == 1 && (appliedRevision != 0 || lastAppliedBatchId != null)
                || version > 1 && lastAppliedBatchId == null) throw invalid();
    }

    /** 失败、取消或收到数据均不推进；组织应用与本次转换由同一事务持久化。 */
    public OrganizationSyncSource applied(OrganizationSyncBatch batch, long expectedVersion) {
        if (version != expectedVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Organization source revision changed");
        if (batch == null || batch.state().status() != OrganizationSyncBatch.Status.APPLIED
                || !tenantId.equals(batch.context().tenantId()) || !sourceKey.equals(batch.context().sourceKey())
                || appliedRevision != batch.context().afterRevision()) {
            throw new DomainException("ORGANIZATION_SYNC_SOURCE_CHANGED", "Organization source cursor does not match the applied batch");
        }
        return new OrganizationSyncSource(tenantId, sourceKey, batch.state().delta().revision(), version + 1,
                batch.context().id(), registeredBy, registeredAt);
    }
}
