package io.agentflow.approval;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.approval.service.ApprovalApplicationService;
import io.agentflow.approval.service.ApplicationParticipantPort;
import io.agentflow.approval.service.ApplicationAuditPort;
import io.agentflow.approval.service.ProcessRuntimePort;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 审批申请应用服务，连接认证主体、聚合和基础设施端口。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ApprovalApplicationFacade {
    private final ApplicationRepository repository;
    private final CurrentActor currentActor;
    private final ApprovalApplicationService service;
    private final ApplicationParticipantPort participantPort;
    private final SubmissionRoundRepository rounds;

    /** 创建应用服务。 */
    public ApprovalApplicationFacade(ApplicationRepository repository, ProcessRuntimePort processRuntime,
                                     CurrentActor currentActor, ApplicationParticipantPort participantPort,
                                     SubmissionRoundRepository rounds, ApplicationAuditPort audit) {
        this.repository = repository;
        this.currentActor = currentActor;
        this.service = new ApprovalApplicationService(repository, processRuntime, rounds, audit);
        this.participantPort = participantPort;
        this.rounds = rounds;
    }

    /** 创建申请草稿。 */
    @Transactional
    public Application create(String businessNo, String processKey, long definitionVersion, String title,
                              Map<String, Object> payload) {
        Actor actor = currentActor.actor();
        return service.create(actor.tenantId(), businessNo, processKey, definitionVersion, actor.userId(), title, payload);
    }

    /** 提交申请并启动流程。 */
    @Transactional
    public Application submit(UUID id, long expectedVersion) {
        Actor actor = currentActor.actor();
        requireApplicant(actor, id);
        return service.submit(actor.tenantId(), id, expectedVersion, actor.userId());
    }

    /** 只有发起人可以补正内容，补正及版本更新处于同一事务。 */
    @Transactional
    public Application revise(UUID id, long expectedVersion, String title, Map<String, Object> payload) {
        Actor actor = currentActor.actor();
        requireApplicant(actor, id);
        return service.revise(actor.tenantId(), id, expectedVersion, title, payload, actor.userId());
    }

    /** 仅发起人可以撤回审批中的申请，全部写入与引擎终止共用事务。 */
    @Transactional
    public Application withdraw(UUID id, long expectedVersion, String comment) {
        Actor actor = currentActor.actor();
        requireApplicant(actor, id);
        return service.withdraw(actor.tenantId(), id, expectedVersion, actor.userId(), comment);
    }

    /** 轮次与详情使用同一可见性规则，不因历史接口绕过资源授权。 */
    public List<SubmissionRound> rounds(UUID id) {
        Application application = get(id);
        return rounds.findAll(application.tenantId(), application.id());
    }

    /** 获取申请。 */
    public Application get(UUID id) {
        Actor actor = currentActor.actor();
        Application application = service.get(actor.tenantId(), id);
        if (!isVisible(actor, application)) {
            throw new DomainException("NOT_FOUND", "Application not found");
        }
        return application;
    }

    /** 获取当前租户申请。 */
    public List<Application> list() {
        Actor actor = currentActor.actor();
        return repository.findAll(actor.tenantId()).stream().filter(application -> isVisible(actor, application)).toList();
    }

    private boolean isVisible(Actor actor, Application application) {
        if (actor.hasRole("ADMIN") || application.createdBy().equals(actor.userId())) {
            return true;
        }
        return participantPort.isParticipant(actor.tenantId(), application.id(), actor);
    }

    private void requireApplicant(Actor actor, UUID id) {
        Application application = service.get(actor.tenantId(), id);
        if (!application.createdBy().equals(actor.userId())) {
            throw new DomainException("FORBIDDEN", "Only the applicant can revise, submit or withdraw this application");
        }
    }
}
