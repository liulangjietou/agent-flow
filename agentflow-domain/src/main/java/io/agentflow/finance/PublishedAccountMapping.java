package io.agentflow.finance;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 不可变科目映射发布物；后续草稿、停用类别和重新发布均不能改写原正文。
 * @author owlzhangfq@gmail.com
 */
public record PublishedAccountMapping(UUID mappingId, String tenantId, String key, long version, long draftRevision,
                                      long categoryRevision, AccountMappingDefinition definition, String targetDigest,
                                      String publishedBy, Instant publishedAt, String comment) {
    /** 发布证据必须包含明确目标、非空科目集合和审计理由。 */
    public PublishedAccountMapping {
        if (mappingId == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || !AccountMappingDraft.validKey(key)
                || version < 1 || draftRevision < version || categoryRevision < 0 || definition == null || definition.entries().isEmpty()
                || categoryRevision == 0 && definition.entries().stream().anyMatch(entry -> entry.key().role() == AccountMappingPort.Role.EXPENSE)
                || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}") || StringUtils.isBlank(publishedBy) || publishedBy.length() > 128
                || publishedAt == null || StringUtils.isBlank(comment) || comment.length() > 2000) {
            throw new DomainException("INVALID_ACCOUNT_MAPPING_PUBLICATION", "Account mapping publication requires immutable source versions, target and audit facts");
        }
        comment = comment.strip();
    }

    /** 只将本次必要科目送往 ERP 校验，原选择不能被无声重新绑定。 */
    public AccountMappingPort.Request request(AccountMappingPort.Request original, long activeRevision) {
        if (original.managedMapping() != null) throw new DomainException("ACCOUNT_MAPPING_SCOPE_MISMATCH", "An accounting request already binds its published mapping");
        var entries = definition.select(original);
        var selection = new AccountMappingSelection(mappingId, version, categoryRevision, activeRevision, definition.digest(), targetDigest);
        return new AccountMappingPort.Request(original.legalEntityId(), original.currency(), original.keys(),
                new ManagedAccountMapping(tenantId, definition.legalEntityId(), definition.currency(), selection, entries));
    }
}
