package io.agentflow.agent;

import java.util.Optional;
import java.util.UUID;

/**
 * 草稿建议持久端口，原始上下文不可覆盖，每个版本追加独立轨迹。
 * @author owlzhangfq@gmail.com
 */
public interface DraftAssistRunRepository {
    /** 与原申请版本核对后保存排队记录。 */
    void create(DraftAssistRun run);
    /** 按乐观版本保存一次领域迁移及对应审计轨迹。 */
    void update(DraftAssistRun run, long expectedVersion);
    /** 运行标识始终与租户共同查询。 */
    Optional<DraftAssistRun> find(String tenant, UUID id);
}
