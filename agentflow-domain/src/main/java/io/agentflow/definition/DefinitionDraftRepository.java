package io.agentflow.definition;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.agentflow.definition.DefinitionModels.DefinitionDraft;

/** 流程定义上下文的持久化端口，基础设施层负责实现。 */
public interface DefinitionDraftRepository {
    /** 保存新草稿或当前聚合状态。 */
    DefinitionDraft save(DefinitionDraft draft);

    /** 按租户和标识读取草稿。 */
    Optional<DefinitionDraft> findById(String tenantId, UUID id);

    /** 查询租户下的流程草稿。 */
    List<DefinitionDraft> findAll(String tenantId, String status);
}
