package io.agentflow.organization;

import java.time.Instant;
import java.util.UUID;

/**
 * 实际办理时已经复核的原授权快照；后续撤销不覆盖已发生的使用事实。
 * 实际办理人由同一任务审计或业务记录的 actor 保存，不复制管理员创建原因。
 * @author owlzhangfq@gmail.com
 */
public record ApprovalProxyUse(UUID proxyId, long revision, UUID definitionId, UUID principalId,
                               String principal, UUID substituteId, Instant startsAt, Instant endsAt,
                               Instant authorizedAt) {
    /** 在锁后当前资格和任务范围通过后构造，不由客户端传入这些依据。 */
    public static ApprovalProxyUse authorized(ApprovalProxy proxy, String principal, Instant authorizedAt) {
        return new ApprovalProxyUse(proxy.id(), proxy.revision(), proxy.definitionId(), proxy.principalId(),
                principal, proxy.substituteId(), proxy.startsAt(), proxy.endsAt(), authorizedAt);
    }
}
