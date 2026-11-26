package io.agentflow.organization;

import io.agentflow.common.SystemRole;
import io.agentflow.common.ErrorCode;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionDraftRepository;
import io.agentflow.definition.DefinitionModels.DraftStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 管理员编排代理与人员、发布版本、目录审计；自身期限及撤销转换由代理实体负责。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ApprovalProxyService {
    private static final String CHANGE_KIND = "APPROVAL_PROXY";
    private final OrganizationRepository organization;
    private final ApprovalProxyRepository proxies;
    private final DefinitionDraftRepository definitions;

    /** 复用目录写锁防止并发建立重叠关系，授权记录与组织审计在同一事务保存。 */
    public ApprovalProxyService(OrganizationRepository organization, ApprovalProxyRepository proxies, DefinitionDraftRepository definitions) {
        this.organization = organization; this.proxies = proxies; this.definitions = definitions;
    }

    /** 明确绑定一个已经发布的版本；停用发起不改变其在审任务，仍允许为其设置代理。 */
    @Transactional
    public ApprovalProxy create(Actor actor, UUID definitionId, UUID principalId, UUID substituteId,
                                Instant startsAt, Instant endsAt, String reason) {
        actor.requireRole(SystemRole.ADMIN);
        return createInternal(actor, definitionId, principalId, substituteId, startsAt, endsAt, reason);
    }

    /** 审批人自助为本人设置审批代理，被代理人严格绑定当前认证主体，不允许代他人建代理。 */
    @Transactional
    public ApprovalProxy createSelf(Actor actor, UUID definitionId, UUID substituteId,
                                    Instant startsAt, Instant endsAt, String reason) {
        actor.requireRole(SystemRole.APPROVER);
        long directoryRevision = organization.lock(actor.tenantId());
        var principal = organization.personBySubject(actor.tenantId(), actor.userId())
                .filter(OrganizationPerson::canApprove)
                .orElseThrow(() -> new DomainException(ErrorCode.APPROVAL_PROXY_PERSON_REQUIRED,
                        "Current user must be an active local approver in this tenant"));
        return createLocked(actor, directoryRevision, definitionId, principal.id(), substituteId, startsAt, endsAt, reason);
    }

    private ApprovalProxy createInternal(Actor actor, UUID definitionId, UUID principalId, UUID substituteId,
                                         Instant startsAt, Instant endsAt, String reason) {
        long directoryRevision = organization.lock(actor.tenantId());
        return createLocked(actor, directoryRevision, definitionId, principalId, substituteId, startsAt, endsAt, reason);
    }

    private ApprovalProxy createLocked(Actor actor, long directoryRevision, UUID definitionId, UUID principalId, UUID substituteId,
                                       Instant startsAt, Instant endsAt, String reason) {
        var value = new ApprovalProxy(UUID.randomUUID(), definitionId, principalId, substituteId,
                startsAt, endsAt, reason, actor.userId(), Instant.now(), 1, null);
        definitions.findById(actor.tenantId(), value.definitionId()).filter(definition -> definition.status() == DraftStatus.PUBLISHED)
                .orElseThrow(() -> new DomainException(ErrorCode.APPROVAL_PROXY_DEFINITION_REQUIRED, "Approval proxy requires a published definition in this tenant"));
        requirePerson(actor.tenantId(), value.principalId());
        requirePerson(actor.tenantId(), value.substituteId());
        if (proxies.overlaps(actor.tenantId(), value.definitionId(), value.principalId(), value.startsAt(), value.endsAt())) {
            throw new DomainException(ErrorCode.APPROVAL_PROXY_OVERLAP, "The principal already has a proxy in this period and definition");
        }
        proxies.insert(actor.tenantId(), value);
        organization.recordChange(actor.tenantId(), directoryRevision, actor.userId(), CHANGE_KIND, value.id(), value, value.createdAt());
        return value;
    }

    /** 撤销与正在使用原代理的事务共用行锁，保持明确先后顺序与具名原因。 */
    @Transactional
    public ApprovalProxy revoke(Actor actor, UUID id, long expectedRevision, String reason) {
        actor.requireRole(SystemRole.ADMIN);
        long directoryRevision = organization.lock(actor.tenantId());
        var original = proxies.lock(actor.tenantId(), id)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Approval proxy not found"));
        return revokeLocked(actor, directoryRevision, original, expectedRevision, reason);
    }

    /** 审批人自助撤销本人名下的审批代理，不能撤销他人的代理授权。 */
    @Transactional
    public ApprovalProxy revokeSelf(Actor actor, UUID id, long expectedRevision, String reason) {
        actor.requireRole(SystemRole.APPROVER);
        long directoryRevision = organization.lock(actor.tenantId());
        var principal = organization.personBySubject(actor.tenantId(), actor.userId())
                .orElseThrow(() -> new DomainException(ErrorCode.FORBIDDEN, "Current user has no local approver record in this tenant"));
        var original = proxies.lock(actor.tenantId(), id)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Approval proxy not found"));
        if (!original.principalId().equals(principal.id())) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Only the principal approver can revoke their own approval proxy");
        }
        return revokeLocked(actor, directoryRevision, original, expectedRevision, reason);
    }

    private ApprovalProxy revokeLocked(Actor actor, long directoryRevision, ApprovalProxy original, long expectedRevision, String reason) {
        var value = original.revoke(expectedRevision, actor.userId(), reason, Instant.now());
        proxies.revoke(actor.tenantId(), original.id(), expectedRevision, value.revocation());
        organization.recordChange(actor.tenantId(), directoryRevision, actor.userId(), CHANGE_KIND, value.id(), value, value.revocation().at());
        return value;
    }

    private void requirePerson(String tenantId, UUID personId) {
        organization.person(tenantId, personId).filter(OrganizationPerson::canApprove)
                .orElseThrow(() -> new DomainException(ErrorCode.APPROVAL_PROXY_PERSON_REQUIRED, "Both proxy participants must be active local approvers in this tenant"));
    }
}
