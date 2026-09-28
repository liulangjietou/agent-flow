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
import io.agentflow.definition.DefinitionDraftRepository;
import io.agentflow.definition.DefinitionModels;
import io.agentflow.form.FormSchema;
import io.agentflow.notification.ApprovalNotificationService;
import io.agentflow.organization.OrganizationInitiatorDirectory;
import io.agentflow.attachment.AttachmentReferenceService;
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
    private static final String BUNDLED_LEGACY_PROCESS = "expense-reimbursement";
    private static final long BUNDLED_LEGACY_VERSION = 1L;
    private final ApplicationRepository repository;
    private final CurrentActor currentActor;
    private final ApprovalApplicationService service;
    private final List<ApplicationParticipantPort> participantPorts;
    private final SubmissionRoundRepository rounds;
    private final DefinitionDraftRepository definitions;
    private final ProcessRuntimePort processRuntime;
    private final ApprovalNotificationService notifications;
    private final OrganizationInitiatorDirectory initiators;
    private final AttachmentReferenceService attachments;

    /** 创建应用服务。 */
    public ApprovalApplicationFacade(ApplicationRepository repository, ProcessRuntimePort processRuntime,
                                     CurrentActor currentActor, List<ApplicationParticipantPort> participantPorts,
                                     SubmissionRoundRepository rounds, ApplicationAuditPort audit, DefinitionDraftRepository definitions,
                                     ApprovalNotificationService notifications, OrganizationInitiatorDirectory initiators, AttachmentReferenceService attachments) {
        this.repository = repository;
        this.currentActor = currentActor;
        this.service = new ApprovalApplicationService(repository, processRuntime, rounds, audit);
        this.participantPorts = List.copyOf(participantPorts);
        this.rounds = rounds;
        this.definitions = definitions;
        this.processRuntime = processRuntime;
        this.notifications = notifications;
        this.initiators = initiators;
        this.attachments = attachments;
    }

    /** 创建申请草稿。 */
    @Transactional
    public Application create(String businessNo, String processKey, long definitionVersion, String title,
                              Map<String, Object> payload) {
        Actor actor = currentActor.actor();
        DefinitionModels.DefinitionDraft definition = definitions.lockPublished(actor.tenantId(), processKey, definitionVersion).orElse(null);
        // classpath 内置报销 v1 是唯一没有平台定义行的公开 legacy 模板。
        if (definition == null && !(BUNDLED_LEGACY_PROCESS.equals(processKey) && definitionVersion == BUNDLED_LEGACY_VERSION)) {
            throw new DomainException("PROCESS_DEFINITION_NOT_FOUND", "Published process definition is not available");
        }
        if (definition != null) definition.requireStartEnabled();
        FormSchema formSchema = definition == null ? null : definition.formSchema();
        String runtimeDefinitionId = processRuntime.resolveDefinition(actor.tenantId(), processKey, definitionVersion, definition == null);
        var application = service.create(actor.tenantId(), businessNo, processKey, definitionVersion, actor.userId(), title, payload,
                formSchema, runtimeDefinitionId, definition == null ? null : definition.notificationTexts());
        attachments.validate(application, false);
        return application;
    }

    /** 提交申请并启动流程。 */
    @Transactional
    public Application submit(UUID id, long expectedVersion) {
        return submit(id, expectedVersion, null);
    }

    /** 选择任职只允许当前申请人，内容在当前提交轮次内冻结。 */
    @Transactional
    public Application submit(UUID id, long expectedVersion, UUID initiatorAppointmentId) {
        Actor actor = currentActor.actor();
        requireApplicant(actor, id);
        var context = initiators.snapshot(actor, initiatorAppointmentId);
        Application application = service.submit(actor.tenantId(), id, expectedVersion, actor.userId(), context);
        // 校验实际提交的聚合，避免二次读取跨版本；失败时申请、引擎及轮次一并回滚。
        attachments.validate(application, true);
        attachments.freeze(application);
        notifications.submitted(application, actor.userId());
        return application;
    }

    /** 只有发起人可以补正内容，补正及版本更新处于同一事务。 */
    @Transactional
    public Application revise(UUID id, long expectedVersion, String title, Map<String, Object> payload) {
        Actor actor = currentActor.actor();
        requireApplicant(actor, id);
        var application = service.revise(actor.tenantId(), id, expectedVersion, title, payload, actor.userId());
        attachments.validate(application, false);
        return application;
    }

    /** 仅发起人可以撤回审批中的申请，全部写入与引擎终止共用事务。 */
    @Transactional
    public Application withdraw(UUID id, long expectedVersion, String comment) {
        Actor actor = currentActor.actor();
        requireApplicant(actor, id);
        var previous = notifications.beforeWithdrawal(service.get(actor.tenantId(), id));
        Application application = service.withdraw(actor.tenantId(), id, expectedVersion, actor.userId(), comment);
        notifications.withdrawn(application, actor.userId(), previous);
        return application;
    }

    /** 仅申请人可作废未在审批中的单据；申请版本与审计在同一事务内提交。 */
    @Transactional
    public Application cancel(UUID id, long expectedVersion, String comment) {
        Actor actor = currentActor.actor();
        requireApplicant(actor, id);
        return service.cancel(actor.tenantId(), id, expectedVersion, actor.userId(), comment);
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
        return participantPorts.stream().anyMatch(port -> port.isParticipant(actor.tenantId(), application.id(), actor));
    }

    private Application requireApplicant(Actor actor, UUID id) {
        Application application = service.get(actor.tenantId(), id);
        if (!application.createdBy().equals(actor.userId())) {
            throw new DomainException("FORBIDDEN", "Only the applicant can revise, submit, withdraw or cancel this application");
        }
        return application;
    }

    /** 附件写入复用申请人的资源授权，状态与版本由申请聚合继续判断。 */
    public Application requireApplicant(UUID id) {
        return requireApplicant(currentActor.actor(), id);
    }
}
