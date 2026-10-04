package io.agentflow.organization;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.UUID;
import static io.agentflow.organization.OrganizationSyncKey.*;

/**
 * 来源对象与本地实体的稳定绑定，记录上次同步的本地修订以识别之后的人工改动。
 * @author owlzhangfq@gmail.com
 */
public record OrganizationSyncBinding(String tenantId, String sourceKey, OrganizationSyncKey key, UUID localId,
                                      long localRevision, long sourceRevision, long version, UUID appliedBatchId, Instant updatedAt) {
    /** 绑定必须来自一个已经应用的来源批次，租户与真实本地引用由仓储外键共同约束。 */
    public OrganizationSyncBinding {
        text(tenantId, 64); source(sourceKey);
        if (key == null || localId == null || localRevision < 1 || sourceRevision < 1 || version < 1 || appliedBatchId == null || updatedAt == null) throw invalid();
    }

    /** 保留原对象身份；调岗和主体更换不能通过更新映射覆盖历史关系。 */
    public OrganizationSyncBinding applied(long expectedVersion, long localRevision, long sourceRevision, UUID batchId, Instant at) {
        if (version != expectedVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Organization source binding changed");
        if (localRevision < this.localRevision || sourceRevision <= this.sourceRevision || at == null || at.isBefore(updatedAt)) throw invalid();
        return new OrganizationSyncBinding(tenantId, sourceKey, key, localId, localRevision, sourceRevision, version + 1, batchId, at);
    }
}
