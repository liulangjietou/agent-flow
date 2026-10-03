package io.agentflow.organization;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 代理关系独立保存；不修改引擎责任人、系统角色和组织任职。
 * @author owlzhangfq@gmail.com
 */
public interface ApprovalProxyRepository {
    /** 在组织目录写锁内新增，范围和原创建事实只写一次。 */
    void insert(String tenantId, ApprovalProxy proxy);
    /** 精确读取当前租户的一条代理依据。 */
    Optional<ApprovalProxy> find(String tenantId, UUID id);
    /** 办理与撤销共用此行锁，锁后重新判断有效期。 */
    Optional<ApprovalProxy> lock(String tenantId, UUID id);
    /** 只写撤销事实，并以原修订防止重复修改。 */
    void revoke(String tenantId, UUID id, long expectedRevision, ApprovalProxy.Revocation revocation);
    /** 同一原审批人、同一版本的未撤销时间段不得重叠；相邻边界允许衔接。 */
    boolean overlaps(String tenantId, UUID definitionId, UUID principalId, Instant startsAt, Instant endsAt);
    /** 管理员分页读取原记录，可按原审批人或代理人过滤，返回 limit+1 条。 */
    List<ApprovalProxy> list(String tenantId, UUID personId, String afterId, int limit);
    /** 同时复核双方当前本地审批资格；这里只返回直接代理，不递归展开他人的代理。 */
    List<ActiveProxy> activeForSubstitute(String tenantId, String subject, Instant now);

    /**
     * 人员主体来自当前租户稳定绑定；任务层仍需复核原生候选、职责和实际账号角色。
     * @author owlzhangfq@gmail.com
     */
    record ActiveProxy(ApprovalProxy proxy, String principalSubject) { }
}
