package io.agentflow.agent;

import java.util.Optional;
import java.util.UUID;

/**
 * 摘要运行持久化端口；运行和追加式状态记录必须原子保存，读取始终指定租户。
 * @author owlzhangfq@gmail.com
 */
public interface AssistRunRepository {
    /** 创建待执行运行，并复核申请版本与租户绑定。 */
    void create(AssistRun run);

    /** 保存一次合法状态推进，禁止改变原输入上下文或覆盖其他执行者的结果。 */
    void update(AssistRun run, long expectedVersion);

    /** 读取单个运行；申请可见权限由调用方按实时身份复核。 */
    Optional<AssistRun> find(String tenantId, UUID runId);
}
