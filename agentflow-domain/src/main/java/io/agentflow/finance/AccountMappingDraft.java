package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseCategoryCatalog;
import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 法人与币种固定的科目映射草稿，编辑和发布分别推进各自的版本。
 * @author owlzhangfq@gmail.com
 */
public record AccountMappingDraft(UUID id, String tenantId, String key, long revision, AccountMappingDefinition definition,
                                  long publishedVersion, long publishedDraftRevision) {
    /** 重建时拒绝不存在的发布修订和非法业务身份。 */
    public AccountMappingDraft {
        if (id == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || !validKey(key) || revision < 1 || definition == null
                || publishedVersion < 0 || publishedDraftRevision < 0 || publishedDraftRevision > revision || publishedVersion > publishedDraftRevision
                || (publishedVersion == 0) != (publishedDraftRevision == 0)) {
            throw new DomainException("INVALID_ACCOUNT_MAPPING_DRAFT", "Account mapping draft identity and revision are invalid");
        }
    }

    /** 创建不会自动激活或生成企业默认科目。 */
    public static AccountMappingDraft create(String tenant, String key, AccountMappingDefinition definition) {
        return new AccountMappingDraft(UUID.randomUUID(), tenant, key, 1, definition, 0, 0);
    }

    /** 业务键的法人和币种不可换用；变更范围须新建明确配置。 */
    public AccountMappingDraft revise(long expectedRevision, AccountMappingDefinition next) {
        requireRevision(expectedRevision);
        if (!definition.legalEntityId().equals(next.legalEntityId()) || !definition.currency().equals(next.currency())) {
            throw new DomainException("ACCOUNT_MAPPING_SCOPE_IMMUTABLE", "An account mapping key cannot change its legal entity or currency");
        }
        if (definition.equals(next)) throw unchanged();
        return new AccountMappingDraft(id, tenantId, key, Math.incrementExact(revision), next, publishedVersion, publishedDraftRevision);
    }

    /** 发布固定类别修订和服务端 ERP 目标，跨配置生效指针由应用服务原子更新。 */
    public Publication publish(long expectedRevision, ExpenseCategoryCatalog categories, String targetDigest, String actor, Instant now, String comment) {
        requireRevision(expectedRevision);
        if (publishedDraftRevision == revision) throw unchanged();
        if (!tenantId.equals(categories.tenantId())) throw new DomainException("ACCOUNT_MAPPING_SCOPE_MISMATCH", "Account mapping and category catalog must belong to the same tenant");
        definition.requirePublishable(categories);
        var version = new PublishedAccountMapping(id, tenantId, key, Math.incrementExact(publishedVersion), revision, categories.version(),
                definition, targetDigest, actor, now, comment);
        return new Publication(new AccountMappingDraft(id, tenantId, key, revision, definition, version.version(), revision), version);
    }

    /** 期望修订由操作者读取后提交，拒绝覆盖并发修改。 */
    public void requireRevision(long expected) {
        if (revision != expected) throw new DomainException("CONCURRENCY_CONFLICT", "Account mapping draft revision changed");
    }

    /** 管理路径只接受稳定的小写业务键。 */
    public static boolean validKey(String value) { return value != null && value.matches("[a-z][a-z0-9-]{0,63}"); }
    private static DomainException unchanged() { return new DomainException("ACCOUNT_MAPPING_UNCHANGED", "Account mapping draft has no unpublished changes"); }

    /**
     * 发布游标与不可变发布物须在同一数据库事务保存。
     * @author owlzhangfq@gmail.com
     */
    public record Publication(AccountMappingDraft draft, PublishedAccountMapping version) { }
}
