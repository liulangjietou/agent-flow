package io.agentflow.expense;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.UUID;

/**
 * 费用制度草稿与发布游标；编辑产生修订，发布物独立保存且不能被后续编辑覆盖。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePolicyDraft(UUID id, String tenantId, String key, long revision, ExpensePolicyDefinition definition,
                                 long publishedVersion, long publishedDraftRevision) {
    /** 重建拒绝不连续的发布元数据和非法身份。 */
    public ExpensePolicyDraft {
        if (id == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || !validKey(key) || revision < 1 || definition == null
                || publishedVersion < 0 || publishedDraftRevision < 0 || publishedDraftRevision > revision || publishedVersion > publishedDraftRevision
                || (publishedVersion == 0) != (publishedDraftRevision == 0)) throw new DomainException("INVALID_EXPENSE_POLICY_DRAFT", "Expense policy draft identity and revision are invalid");
    }

    /** 明确创建首个草稿，不虚构已发布版本。 */
    public static ExpensePolicyDraft create(String tenant, String key, ExpensePolicyDefinition definition) {
        return new ExpensePolicyDraft(UUID.randomUUID(), tenant, key, 1, definition, 0, 0);
    }

    /** 只改变当前草稿，旧发布物仍引用原定义。 */
    public ExpensePolicyDraft revise(long expectedRevision, ExpensePolicyDefinition next) {
        requireRevision(expectedRevision);
        if (definition.equals(next)) throw unchanged();
        return new ExpensePolicyDraft(id, tenantId, key, revision + 1, next, publishedVersion, publishedDraftRevision);
    }

    /** 发布固定当前规则和已确认类别版本，不为相同草稿重复生成版本。 */
    public Publication publish(long expectedRevision, ExpenseCategoryCatalog categories, String actor, Instant now, String comment) {
        requireRevision(expectedRevision);
        if (publishedDraftRevision == revision) throw unchanged();
        if (!tenantId.equals(categories.tenantId())) throw new DomainException("EXPENSE_POLICY_CATEGORY_UNAVAILABLE", "Expense policy and categories must belong to the same tenant");
        definition.requirePublishable(categories);
        var version = new PublishedExpensePolicy(id, tenantId, key, publishedVersion + 1, revision, categories.version(), definition, actor, now, comment);
        return new Publication(new ExpensePolicyDraft(id, tenantId, key, revision, definition, version.version(), revision), version);
    }

    /** 并发校验属于草稿自身状态约束。 */
    public void requireRevision(long expected) {
        if (revision != expected) throw new DomainException("CONCURRENCY_CONFLICT", "Expense policy draft revision changed");
    }

    /** 路径键使用稳定的小写业务标识，不解释路径或脚本片段。 */
    public static boolean validKey(String key) { return key != null && key.matches("[a-z][a-z0-9-]{0,63}"); }
    private static DomainException unchanged() { return new DomainException("EXPENSE_CONFIGURATION_UNCHANGED", "Expense policy draft has no unpublished changes"); }

    /**
     * 应用层必须在同一事务中保存更新游标和新发布物。
     * @author owlzhangfq@gmail.com
     */
    public record Publication(ExpensePolicyDraft draft, PublishedExpensePolicy version) { }
}
