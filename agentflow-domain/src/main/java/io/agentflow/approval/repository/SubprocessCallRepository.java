package io.agentflow.approval.repository;

import io.agentflow.approval.model.SubprocessCall;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 持久父子调用索引，审批编排通过实际关系查询祖先和后代，不从流程名称猜测关联。
 * @author owlzhangfq@gmail.com
 */
public interface SubprocessCallRepository {
    /** 与子申请、子轮次及原生调用共用一个事务，重复激活拒绝追加第二份关系。 */
    void append(SubprocessCall call);

    /** 每份子申请只属于一个父调用。 */
    Optional<SubprocessCall> findByChild(String tenantId, UUID childApplicationId);

    /** 用原生执行身份核对重入，不将节点名当作调用幂等标识。 */
    Optional<SubprocessCall> findByActivation(String tenantId, String parentInstanceId, String activationId);

    /** 查询指定父轮次的直接后代，旧轮次不混入当前办理。 */
    List<SubprocessCall> findByParentRound(String tenantId, UUID parentApplicationId, int roundNo);

    /** 按激活时间和编号稳定分页；游标必须属于同一租户、父申请和原轮次。 */
    List<SubprocessCall> pageByParentRound(String tenantId, UUID parentApplicationId, int roundNo, UUID afterId, int limit);
}
