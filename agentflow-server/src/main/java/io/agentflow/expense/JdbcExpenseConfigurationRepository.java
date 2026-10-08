package io.agentflow.expense;


import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.ExpenseConfigurationRepositoryMapper;
import io.agentflow.mybatis.SqlRows;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 租户配置头负责串行化短写事务，类别、草稿内容、发布物及生效记录均保留不可变历史。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpenseConfigurationRepository {
    private final ExpenseConfigurationRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 复用统一 JSON 编解码，持久化投影只服务于身份检查和有界目录查询。 */
    public JdbcExpenseConfigurationRepository(
            ExpenseConfigurationRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 未配置租户只返回零版，不因读取而创建企业规则。 */
    public Head head(String tenant) {
        return heads(tenant, false).stream().findFirst().orElse(new Head(tenant, 0, 0, null, null));
    }

    /** 首次创建冲突直接回滚，由调用者刷新后重试，不能在 PostgreSQL 已失败的事务中继续查询。 */
    public Head lock(String tenant) {
        if (heads(tenant, false).isEmpty()) {
            try {
                sqlMapper.lock(tenant);
            } catch (DuplicateKeyException concurrent) {
                throw changed();
            }
        }
        return heads(tenant, true).get(0);
    }

    private List<Head> heads(String tenant, boolean lock) {
        return SqlRows.map(
                sqlMapper.headsQuery(lock, new Object[] {tenant}),
                row ->
                        new Head(
                                tenant,
                                row.getLong("category_revision"),
                                row.getLong("active_revision"),
                                uuid(row.getString("active_policy_id")),
                                row.getObject("active_policy_version", Long.class)));
    }

    /** 版本零仅表示空配置，其余版本必须有完整历史，不把损坏记录降级为空。 */
    public ExpenseCategoryCatalog categories(String tenant, long revision) {
        if (revision == 0) return new ExpenseCategoryCatalog(tenant, 0, List.of());
        return categoryRevision(tenant, revision).orElseThrow(JdbcExpenseConfigurationRepository::missing).catalog();
    }

    /** 保存类别及审计理由；旧版本没有 UPDATE 或 DELETE 路径。 */
    @Transactional
    public void saveCategories(
            ExpenseCategoryCatalog next, Actor actor, Instant now, String comment) {
        sqlMapper.saveCategories(
                next.tenantId(),
                next.version(),
                next.categories().size(),
                json.write(next),
                actor.userId(),
                Timestamp.from(now),
                comment);
        if (sqlMapper.saveCategories2(next.version(), next.tenantId(), next.version() - 1) != 1)
            throw changed();
    }

    /** 当前草稿读取与租户、业务键和数据库版本投影相互核对。 */
    public Optional<ExpensePolicyDraft> draft(String tenant, String key) {
        return SqlRows.map(
                        sqlMapper.draft(tenant, key),
                        row -> {
                            var value =
                                    json.read(
                                            row.getString("state_json"), ExpensePolicyDraft.class);
                            if (!value.tenantId().equals(tenant)
                                    || !value.key().equals(key)
                                    || !value.id().toString().equals(row.getString("id"))
                                    || value.revision() != row.getLong("revision")
                                    || value.publishedVersion() != row.getLong("published_version")
                                    || value.publishedDraftRevision()
                                            != row.getLong("published_draft_revision")
                                    || !value.definition().name().equals(row.getString("name")))
                                throw inconsistent();
                            return value;
                        })
                .stream()
                .findFirst();
    }

    /** 初稿和编辑都保存当时完整内容及操作者，发布不会伪造新的内容修订。 */
    @Transactional
    public void saveDraft(
            ExpensePolicyDraft next,
            long expectedRevision,
            Actor actor,
            Instant now,
            String comment) {
        if (expectedRevision == 0) {
            sqlMapper.saveDraft(
                    next.tenantId(),
                    next.key(),
                    next.id().toString(),
                    next.definition().name(),
                    next.revision(),
                    next.publishedVersion(),
                    next.publishedDraftRevision(),
                    json.write(next),
                    actor.userId(),
                    Timestamp.from(now));
        } else if (sqlMapper.saveDraft2(
                        next.definition().name(),
                        next.revision(),
                        json.write(next),
                        actor.userId(),
                        Timestamp.from(now),
                        next.tenantId(),
                        next.key(),
                        expectedRevision,
                        next.publishedVersion())
                != 1) throw changed();
        sqlMapper.saveDraft3(
                next.tenantId(),
                next.id().toString(),
                next.revision(),
                next.definition().name(),
                json.write(next.definition()),
                actor.userId(),
                Timestamp.from(now),
                comment);
    }

    /** 发布、切换当前制度和审计同事务提交，不允许另一位管理员的发布被静默覆盖。 */
    @Transactional
    public void publish(ExpensePolicyDraft.Publication publication, Head previous) {
        var draft = publication.draft();
        var version = publication.version();
        if (sqlMapper.publish(
                        version.version(),
                        version.draftRevision(),
                        json.write(draft),
                        draft.tenantId(),
                        draft.id().toString(),
                        draft.revision(),
                        version.version() - 1)
                != 1) throw changed();
        sqlMapper.publish2(
                version.tenantId(),
                version.policyId().toString(),
                version.version(),
                version.draftRevision(),
                version.categoryRevision(),
                version.definition().name(),
                json.write(version),
                version.publishedBy(),
                Timestamp.from(version.publishedAt()),
                version.comment());
        sqlMapper.publish3(
                version.tenantId(),
                previous.activeRevision() + 1,
                version.policyId().toString(),
                version.version(),
                version.publishedBy(),
                Timestamp.from(version.publishedAt()),
                version.comment());
        if (sqlMapper.publish4(
                        previous.activeRevision() + 1,
                        version.policyId().toString(),
                        version.version(),
                        version.tenantId(),
                        previous.activeRevision(),
                        previous.categoryRevision())
                != 1) throw changed();
    }

    /** 历史发布物完整读取，验证元数据以防止引用其他租户或错误草稿。 */
    public Optional<PublishedExpensePolicy> version(String tenant, UUID policyId, long version) {
        return SqlRows.map(
                        sqlMapper.version(tenant, policyId.toString(), version),
                        row -> {
                            var value =
                                    json.read(
                                            row.getString("state_json"),
                                            PublishedExpensePolicy.class);
                            if (!value.tenantId().equals(tenant)
                                    || !value.policyId().equals(policyId)
                                    || value.version() != version
                                    || value.draftRevision() != row.getLong("draft_revision")
                                    || value.categoryRevision() != row.getLong("category_revision")
                                    || !value.definition().name().equals(row.getString("name"))
                                    || !value.publishedBy().equals(row.getString("published_by"))
                                    || !value.publishedAt()
                                            .equals(row.getTimestamp("published_at").toInstant())
                                    || !value.comment().equals(row.getString("comment")))
                                throw inconsistent();
                            return value;
                        })
                .stream()
                .findFirst();
    }

    /** 目录不复制完整规则，稳定业务键支持逐页加载。 */
    public List<DraftSummary> list(String tenant, String afterKey, int limit) {
        return SqlRows.map(
                sqlMapper.list(tenant, afterKey == null ? "" : afterKey, limit + 1),
                row ->
                        new DraftSummary(
                                uuid(row.getString("id")),
                                row.getString("policy_key"),
                                row.getString("name"),
                                row.getLong("revision"),
                                row.getLong("published_version"),
                                row.getLong("published_draft_revision"),
                                row.getString("updated_by"),
                                row.getTimestamp("updated_at").toInstant()));
    }

    /** 发布历史只取摘要，分页时仍固定在一个制度身份内。 */
    public List<VersionSummary> versions(String tenant, UUID id, long before, int limit) {
        return SqlRows.map(
                sqlMapper.versions(tenant, id.toString(), before, limit + 1),
                row ->
                        new VersionSummary(
                                row.getLong("version"),
                                row.getLong("draft_revision"),
                                row.getLong("category_revision"),
                                row.getString("name"),
                                row.getString("published_by"),
                                row.getTimestamp("published_at").toInstant(),
                                row.getString("comment")));
    }

    /** 类别历史摘要包含当时操作者和理由。 */
    public List<CategorySummary> categoryVersions(String tenant, long before, int limit) {
        return SqlRows.map(
                sqlMapper.categoryVersions(tenant, before, limit + 1),
                row ->
                        new CategorySummary(
                                row.getLong("revision"),
                                row.getInt("category_count"),
                                row.getString("updated_by"),
                                row.getTimestamp("updated_at").toInstant(),
                                row.getString("comment")));
    }

    /** 单版类别连同审计事实读取；不存在与跨租户返回相同的空结果。 */
    public Optional<CategoryRevision> categoryRevision(String tenant, long revision) {
        return SqlRows.map(
                        sqlMapper.categoryRevision(tenant, revision),
                        row -> {
                            var value =
                                    json.read(
                                            row.getString("state_json"),
                                            ExpenseCategoryCatalog.class);
                            if (!value.tenantId().equals(tenant)
                                    || value.version() != revision
                                    || value.categories().size() != row.getInt("category_count"))
                                throw inconsistent();
                            return new CategoryRevision(
                                    value,
                                    row.getString("updated_by"),
                                    row.getTimestamp("updated_at").toInstant(),
                                    row.getString("comment"));
                        })
                .stream()
                .findFirst();
    }

    /** 内容修订历史不根据当前发布游标补写当时状态。 */
    public Optional<DraftRevision> draftRevision(String tenant, UUID id, long revision) {
        return SqlRows.map(
                        sqlMapper.draftRevision(tenant, id.toString(), revision),
                        row -> {
                            var definition =
                                    json.read(
                                            row.getString("definition_json"),
                                            ExpensePolicyDefinition.class);
                            if (!definition.name().equals(row.getString("name")))
                                throw inconsistent();
                            return new DraftRevision(
                                    id,
                                    revision,
                                    definition,
                                    row.getString("updated_by"),
                                    row.getTimestamp("updated_at").toInstant(),
                                    row.getString("comment"));
                        })
                .stream()
                .findFirst();
    }

    /** 生效记录有界倒序读取，用于解释多个制度键之间的明确切换。 */
    public List<Activation> activations(String tenant, long before, int limit) {
        return SqlRows.map(
                sqlMapper.activations(tenant, before, limit + 1),
                row ->
                        new Activation(
                                row.getLong("revision"),
                                row.getString("policy_key"),
                                uuid(row.getString("policy_id")),
                                row.getLong("policy_version"),
                                row.getString("activated_by"),
                                row.getTimestamp("activated_at").toInstant(),
                                row.getString("comment")));
    }

    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }

    private static DomainException changed() { return new DomainException("CONCURRENCY_CONFLICT", "Expense configuration changed before saving"); }

    private static DomainException missing() { return new DomainException("NOT_FOUND", "Expense configuration version was not found"); }

    private static DomainException inconsistent() { return new DomainException("EXPENSE_CONFIGURATION_INCONSISTENT", "Expense configuration history is inconsistent"); }

    /**
     * 一次读取得到当前类别与发布指针，再按不可变版本读正文。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Head(
            String tenantId,
            long categoryRevision,
            long activeRevision,
            UUID policyId,
            Long policyVersion) {}

    /**
     * 当前制度的轻量目录投影。
     *
     * @author owlzhangfq@gmail.com
     */
    public record DraftSummary(
            UUID id,
            String key,
            String name,
            long revision,
            long publishedVersion,
            long publishedDraftRevision,
            String updatedBy,
            Instant updatedAt) {}

    /**
     * 不可变发布摘要。
     *
     * @author owlzhangfq@gmail.com
     */
    public record VersionSummary(
            long version,
            long draftRevision,
            long categoryRevision,
            String name,
            String publishedBy,
            Instant publishedAt,
            String comment) {}

    /**
     * 类别修订摘要。
     *
     * @author owlzhangfq@gmail.com
     */
    public record CategorySummary(
            long version, int categoryCount, String updatedBy, Instant updatedAt, String comment) {}

    /**
     * 类别历史正文及审计信息。
     *
     * @author owlzhangfq@gmail.com
     */
    public record CategoryRevision(
            ExpenseCategoryCatalog catalog, String updatedBy, Instant updatedAt, String comment) {}

    /**
     * 草稿内容历史及审计信息。
     *
     * @author owlzhangfq@gmail.com
     */
    public record DraftRevision(
            UUID policyId,
            long revision,
            ExpensePolicyDefinition definition,
            String updatedBy,
            Instant updatedAt,
            String comment) {}

    /**
     * 一次明确发布并生效的操作者、来源及原因。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Activation(
            long revision,
            String key,
            UUID policyId,
            long policyVersion,
            String activatedBy,
            Instant activatedAt,
            String comment) {}
}
