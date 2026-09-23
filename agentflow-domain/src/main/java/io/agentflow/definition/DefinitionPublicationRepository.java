package io.agentflow.definition;

import java.util.Optional;
import java.util.UUID;

/**
 * 发布事实仓储，只允许新增和按租户读取，不提供修改或补造历史记录的操作。
 * @author owlzhangfq@gmail.com
 */
public interface DefinitionPublicationRepository {
    /** 与定义状态和引擎部署在同一事务内新增发布事实。 */
    void save(DefinitionPublication publication);

    /** 读取发布事实；历史版本可能没有完整记录。 */
    Optional<DefinitionPublication> findByDefinition(String tenantId, UUID definitionId);
}
