package io.agentflow.definition;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.agentflow.definition.DefinitionModels.DefinitionDraft;

/**
 * 流程定义上下文的持久化端口，基础设施层负责实现。
 * @author owlzhangfq@gmail.com
 */
public interface DefinitionDraftRepository {
    /** 保存新草稿或当前聚合状态。 */
    DefinitionDraft save(DefinitionDraft draft);

    /** 按租户和标识读取草稿。 */
    Optional<DefinitionDraft> findById(String tenantId, UUID id);

    /** 只返回指定租户和业务版本的已发布快照。 */
    Optional<DefinitionDraft> findPublished(String tenantId, String key, long version);

    /** 发起用例锁定该版本直到事务结束，与停用更新形成明确先后顺序。 */
    Optional<DefinitionDraft> lockPublished(String tenantId, String key, long version);

    /** 查询租户下的流程草稿。 */
    List<DefinitionDraft> findAll(String tenantId, String status);

    /** 返回同一租户、同一流程 key 的下一个已发布版本号。 */
    long nextVersion(String tenantId, String key);
}
