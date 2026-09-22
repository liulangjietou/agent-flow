package io.agentflow.approval;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.service.ApprovalApplicationService;
import io.agentflow.approval.service.ApplicationParticipantPort;
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

    /** 创建应用服务。 */
    public ApprovalApplicationFacade(ApplicationRepository repository, ProcessRuntimePort processRuntime,
                                     CurrentActor currentActor, ApplicationParticipantPort participantPort) {
        this.repository = repository;
        this.currentActor = currentActor;
        this.service = new ApprovalApplicationService(repository, processRuntime);
        this.participantPort = participantPort;
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
        Application existing = service.get(actor.tenantId(), id);
        if (!existing.createdBy().equals(actor.userId())) {
            throw new DomainException("FORBIDDEN", "Only the applicant can submit this application");
        }
        return service.submit(actor.tenantId(), id, expectedVersion);
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
}
