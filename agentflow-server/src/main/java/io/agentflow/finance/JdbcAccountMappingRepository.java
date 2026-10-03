package io.agentflow.finance;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 科目配置保留不可变正文和范围投影；写入复用租户费用配置锁，类别与发布不会交叉覆盖。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcAccountMappingRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;

    /** 使用统一 JSON 工具恢复领域约束，SQL 投影另行核对租户、范围与版本。 */
    public JdbcAccountMappingRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 一次 SQL 读取类别与范围指针；未配置时只返回零版，不生成业务数据。 */
    public Head head(String tenant, UUID entity, String currency) {
        return jdbc.query("""
                SELECT e.category_revision,s.active_revision,s.active_mapping_id,s.active_mapping_version
                FROM expense_configuration e LEFT JOIN account_mapping_scope s
                ON s.tenant_id=e.tenant_id AND s.legal_entity_id=? AND s.currency=? WHERE e.tenant_id=?
                """, (row, index) -> new Head(tenant, entity, currency, row.getLong("category_revision"), row.getLong("active_revision"),
                        uuid(row.getString("active_mapping_id")), row.getObject("active_mapping_version", Long.class)), entity.toString(), currency, tenant)
                .stream().findFirst().orElse(new Head(tenant, entity, currency, 0, 0, null, null));
    }

    /** 调用方已锁住租户配置头，同一范围的首个草稿只初始化一次。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void initializeScope(String tenant, UUID entity, String currency) {
        jdbc.update("""
                INSERT INTO account_mapping_scope (tenant_id,legal_entity_id,currency)
                SELECT ?,?,? WHERE NOT EXISTS (SELECT 1 FROM account_mapping_scope WHERE tenant_id=? AND legal_entity_id=? AND currency=?)
                """, tenant, entity.toString(), currency, tenant, entity.toString(), currency);
    }

    /** 当前草稿只在认证租户内读取，JSON 身份与查询投影不一致时拒绝继续使用。 */
    public Optional<AccountMappingDraft> draft(String tenant, String key) {
        return jdbc.query("SELECT * FROM account_mapping_draft WHERE tenant_id=? AND mapping_key=?", (row, index) -> {
            var value = json.read(row.getString("state_json"), AccountMappingDraft.class);
            if (!tenant.equals(value.tenantId()) || !key.equals(value.key()) || !value.id().toString().equals(row.getString("id"))
                    || value.revision() != row.getLong("revision") || value.publishedVersion() != row.getLong("published_version")
                    || value.publishedDraftRevision() != row.getLong("published_draft_revision")) throw inconsistent();
            requireDefinition(row, value.definition()); return value;
        }, tenant, key).stream().findFirst();
    }

    /** 初稿和编辑分别追加内容历史，发布不覆盖当时的修改理由。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveDraft(AccountMappingDraft next, long expectedRevision, Actor actor, Instant now, String comment) {
        var definition = next.definition();
        if (expectedRevision == 0) {
            jdbc.update("""
                    INSERT INTO account_mapping_draft (tenant_id,mapping_key,id,legal_entity_id,currency,name,revision,published_version,published_draft_revision,state_json,updated_by,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """, next.tenantId(), next.key(), next.id().toString(), definition.legalEntityId().toString(), definition.currency(), definition.name(),
                    next.revision(), next.publishedVersion(), next.publishedDraftRevision(), json.write(next), actor.userId(), Timestamp.from(now));
        } else if (jdbc.update("""
                UPDATE account_mapping_draft SET name=?,revision=?,state_json=?,updated_by=?,updated_at=?
                WHERE tenant_id=? AND mapping_key=? AND revision=? AND published_version=?
                """, definition.name(), next.revision(), json.write(next), actor.userId(), Timestamp.from(now), next.tenantId(), next.key(), expectedRevision, next.publishedVersion()) != 1) throw changed();
        jdbc.update("""
                INSERT INTO account_mapping_draft_revision (tenant_id,mapping_id,revision,legal_entity_id,currency,name,definition_json,updated_by,updated_at,comment)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, next.tenantId(), next.id().toString(), next.revision(), definition.legalEntityId().toString(), definition.currency(), definition.name(),
                json.write(definition), actor.userId(), Timestamp.from(now), comment);
    }

    /** 新发布、原草稿游标、生效指针和审计必须共同提交，任一步失败都整体回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(AccountMappingDraft.Publication publication, Head previous) {
        var draft = publication.draft(); var version = publication.version(); var definition = version.definition();
        if (jdbc.update("""
                UPDATE account_mapping_draft SET published_version=?,published_draft_revision=?,state_json=?
                WHERE tenant_id=? AND mapping_key=? AND revision=? AND published_version=?
                """, version.version(), draft.revision(), json.write(draft), draft.tenantId(), draft.key(), draft.revision(), version.version() - 1) != 1) throw changed();
        jdbc.update("""
                INSERT INTO account_mapping_version (tenant_id,mapping_id,version,draft_revision,category_revision,legal_entity_id,currency,name,target_digest,definition_digest,state_json,published_by,published_at,comment)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, version.tenantId(), version.mappingId().toString(), version.version(), version.draftRevision(), version.categoryRevision() == 0 ? null : version.categoryRevision(),
                definition.legalEntityId().toString(), definition.currency(), definition.name(), version.targetDigest(), definition.digest(), json.write(version),
                version.publishedBy(), Timestamp.from(version.publishedAt()), version.comment());
        long activeRevision = Math.incrementExact(previous.activeRevision());
        jdbc.update("""
                INSERT INTO account_mapping_activation (tenant_id,legal_entity_id,currency,revision,mapping_id,mapping_version,activated_by,activated_at,comment)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, version.tenantId(), definition.legalEntityId().toString(), definition.currency(), activeRevision, version.mappingId().toString(), version.version(),
                version.publishedBy(), Timestamp.from(version.publishedAt()), version.comment());
        if (jdbc.update("""
                UPDATE account_mapping_scope SET active_revision=?,active_mapping_id=?,active_mapping_version=?
                WHERE tenant_id=? AND legal_entity_id=? AND currency=? AND active_revision=?
                """, activeRevision, version.mappingId().toString(), version.version(), previous.tenantId(), previous.legalEntityId().toString(), previous.currency(), previous.activeRevision()) != 1) throw changed();
    }

    /** 已发布正文永不按当前草稿补写；范围、摘要和审计投影均须相符。 */
    public Optional<PublishedAccountMapping> version(String tenant, UUID id, long version) {
        return jdbc.query("SELECT * FROM account_mapping_version WHERE tenant_id=? AND mapping_id=? AND version=?", (row, index) -> {
            var value = json.read(row.getString("state_json"), PublishedAccountMapping.class);
            if (!tenant.equals(value.tenantId()) || !id.equals(value.mappingId()) || version != value.version()
                    || value.draftRevision() != row.getLong("draft_revision") || value.categoryRevision() != row.getLong("category_revision")
                    || !value.targetDigest().equals(row.getString("target_digest")) || !value.definition().digest().equals(row.getString("definition_digest"))
                    || !value.publishedBy().equals(row.getString("published_by")) || !value.publishedAt().equals(row.getTimestamp("published_at").toInstant())
                    || !value.comment().equals(row.getString("comment"))) throw inconsistent();
            requireDefinition(row, value.definition()); return value;
        }, tenant, id.toString(), version).stream().findFirst();
    }

    /** 目录可按法人及币种筛选，摘要不包含完整科目代码。 */
    public List<DraftSummary> list(String tenant, UUID entity, String currency, String afterKey, int limit) {
        String filter = (entity == null ? "" : " AND legal_entity_id=?") + (currency == null ? "" : " AND currency=?");
        var args = new java.util.ArrayList<Object>(List.of(tenant, afterKey == null ? "" : afterKey));
        if (entity != null) args.add(entity.toString()); if (currency != null) args.add(currency); args.add(limit + 1);
        return jdbc.query("""
                SELECT id,mapping_key,name,legal_entity_id,currency,revision,published_version,published_draft_revision,updated_by,updated_at
                FROM account_mapping_draft WHERE tenant_id=? AND mapping_key>?
                """ + filter + " ORDER BY mapping_key LIMIT ?",
                (row, index) -> new DraftSummary(uuid(row.getString("id")), row.getString("mapping_key"), row.getString("name"), uuid(row.getString("legal_entity_id")),
                        row.getString("currency"), row.getLong("revision"), row.getLong("published_version"), row.getLong("published_draft_revision"),
                        row.getString("updated_by"), row.getTimestamp("updated_at").toInstant()), args.toArray());
    }

    /** 发布历史采用倒序分页，并保留发布时目标摘要。 */
    public List<VersionSummary> versions(String tenant, UUID id, long before, int limit) {
        return jdbc.query("""
                SELECT version,draft_revision,category_revision,name,target_digest,published_by,published_at,comment
                FROM account_mapping_version WHERE tenant_id=? AND mapping_id=? AND version<? ORDER BY version DESC LIMIT ?
                """, (row, index) -> new VersionSummary(row.getLong("version"), row.getLong("draft_revision"), row.getLong("category_revision"), row.getString("name"),
                        row.getString("target_digest"), row.getString("published_by"), row.getTimestamp("published_at").toInstant(), row.getString("comment")), tenant, id.toString(), before, limit + 1);
    }

    /** 内容历史保留当时的领域定义及操作者。 */
    public Optional<DraftRevision> draftRevision(String tenant, UUID id, long revision) {
        return jdbc.query("SELECT * FROM account_mapping_draft_revision WHERE tenant_id=? AND mapping_id=? AND revision=?", (row, index) -> {
            var definition = json.read(row.getString("definition_json"), AccountMappingDefinition.class); requireDefinition(row, definition);
            return new DraftRevision(id, revision, definition, row.getString("updated_by"), row.getTimestamp("updated_at").toInstant(), row.getString("comment"));
        }, tenant, id.toString(), revision).stream().findFirst();
    }

    /** 同一法人及币种的生效历史能解释业务键切换，不与另一范围共用游标。 */
    public List<Activation> activations(String tenant, UUID entity, String currency, long before, int limit) {
        return jdbc.query("""
                SELECT a.*,d.mapping_key FROM account_mapping_activation a JOIN account_mapping_draft d
                ON d.tenant_id=a.tenant_id AND d.id=a.mapping_id
                WHERE a.tenant_id=? AND a.legal_entity_id=? AND a.currency=? AND a.revision<? ORDER BY a.revision DESC LIMIT ?
                """, (row, index) -> new Activation(row.getLong("revision"), row.getString("mapping_key"), uuid(row.getString("mapping_id")), row.getLong("mapping_version"),
                        row.getString("activated_by"), row.getTimestamp("activated_at").toInstant(), row.getString("comment")), tenant, entity.toString(), currency, before, limit + 1);
    }

    private static void requireDefinition(ResultSet row, AccountMappingDefinition definition) throws SQLException {
        if (!definition.legalEntityId().toString().equals(row.getString("legal_entity_id")) || !definition.currency().equals(row.getString("currency"))
                || !definition.name().equals(row.getString("name"))) throw inconsistent();
    }
    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
    private static DomainException changed() { return new DomainException("CONCURRENCY_CONFLICT", "Account mapping configuration changed before saving"); }
    private static DomainException inconsistent() { return new DomainException("ACCOUNT_MAPPING_CONFIGURATION_INCONSISTENT", "Account mapping history or scope is inconsistent"); }

    /**
     * 当前类别与法人币种范围的生效指针。
     * @author owlzhangfq@gmail.com
     */
    public record Head(String tenantId, UUID legalEntityId, String currency, long categoryRevision, long activeRevision, UUID mappingId, Long mappingVersion) { }
    /**
     * 草稿目录摘要不携带科目代码。
     * @author owlzhangfq@gmail.com
     */
    public record DraftSummary(UUID id, String key, String name, UUID legalEntityId, String currency, long revision, long publishedVersion,
                               long publishedDraftRevision, String updatedBy, Instant updatedAt) { }
    /**
     * 不可变发布的分页摘要。
     * @author owlzhangfq@gmail.com
     */
    public record VersionSummary(long version, long draftRevision, long categoryRevision, String name, String targetDigest, String publishedBy, Instant publishedAt, String comment) { }
    /**
     * 原草稿修订及其修改理由。
     * @author owlzhangfq@gmail.com
     */
    public record DraftRevision(UUID mappingId, long revision, AccountMappingDefinition definition, String updatedBy, Instant updatedAt, String comment) { }
    /**
     * 单一范围的生效变更记录。
     * @author owlzhangfq@gmail.com
     */
    public record Activation(long revision, String key, UUID mappingId, long mappingVersion, String activatedBy, Instant activatedAt, String comment) { }
}
