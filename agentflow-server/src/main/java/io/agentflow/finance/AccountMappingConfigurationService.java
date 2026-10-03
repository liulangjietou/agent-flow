package io.agentflow.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.expense.JdbcExpenseConfigurationRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 科目配置用例协调类别、发布版本与生效指针，事务中不调用外部 ERP。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AccountMappingConfigurationService {
    private final JdbcAccountMappingRepository repository;
    private final JdbcExpenseConfigurationRepository categories;
    private final FinanceGatewayConfiguration gateway;

    /** 复用类别写锁以避免发布与类别停用穿插，财务目标只取服务端配置。 */
    public AccountMappingConfigurationService(JdbcAccountMappingRepository repository, JdbcExpenseConfigurationRepository categories, FinanceGatewayConfiguration gateway) {
        this.repository = repository; this.categories = categories; this.gateway = gateway;
    }

    /** 未配置的范围明确返回零版；历史正文按一次头指针取得，不拼接当前草稿。 */
    @Transactional(readOnly = true)
    public Current current(String tenant, UUID entity, String currency) {
        return current(repository.head(tenant, entity, currency));
    }

    /** 草稿可离线保存，不伪造 ERP 已认可的科目。 */
    @Transactional
    public AccountMappingDraft saveDraft(Actor actor, String key, long expectedRevision, AccountMappingDefinition definition, String comment) {
        categories.lock(actor.tenantId());
        var old = repository.draft(actor.tenantId(), key);
        AccountMappingDraft next;
        if (old.isEmpty()) {
            if (expectedRevision != 0) throw missing();
            next = AccountMappingDraft.create(actor.tenantId(), key, definition);
            repository.initializeScope(actor.tenantId(), definition.legalEntityId(), definition.currency());
        } else next = old.get().revise(expectedRevision, definition);
        repository.saveDraft(next, expectedRevision, actor, now(), comment); return next;
    }

    /** 三版本确认后原子发布；不接受客户端 ERP 地址、目标摘要或新版本号。 */
    @Transactional
    public Current publish(Actor actor, String key, long expectedDraftRevision, long expectedCategoryRevision, long expectedActiveRevision, String comment) {
        var categoryHead = categories.lock(actor.tenantId());
        var catalog = categories.categories(actor.tenantId(), categoryHead.categoryRevision()); catalog.requireVersion(expectedCategoryRevision);
        var draft = draft(actor.tenantId(), key);
        var head = repository.head(actor.tenantId(), draft.definition().legalEntityId(), draft.definition().currency());
        if (head.activeRevision() != expectedActiveRevision) throw new DomainException("CONCURRENCY_CONFLICT", "Active account mapping changed before publishing");
        var target = gateway.destination(actor.tenantId()).orElseThrow(() -> new DomainException("FINANCE_GATEWAY_UNAVAILABLE", "NOT_CONFIGURED"));
        var publication = draft.publish(expectedDraftRevision, catalog, target.digest(actor.tenantId()), actor.userId(), now(), comment);
        repository.publish(publication, head);
        return new Current(head.legalEntityId(), head.currency(), head.categoryRevision(), Math.incrementExact(head.activeRevision()), publication.version());
    }

    /** 管理读取始终限定在认证租户的业务键中。 */
    @Transactional(readOnly = true)
    public AccountMappingDraft draft(String tenant, String key) { return repository.draft(tenant, key).orElseThrow(AccountMappingConfigurationService::missing); }

    /** 发布正文不能通过当前草稿重算，租户和业务键需与存储身份一致。 */
    @Transactional(readOnly = true)
    public PublishedAccountMapping version(String tenant, String key, long version) {
        var draft = draft(tenant, key);
        var value = repository.version(tenant, draft.id(), version).orElseThrow(AccountMappingConfigurationService::missing);
        if (!value.key().equals(key)) throw inconsistent(); return value;
    }

    /** 草稿历史不随着后续发布更新修改人或修改理由。 */
    @Transactional(readOnly = true)
    public JdbcAccountMappingRepository.DraftRevision draftRevision(String tenant, String key, long revision) {
        return repository.draftRevision(tenant, draft(tenant, key).id(), revision).orElseThrow(AccountMappingConfigurationService::missing);
    }

    /** 草稿目录按稳定业务键分页，可缩小到明确法人或币种。 */
    @Transactional(readOnly = true)
    public DraftPage list(String tenant, UUID entity, String currency, String afterKey, int limit) {
        var found = repository.list(tenant, entity, currency, afterKey, limit); var items = found.stream().limit(limit).toList();
        return new DraftPage(items, found.size() > limit ? items.get(items.size() - 1).key() : null);
    }

    /** 已发布版本按同一配置身份倒序分页。 */
    @Transactional(readOnly = true)
    public VersionPage versions(String tenant, String key, long before, int limit) {
        var found = repository.versions(tenant, draft(tenant, key).id(), before, limit); var items = found.stream().limit(limit).toList();
        return new VersionPage(items, found.size() > limit ? items.get(items.size() - 1).version() : null);
    }

    /** 生效历史始终要求明确范围，避免跨法人混用修订号。 */
    @Transactional(readOnly = true)
    public ActivationPage activations(String tenant, UUID entity, String currency, long before, int limit) {
        var found = repository.activations(tenant, entity, currency, before, limit); var items = found.stream().limit(limit).toList();
        return new ActivationPage(items, found.size() > limit ? items.get(items.size() - 1).revision() : null);
    }

    private Current current(JdbcAccountMappingRepository.Head head) {
        var active = head.mappingId() == null ? null : repository.version(head.tenantId(), head.mappingId(), head.mappingVersion()).orElseThrow(AccountMappingConfigurationService::inconsistent);
        if (active != null && (!active.definition().legalEntityId().equals(head.legalEntityId()) || !active.definition().currency().equals(head.currency()))) throw inconsistent();
        return new Current(head.legalEntityId(), head.currency(), head.categoryRevision(), head.activeRevision(), active);
    }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MILLIS); }
    private static DomainException missing() { return new DomainException("NOT_FOUND", "Account mapping configuration or version was not found"); }
    private static DomainException inconsistent() { return new DomainException("ACCOUNT_MAPPING_CONFIGURATION_INCONSISTENT", "Active account mapping history or scope is inconsistent"); }

    /**
     * 当前范围的发布版本与三个待确认的版本来源。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Current(UUID legalEntityId, String currency, long categoryRevision, long activeRevision, PublishedAccountMapping activeMapping) { }
    /**
     * 管理目录分页。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DraftPage(List<JdbcAccountMappingRepository.DraftSummary> items, String nextAfterKey) { }
    /**
     * 发布历史分页。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record VersionPage(List<JdbcAccountMappingRepository.VersionSummary> items, Long nextBeforeVersion) { }
    /**
     * 法人币种范围的生效历史分页。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ActivationPage(List<JdbcAccountMappingRepository.Activation> items, Long nextBeforeVersion) { }
}
