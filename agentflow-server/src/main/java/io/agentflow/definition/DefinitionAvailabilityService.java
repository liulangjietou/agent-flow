package io.agentflow.definition;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static io.agentflow.definition.DefinitionModels.DefinitionDraft;

/**
 * 版本治理用例只编排聚合和追加记录，不挂起运行中的引擎实例。
 * @author owlzhangfq@gmail.com
 */
@Service
public class DefinitionAvailabilityService {
    private final DefinitionDraftRepository definitions;
    private final DefinitionAvailabilityRepository changes;

    /** 治理状态与审计使用同一业务事务。 */
    public DefinitionAvailabilityService(DefinitionDraftRepository definitions, DefinitionAvailabilityRepository changes) {
        this.definitions = definitions; this.changes = changes;
    }

    /** 保留发布版本和内容，以修订号阻止过期页面覆盖新状态。 */
    @Transactional
    public DefinitionDraft change(Actor actor, UUID id, long expectedRevision, boolean enabled, String reason) {
        DefinitionDraft definition = get(actor.tenantId(), id);
        var change = DefinitionAvailabilityChange.prepare(definition, actor, enabled, reason, Instant.now());
        definition.changeAvailability(expectedRevision, enabled);
        definitions.save(definition);
        changes.append(change);
        return definition;
    }

    /** 读取已发布版本的治理历史，旧版本没有操作时返回空记录。 */
    public List<DefinitionAvailabilityChange> history(String tenantId, UUID id, long beforeRevision, int limit) {
        DefinitionDraft definition = get(tenantId, id);
        if (definition.status() != DefinitionModels.DraftStatus.PUBLISHED) {
            throw new DomainException("DEFINITION_NOT_PUBLISHED", "Definition has not been published");
        }
        return changes.history(tenantId, id, beforeRevision, limit);
    }

    private DefinitionDraft get(String tenantId, UUID id) {
        return definitions.findById(tenantId, id)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Process definition not found"));
    }
}
