package io.agentflow.approval.service;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import io.agentflow.notification.NotificationTexts;
import io.agentflow.organization.InitiatorContext;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 审批申请用例编排服务，负责聚合、运行时和仓储之间的事务协作。
 * @author owlzhangfq@gmail.com
 */
public class ApprovalApplicationService {
    private final ApplicationRepository repository;
    private final ProcessRuntimePort processRuntime;
    private final SubmissionRoundRepository rounds;
    private final ApplicationAuditPort audit;

    /** 创建应用服务。 */
    public ApprovalApplicationService(ApplicationRepository repository, ProcessRuntimePort processRuntime,
                                      SubmissionRoundRepository rounds, ApplicationAuditPort audit) {
        this.repository = repository;
        this.processRuntime = processRuntime;
        this.rounds = rounds;
        this.audit = audit;
    }

    /** 创建草稿并拒绝租户外重复业务单号。 */
    public Application create(String tenantId, String businessNo, String processKey, long definitionVersion,
                              String userId, String title, Map<String, Object> payload) {
        return create(tenantId, businessNo, processKey, definitionVersion, userId, title, payload, null);
    }

    /** 创建绑定表单快照的申请，定义查询由上层跨聚合编排完成。 */
    public Application create(String tenantId, String businessNo, String processKey, long definitionVersion,
                              String userId, String title, Map<String, Object> payload, FormSchema formSchema) {
        return create(tenantId, businessNo, processKey, definitionVersion, userId, title, payload, formSchema, null);
    }

    /** 实际引擎定义作为不透明标识保存在申请内，不把引擎类型引入领域。 */
    public Application create(String tenantId, String businessNo, String processKey, long definitionVersion,
                              String userId, String title, Map<String, Object> payload, FormSchema formSchema, String runtimeDefinitionId) {
        return create(tenantId, businessNo, processKey, definitionVersion, userId, title, payload, formSchema,
                runtimeDefinitionId, NotificationTexts.EMPTY);
    }

    /** 通知文案来自上层已解析的同一发布定义，与申请一同保存。 */
    public Application create(String tenantId, String businessNo, String processKey, long definitionVersion,
                              String userId, String title, Map<String, Object> payload, FormSchema formSchema,
                              String runtimeDefinitionId, NotificationTexts notificationTexts) {
        return create(tenantId, businessNo, processKey, definitionVersion, userId, title, payload, formSchema,
                runtimeDefinitionId, notificationTexts, null);
    }

    /** 结构化业务绑定由相应应用服务产生，与创建审计在同一事务保存。 */
    public Application create(String tenantId, String businessNo, String processKey, long definitionVersion,
                              String userId, String title, Map<String, Object> payload, FormSchema formSchema,
                              String runtimeDefinitionId, NotificationTexts notificationTexts, BusinessReference businessReference) {
        if (repository.findByBusinessNo(tenantId, businessNo).isPresent()) {
            throw new DomainException("BUSINESS_NO_EXISTS", "Business number already exists");
        }
        Application application = businessReference == null
                ? Application.draft(UUID.randomUUID(), tenantId, businessNo, processKey, definitionVersion, userId, title, payload,
                    formSchema, runtimeDefinitionId, notificationTexts)
                : Application.draftBusiness(UUID.randomUUID(), tenantId, businessNo, processKey, definitionVersion, userId, title, payload,
                    formSchema, runtimeDefinitionId, notificationTexts, businessReference);
        repository.save(application);
        recordApplicationOperation(application, userId, ApplicationAuditPort.Action.CREATE, null, null, null);
        return application;
    }

    /** 提交申请；流程启动失败时由上层事务回滚本地状态。 */
    public Application submit(String tenantId, UUID id, long expectedVersion, String submittedBy) {
        return submit(tenantId, id, expectedVersion, submittedBy, null);
    }

    /** 把服务端确认的任职同时交给运行端口和轮次仓储，不把它混入用户表单。 */
    public Application submit(String tenantId, UUID id, long expectedVersion, String submittedBy, InitiatorContext initiatorContext) {
        Application application = repository.findById(tenantId, id)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Application not found"));
        ApplicationStatus previousStatus = application.status();
        SubmissionRound previousRound = application.runtimeDefinitionId() == null
                ? rounds.findByRound(tenantId, id, application.roundNo()).orElse(null) : null;
        application.submit(expectedVersion);
        ProcessRuntimePort.StartedProcess started = processRuntime.start(new ProcessRuntimePort.StartProcessCommand(tenantId, id, application.processKey(),
                application.definitionVersion(), application.roundNo(), application.businessNo(), application.payload(), application.formSchema(),
                application.runtimeDefinitionId(), previousRound == null ? null : previousRound.processInstanceId(), initiatorContext));
        repository.update(application, expectedVersion);
        rounds.append(SubmissionRound.submitted(application, started.processInstanceId(), submittedBy, Instant.now(), initiatorContext, started.risk()));
        recordApplicationOperation(application, submittedBy, ApplicationAuditPort.Action.SUBMIT, previousStatus,
                started.processInstanceId(), null);
        return application;
    }

    /** 补正申请内容并使用显式版本保存，历史轮次快照不受影响。 */
    public Application revise(String tenantId, UUID id, long expectedVersion, String title, Map<String, Object> payload,
                              String revisedBy) {
        Application application = get(tenantId, id);
        ApplicationStatus previousStatus = application.status();
        application.revise(expectedVersion, title, payload);
        repository.update(application, expectedVersion);
        recordApplicationOperation(application, revisedBy, ApplicationAuditPort.Action.REVISE, previousStatus, null, null);
        return application;
    }

    /** 撤回当前申请轮次；领域状态、实际实例、轮次结论和申请审计由上层事务共同提交。 */
    public Application withdraw(String tenantId, UUID id, long expectedVersion, String actor, String comment) {
        Application application = get(tenantId, id);
        ApplicationStatus previousStatus = application.status();
        application.withdraw(expectedVersion);
        SubmissionRound round = rounds.findByRound(tenantId, id, application.roundNo()).orElse(null);
        if (round != null && (round.status() != SubmissionRound.Status.IN_APPROVAL
                || round.definitionVersion() != application.definitionVersion())) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Submission round no longer matches the active application");
        }
        String instanceId = processRuntime.withdraw(new ProcessRuntimePort.WithdrawProcessCommand(
                tenantId, id, application.roundNo(), round == null ? null : round.processInstanceId(),
                "WITHDRAW by " + actor));
        repository.update(application, expectedVersion);
        rounds.complete(tenantId, id, application.roundNo(), instanceId,
                SubmissionRound.Status.WITHDRAWN, comment, actor, Instant.now());
        recordApplicationOperation(application, actor, ApplicationAuditPort.Action.WITHDRAW, previousStatus, instanceId, comment);
        return application;
    }

    /** 作废只改变申请状态，已有审批结论与轮次快照保持原样，不新建或终止流程。 */
    public Application cancel(String tenantId, UUID id, long expectedVersion, String actor, String comment) {
        Application application = get(tenantId, id);
        ApplicationStatus previousStatus = application.status();
        application.cancel(expectedVersion);
        repository.update(application, expectedVersion);
        recordApplicationOperation(application, actor, ApplicationAuditPort.Action.CANCEL, previousStatus, null, comment);
        return application;
    }

    /** 系统业务结论退回当前轮次，仍需精确终止对应实例并保存真实系统操作者。 */
    public Application returnToApplicant(String tenantId, UUID id, long expectedVersion, String actor, String comment) {
        Application application = get(tenantId, id); ApplicationStatus previousStatus = application.status();
        application.returnToApplicant(expectedVersion);
        var round = rounds.findByRound(tenantId, id, application.roundNo()).orElseThrow(
                () -> new DomainException("CONCURRENCY_CONFLICT", "Active submission round not found"));
        if (round.status() != SubmissionRound.Status.IN_APPROVAL || round.definitionVersion() != application.definitionVersion()) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Submission round no longer matches the active application");
        }
        String instanceId = processRuntime.withdraw(new ProcessRuntimePort.WithdrawProcessCommand(tenantId, id, application.roundNo(),
                round.processInstanceId(), "RETURN by " + actor));
        repository.update(application, expectedVersion);
        rounds.complete(tenantId, id, application.roundNo(), instanceId, SubmissionRound.Status.RETURNED, comment, actor, Instant.now());
        recordApplicationOperation(application, actor, ApplicationAuditPort.Action.RETURN, previousStatus, instanceId, comment);
        return application;
    }

    /** 已授权的业务核定只更新当前路由，不改写提交快照；具体任务证据由业务用例追加。 */
    public Application adjustBusiness(String tenantId, UUID id, long expectedVersion, BusinessReference reference,
            Map<String, Object> payload) {
        var application = get(tenantId, id);
        application.adjustBusinessPayload(expectedVersion, reference, payload);
        var round = rounds.findByRound(tenantId, id, application.roundNo()).orElseThrow(
                () -> new DomainException("CONCURRENCY_CONFLICT", "Active submission round not found"));
        if (round.status() != SubmissionRound.Status.IN_APPROVAL || round.definitionVersion() != application.definitionVersion()) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Submission round no longer matches the active application");
        }
        processRuntime.updateBusinessPayload(new ProcessRuntimePort.UpdateBusinessPayload(tenantId, id, application.roundNo(), round.processInstanceId(), application.payload()));
        repository.update(application, expectedVersion);
        return application;
    }

    private void recordApplicationOperation(Application application, String actor, ApplicationAuditPort.Action action,
                                            ApplicationStatus previousStatus, String instanceId, String comment) {
        audit.record(new ApplicationAuditPort.ApplicationOperation(application.tenantId(), application.businessNo(), application.id(),
                application.version(), application.roundNo(), instanceId, actor, action, previousStatus,
                application.status(), comment));
    }

    /** 获取当前租户申请。 */
    public Application get(String tenantId, UUID id) {
        return repository.findById(tenantId, id)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Application not found"));
    }

    /** 按租户读取申请，访问主体规则由接口适配层根据参与关系判断。 */
    public Application find(String tenantId, UUID id) {
        return repository.findById(tenantId, id).orElse(null);
    }
}
