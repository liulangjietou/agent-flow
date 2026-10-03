package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 费用配置用例负责跨聚合版本检查和原子发布，类别与制度自身不变量由领域模型维护。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseConfigurationService {
    private final JdbcExpenseConfigurationRepository repository;

    /** 只依赖本地配置仓储，保存草稿不调用企业财务服务。 */
    public ExpenseConfigurationService(JdbcExpenseConfigurationRepository repository) { this.repository = repository; }

    /** 完整配置按一次头指针读取，再读取不可变正文，避免跨版本拼装。 */
    @Transactional(readOnly = true)
    public Current current(String tenant) {
        var head = repository.head(tenant);
        var policy = head.policyId() == null ? null : repository.version(tenant, head.policyId(), head.policyVersion()).orElseThrow(ExpenseConfigurationService::inconsistent);
        return new Current(repository.categories(tenant, head.categoryRevision()), head.activeRevision(), policy);
    }

    /** 只读类别，不因为尚未发布制度而生成默认类别。 */
    @Transactional(readOnly = true)
    public ExpenseCategoryCatalog categories(String tenant) { var head = repository.head(tenant); return repository.categories(tenant, head.categoryRevision()); }

    /** 明确修订整个类别目录；历史身份只能停用，不能移除。 */
    @Transactional
    public ExpenseCategoryCatalog saveCategories(Actor actor, long expectedVersion, List<ExpenseCategoryCatalog.Category> categories, String comment) {
        var head = repository.lock(actor.tenantId());
        var next = repository.categories(actor.tenantId(), head.categoryRevision()).revise(expectedVersion, categories);
        repository.saveCategories(next, actor, now(), comment);
        return next;
    }

    /** 零版本表示显式创建，其余版本表示核对过的当前草稿。 */
    @Transactional
    public ExpensePolicyDraft saveDraft(Actor actor, String key, long expectedRevision, ExpensePolicyDefinition definition, String comment) {
        repository.lock(actor.tenantId());
        var existing = repository.draft(actor.tenantId(), key);
        ExpensePolicyDraft next;
        if (existing.isEmpty()) {
            if (expectedRevision != 0) throw missing();
            next = ExpensePolicyDraft.create(actor.tenantId(), key, definition);
        } else next = existing.get().revise(expectedRevision, definition);
        repository.saveDraft(next, expectedRevision, actor, now(), comment);
        return next;
    }

    /** 当前租户仅有一个完整生效制度集；发布另一业务键时必须确认当前生效修订。 */
    @Transactional
    public Current publish(Actor actor, String key, long expectedDraftRevision, long expectedCategoryRevision, long expectedActiveRevision, String comment) {
        var head = repository.lock(actor.tenantId());
        if (head.activeRevision() != expectedActiveRevision) throw new DomainException("CONCURRENCY_CONFLICT", "Active expense policy changed before publishing");
        var categories = repository.categories(actor.tenantId(), head.categoryRevision());
        categories.requireVersion(expectedCategoryRevision);
        var publication = draft(actor.tenantId(), key).publish(expectedDraftRevision, categories, actor.userId(), now(), comment);
        repository.publish(publication, head);
        return new Current(categories, head.activeRevision() + 1, publication.version());
    }

    /** 当前草稿只在认证租户内查找。 */
    @Transactional(readOnly = true)
    public ExpensePolicyDraft draft(String tenant, String key) { return repository.draft(tenant, key).orElseThrow(ExpenseConfigurationService::missing); }

    /** 发布历史不能用当前草稿补写缺失的版本。 */
    @Transactional(readOnly = true)
    public PublishedExpensePolicy version(String tenant, String key, long version) {
        var draft = draft(tenant, key);
        var value = repository.version(tenant, draft.id(), version).orElseThrow(ExpenseConfigurationService::missing);
        if (!value.key().equals(key)) throw inconsistent();
        return value;
    }

    /** 草稿历史保留内容和修改理由，即使该修订没有发布。 */
    @Transactional(readOnly = true)
    public JdbcExpenseConfigurationRepository.DraftRevision draftRevision(String tenant, String key, long revision) {
        return repository.draftRevision(tenant, draft(tenant, key).id(), revision).orElseThrow(ExpenseConfigurationService::missing);
    }

    /** 类别完整历史采用显式正版本，不能把零版当成已配置历史。 */
    @Transactional(readOnly = true)
    public JdbcExpenseConfigurationRepository.CategoryRevision categoryRevision(String tenant, long revision) {
        return repository.categoryRevision(tenant, revision).orElseThrow(ExpenseConfigurationService::missing);
    }

    /** 稳定业务键分页只返回有界摘要。 */
    @Transactional(readOnly = true)
    public DraftPage list(String tenant, String afterKey, int limit) {
        var found = repository.list(tenant, afterKey, limit); var items = found.stream().limit(limit).toList();
        return new DraftPage(items, found.size() > limit ? items.get(items.size() - 1).key() : null);
    }

    /** 发布版本倒序分页。 */
    @Transactional(readOnly = true)
    public VersionPage versions(String tenant, String key, long before, int limit) {
        var found = repository.versions(tenant, draft(tenant, key).id(), before, limit); var items = found.stream().limit(limit).toList();
        return new VersionPage(items, found.size() > limit ? items.get(items.size() - 1).version() : null);
    }

    /** 类别修订倒序分页。 */
    @Transactional(readOnly = true)
    public CategoryPage categoryVersions(String tenant, long before, int limit) {
        var found = repository.categoryVersions(tenant, before, limit); var items = found.stream().limit(limit).toList();
        return new CategoryPage(items, found.size() > limit ? items.get(items.size() - 1).version() : null);
    }

    /** 生效历史倒序分页；多套草稿共用该租户生效修订。 */
    @Transactional(readOnly = true)
    public ActivationPage activations(String tenant, long before, int limit) {
        var found = repository.activations(tenant, before, limit); var items = found.stream().limit(limit).toList();
        return new ActivationPage(items, found.size() > limit ? items.get(items.size() - 1).revision() : null);
    }

    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MILLIS); }
    private static DomainException missing() { return new DomainException("NOT_FOUND", "Expense configuration or version was not found"); }
    private static DomainException inconsistent() { return new DomainException("EXPENSE_CONFIGURATION_INCONSISTENT", "Active expense policy version is missing or inconsistent"); }

    /**
     * 类别配置和制度生效状态分开展示，未发布时 activePolicy 明确为空。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Current(ExpenseCategoryCatalog categories, long activeRevision, PublishedExpensePolicy activePolicy) { }
    /**
     * 当前制度目录分页。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DraftPage(List<JdbcExpenseConfigurationRepository.DraftSummary> items, String nextAfterKey) { }
    /**
     * 发布历史分页。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record VersionPage(List<JdbcExpenseConfigurationRepository.VersionSummary> items, Long nextBeforeVersion) { }
    /**
     * 类别历史分页。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CategoryPage(List<JdbcExpenseConfigurationRepository.CategorySummary> items, Long nextBeforeVersion) { }
    /**
     * 制度生效历史分页。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ActivationPage(List<JdbcExpenseConfigurationRepository.Activation> items, Long nextBeforeVersion) { }
}
