package io.agentflow.approval.model;

import io.agentflow.common.DomainException;
import io.agentflow.definition.SubprocessPolicy;
import java.time.Instant;
import java.util.UUID;

/**
 * 一次原生调用对应一份独立子申请；身份和输入映射只追加，不随定义或申请后续变化改写。
 * @author owlzhangfq@gmail.com
 */
public record SubprocessCall(UUID id, String tenantId, UUID parentApplicationId, int parentRoundNo,
                             String parentProcessInstanceId, String parentRuntimeDefinitionId,
                             String nodeId, String nodeName, String activationId,
                             UUID childApplicationId, String childProcessInstanceId,
                             UUID childDefinitionId, String childRuntimeDefinitionId,
                             SubprocessPolicy policy, Instant createdAt) {
    public static final int CHILD_ROUND = 1;

    /** 父子必须有独立申请和实例，父重提通过新轮次建立另一条调用事实。 */
    public SubprocessCall {
        if (parentRoundNo < 1 || parentApplicationId.equals(childApplicationId)
                || parentProcessInstanceId.equals(childProcessInstanceId)) {
            throw new DomainException("SUBPROCESS_IDENTITY_INVALID", "Subprocess parent and child identities must be distinct");
        }
    }
}
