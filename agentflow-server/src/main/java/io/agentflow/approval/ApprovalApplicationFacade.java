package io.agentflow.approval;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.expense.ExpenseFormContract;
import io.agentflow.expense.ExpensePlanFormContract;
import io.agentflow.expense.AdvanceRequestFormContract;
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
        return createBound(businessNo, processKey, definitionVersion, title, payload, null);
    }

    /** 业务应用服务创建绑定申请，此入口不直接暴露为 HTTP 请求。 */
    @Transactional
    public Application createBusiness(String businessNo, String processKey, long definitionVersion, String title,
                                      Map<String, Object> payload, BusinessReference reference) {
        return createBound(businessNo, processKey, definitionVersion, title, payload, java.util.Objects.requireNonNull(reference));
    }

    private Application createBound(String businessNo, String processKey, long definitionVersion, String title,
                                     Map<String, Object> payload, BusinessReference reference) {
        Actor actor = currentActor.actor();
        DefinitionModels.DefinitionDraft definition = definitions.lockPublished(actor.tenantId(), processKey, definitionVersion).orElse(null);
        // classpath 内置报销 v1 是唯一没有平台定义行的公开 legacy 模板。
        if (definition == null && !(BUNDLED_LEGACY_PROCESS.equals(processKey) && definitionVersion == BUNDLED_LEGACY_VERSION)) {
            throw new DomainException("PROCESS_DEFINITION_NOT_FOUND", "Published process definition is not available");
        }
        if (definition != null) definition.requireStartEnabled();
        FormSchema formSchema = definition == null ? null : definition.formSchema();
        if (reference == null && (ExpenseFormContract.structured(formSchema) || ExpensePlanFormContract.structured(formSchema)
                || AdvanceRequestFormContract.structured(formSchema))) throw businessEndpointRequired();
        if (reference != null) {
            switch (reference.type()) {
                case EXPENSE -> ExpenseFormContract.requireSchema(formSchema);
                case EXPENSE_PLAN -> ExpensePlanFormContract.requireSchema(formSchema);
                case ADVANCE_REQUEST -> AdvanceRequestFormContract.requireSchema(formSchema);
            }
        }
        String runtimeDefinitionId = processRuntime.resolveDefinition(actor.tenantId(), processKey, definitionVersion, definition == null);
        var application = service.create(actor.tenantId(), businessNo, processKey, definitionVersion, actor.userId(), title, payload,
                formSchema, runtimeDefinitionId, definition == null ? null : definition.notificationTexts(), reference);
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
        return submitBound(id, expectedVersion, initiatorAppointmentId, null);
    }

    /** 结构化提交前由业务服务完成占用、预检和路由投影。 */
    @Transactional
    public Application submitBusiness(UUID id, long expectedVersion, UUID initiatorAppointmentId, BusinessReference reference) {
        return submitBound(id, expectedVersion, initiatorAppointmentId, java.util.Objects.requireNonNull(reference));
    }

    private Application submitBound(UUID id, long expectedVersion, UUID initiatorAppointmentId, BusinessReference reference) {
        Actor actor = currentActor.actor();
        requireWriteBinding(requireApplicant(actor, id), reference);
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
        return reviseBound(id, expectedVersion, title, payload, null);
    }

    /** 结构化草稿与服务端路由字段通过同一申请版本更新。 */
    @Transactional
    public Application reviseBusiness(UUID id, long expectedVersion, String title, Map<String, Object> payload, BusinessReference reference) {
        return reviseBound(id, expectedVersion, title, payload, java.util.Objects.requireNonNull(reference));
    }

    private Application reviseBound(UUID id, long expectedVersion, String title, Map<String, Object> payload, BusinessReference reference) {
        Actor actor = currentActor.actor();
        requireWriteBinding(requireApplicant(actor, id), reference);
        var application = service.revise(actor.tenantId(), id, expectedVersion, title, payload, actor.userId());
        attachments.validate(application, false);
        return application;
    }

    /** 仅发起人可以撤回审批中的申请，全部写入与引擎终止共用事务。 */
    @Transactional
    public Application withdraw(UUID id, long expectedVersion, String comment) {
        return withdrawBound(id, expectedVersion, comment, null);
    }

    /** 业务申请撤回由业务服务核对财务版本，保留本轮资源供补正重提。 */
    @Transactional
    public Application withdrawBusiness(UUID id, long expectedVersion, String comment, BusinessReference reference) {
        return withdrawBound(id, expectedVersion, comment, java.util.Objects.requireNonNull(reference));
    }

    private Application withdrawBound(UUID id, long expectedVersion, String comment, BusinessReference reference) {
        Actor actor = currentActor.actor();
        requireWriteBinding(requireApplicant(actor, id), reference);
        var previous = notifications.beforeWithdrawal(service.get(actor.tenantId(), id));
        Application application = service.withdraw(actor.tenantId(), id, expectedVersion, actor.userId(), comment);
        notifications.withdrawn(application, actor.userId(), previous);
        return application;
    }

    /** 仅申请人可作废未在审批中的单据；申请版本与审计在同一事务内提交。 */
    @Transactional
    public Application cancel(UUID id, long expectedVersion, String comment) {
        return cancelBound(id, expectedVersion, comment, null);
    }

    /** 业务作废需要同事务释放资源，此入口只执行申请自身状态与审计。 */
    @Transactional
    public Application cancelBusiness(UUID id, long expectedVersion, String comment, BusinessReference reference) {
        return cancelBound(id, expectedVersion, comment, java.util.Objects.requireNonNull(reference));
    }

    private Application cancelBound(UUID id, long expectedVersion, String comment, BusinessReference reference) {
        Actor actor = currentActor.actor();
        requireWriteBinding(requireApplicant(actor, id), reference);
        return service.cancel(actor.tenantId(), id, expectedVersion, actor.userId(), comment);
    }

    /** 内部财务结果使用明确系统身份退回，不冒用申请人或某个人工审批任务。 */
    @Transactional
    public Application returnBusiness(String tenant, UUID id, long expectedVersion, BusinessReference reference, String actor, String comment) {
        requireWriteBinding(service.get(tenant, id), java.util.Objects.requireNonNull(reference));
        var application = service.returnToApplicant(tenant, id, expectedVersion, actor, comment);
        notifications.returned(application, actor); return application;
    }

    /** 仅供已经完成业务任务授权的核定服务调用，不向普通修改申请接口开放。 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Application adjustBusiness(UUID id, long expectedVersion, BusinessReference reference, Map<String, Object> payload) {
        var actor = currentActor.actor();
        return service.adjustBusiness(actor.tenantId(), id, expectedVersion, reference, payload);
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

    private static void requireWriteBinding(Application application, BusinessReference reference) {
        if (!java.util.Objects.equals(application.businessReference(), reference)) throw businessEndpointRequired();
    }

    private static DomainException businessEndpointRequired() {
        return new DomainException("USE_BUSINESS_ENDPOINT", "Structured business applications must use their own write endpoint");
    }
}
