package io.agentflow.definition;

import java.util.List;
import java.util.UUID;

/**
 * 版本治理历史只追加，修订号同时用于有界分页。
 * @author owlzhangfq@gmail.com
 */
public interface DefinitionAvailabilityRepository {
    /** 与版本开关更新共同提交，不覆盖已有事件。 */
    void append(DefinitionAvailabilityChange change);

    /** 返回严格小于指定修订的 limit + 1 条事实，按修订降序。 */
    List<DefinitionAvailabilityChange> history(String tenantId, UUID definitionId, long beforeRevision, int limit);
}
