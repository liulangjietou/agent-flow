package io.agentflow.approval.process;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.CountersignProgress;
import io.agentflow.approval.model.TaskAction;
import io.agentflow.approval.model.TaskDelegation;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.service.TaskAuditPort;
import io.agentflow.attachment.AttachmentReferenceService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.ErrorCode;
import io.agentflow.common.SystemRole;
import io.agentflow.form.FormSchema;
import io.agentflow.notification.ApprovalNotificationService;
import io.agentflow.observability.DiagnosticContext;
import io.agentflow.organization.ApprovalProxyUse;
import org.apache.commons.lang3.StringUtils;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.task.api.DelegationState;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 审批任务高级协作服务：支持退回指定已办历史节点以及审批节点回填可编辑表单字段。
 * 与 {@link FlowableTaskFacade} 拆分职责，避免任务门面演化为上帝类。
 * @author owlzhangfq@gmail.com
 */
@Service
public class FlowableTaskEnhancementService {
    private static final Logger LOG = LoggerFactory.getLogger(FlowableTaskEnhancementService.class);
    private static final String ACTION_RETURN_TO_NODE = "RETURN_TO_NODE";
    private static final String ACTION_TASK_FIELDS_UPDATED = "TASK_FIELDS_UPDATED";
    private final TaskService taskService;
    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final CurrentActor currentActor;
    private final ApplicationRepository applicationRepository;
    private final TaskAuditPort auditPort;
    private final ApprovalNotificationService notifications;
    private final FlowableTaskAuthorization authorization;
    private final ApprovalCompletionService completion;
    private final AttachmentReferenceService attachments;

    /** 构造高级任务协作服务。 */
    public FlowableTaskEnhancementService(
            TaskService taskService,
            RuntimeService runtimeService,
            HistoryService historyService,
            CurrentActor currentActor,
            ApplicationRepository applicationRepository,
            TaskAuditPort auditPort,
            ApprovalNotificationService notifications,
            FlowableTaskAuthorization authorization,
            ApprovalCompletionService completion,
            AttachmentReferenceService attachments) {
        this.taskService = taskService;
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.currentActor = currentActor;
        this.applicationRepository = applicationRepository;
        this.auditPort = auditPort;
        this.notifications = notifications;
        this.authorization = authorization;
        this.completion = completion;
        this.attachments = attachments;
    }

    /** 列出当前任务本轮可退回的历史人工节点（按完成时间倒序去重，排除当前节点）。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ReturnableNode> returnableNodes(String taskId) {
        Actor actor = currentActor.actor();
        actor.requireRole(SystemRole.APPROVER);
        Task task = authorization.requireReadable(taskId, actor);
        Application application = authorization.application(actor, task);
        if (application.status() != ApplicationStatus.IN_APPROVAL || application.businessReference() != null) {
            return List.of();
        }
        return findFinishedUserTasks(task.getProcessInstanceId(), task.getTaskDefinitionKey());
    }

    /** 将当前审批任务退回至本轮已完成的指定历史人工节点，不终止申请也不产生新轮次。 */
    @Transactional
    public FlowableTaskFacade.ActionResult returnToNode(
            String taskId, String targetNodeId, String comment, Long expectedVersion, UUID proxyId) {
        Actor actor = currentActor.actor();
        actor.requireRole(SystemRole.APPROVER);
        if (expectedVersion == null) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "expectedVersion is required");
        }
        if (StringUtils.isBlank(targetNodeId)) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "targetNodeId is required");
        }
        if (StringUtils.isBlank(comment)) {
            throw new DomainException(ErrorCode.DOMAIN_RULE_VIOLATION, "A reason is required");
        }
        Task readable = authorization.requireReadable(taskId, actor);
        Application application = completion.lock(authorization.application(actor, readable));
        application.checkVersion(expectedVersion);
        if (application.businessReference() != null) {
            throw new DomainException(ErrorCode.USE_BUSINESS_ENDPOINT,
                    "Structured business applications do not support arbitrary historical node return");
        }
        var authorized = authorization.requireAction(taskId, actor, TaskAction.RETURN, proxyId);
        Task task = authorized.task();
        String normalizedTarget = targetNodeId.trim();
        try (var trace = DiagnosticContext.forBusiness(application.tenantId(), application.businessNo(),
                task.getProcessInstanceId(), task.getId()).open()) {
            var proxyUse = authorized.proxyUse();
            delegation(task).requireAction(TaskAction.RETURN);
            CountersignProgress countersign = countersign(task);
            if (countersign != null) countersign.requireAction(TaskAction.RETURN);

            String processInstanceId = task.getProcessInstanceId();
            String currentNodeId = task.getTaskDefinitionKey();
            if (normalizedTarget.equals(currentNodeId)) {
                throw new DomainException(ErrorCode.TASK_RETURN_NODE_INVALID,
                        "Target node cannot be the current approval node");
            }
            requireNoParallelOrSubprocessActive(processInstanceId, currentNodeId);
            List<ReturnableNode> candidates = findFinishedUserTasks(processInstanceId, currentNodeId);
            boolean validTarget = candidates.stream().anyMatch(node -> node.nodeId().equals(normalizedTarget));
            if (!validTarget) {
                throw new DomainException(ErrorCode.TASK_RETURN_NODE_INVALID,
                        "Target node must be a previously completed user task in the current approval round");
            }

            ApplicationStatus previousStatus = application.status();
            var previousTaskIds = notifications.pendingTaskIds(application);
            recordDecisionAssignee(task, actor, proxyUse);
            taskService.addComment(task.getId(), processInstanceId, comment.trim());
            runtimeService.createChangeActivityStateBuilder()
                    .processInstanceId(processInstanceId)
                    .moveActivityIdTo(currentNodeId, normalizedTarget)
                    .changeState();

            application.recordTaskAction(expectedVersion);
            applicationRepository.update(application, expectedVersion);
            String auditComment = "targetNodeId=" + normalizedTarget + "; " + comment.trim();
            String auditEventId = audit(task, application, actor, ACTION_RETURN_TO_NODE, auditComment, previousStatus, proxyUse);
            notifications.processAdvanced(application, actor.userId(), taskId, task.getName(), previousTaskIds);
            LOG.info("Task returned to historical node, taskId={}, fromNode={}, targetNode={}",
                    taskId, currentNodeId, normalizedTarget);
            return new FlowableTaskFacade.ActionResult(
                    taskId, ACTION_RETURN_TO_NODE, application.status().name(), application.version(), auditEventId);
        }
    }

    /** 审批人在当前节点回填该节点配置为 EDITABLE 的普通表单字段，并同步更新流程引擎变量。 */
    @Transactional
    public FlowableTaskFacade.ActionResult updateEditableFields(
            String taskId, Long expectedVersion, Map<String, Object> fields, UUID proxyId) {
        Actor actor = currentActor.actor();
        actor.requireRole(SystemRole.APPROVER);
        if (expectedVersion == null) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "expectedVersion is required");
        }
        if (fields == null || fields.isEmpty()) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "fields is required");
        }
        Task readable = authorization.requireReadable(taskId, actor);
        Application application = completion.lock(authorization.application(actor, readable));
        application.checkVersion(expectedVersion);
        var authorized = authorization.requireAction(taskId, actor, TaskAction.APPROVE, proxyId);
        Task task = authorized.task();
        try (var trace = DiagnosticContext.forBusiness(application.tenantId(), application.businessNo(),
                task.getProcessInstanceId(), task.getId()).open()) {
            var proxyUse = authorized.proxyUse();
            delegation(task).requireAction(TaskAction.APPROVE);
            ApplicationStatus previousStatus = application.status();
            application.updateTaskEditableFields(expectedVersion, task.getTaskDefinitionKey(), fields);
            if (hasAttachmentChange(application.formSchema(), fields)) {
                attachments.validate(application, true);
                attachments.freeze(application);
            }
            applicationRepository.update(application, expectedVersion);
            runtimeService.setVariable(task.getProcessInstanceId(), "formData", application.payload());
            String updatedKeys = String.join(",", fields.keySet().stream().sorted().toList());
            String auditEventId = audit(task, application, actor, ACTION_TASK_FIELDS_UPDATED,
                    "updatedFields=" + updatedKeys, previousStatus, proxyUse);
            LOG.info("Task editable fields updated, taskId={}, nodeId={}, fieldCount={}",
                    taskId, task.getTaskDefinitionKey(), fields.size());
            return new FlowableTaskFacade.ActionResult(
                    taskId, ACTION_TASK_FIELDS_UPDATED, application.status().name(), application.version(), auditEventId);
        }
    }

    private void requireNoParallelOrSubprocessActive(String processInstanceId, String currentNodeId) {
        List<Task> activeTasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .active()
                .list();
        boolean hasOtherBranch = activeTasks.stream()
                .anyMatch(active -> !currentNodeId.equals(active.getTaskDefinitionKey()));
        if (hasOtherBranch) {
            throw new DomainException(ErrorCode.DOMAIN_RULE_VIOLATION,
                    "Cannot return to a historical node while parallel approval branches are active");
        }
        long activeSubprocesses = runtimeService.createProcessInstanceQuery()
                .superProcessInstanceId(processInstanceId)
                .active()
                .count();
        if (activeSubprocesses > 0) {
            throw new DomainException(ErrorCode.DOMAIN_RULE_VIOLATION,
                    "Cannot return to a historical node while child subprocesses are active");
        }
    }

    private List<ReturnableNode> findFinishedUserTasks(String processInstanceId, String currentNodeId) {
        List<HistoricActivityInstance> finished = historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .activityType("userTask")
                .finished()
                .orderByHistoricActivityInstanceEndTime()
                .desc()
                .list();
        Map<String, ReturnableNode> unique = new LinkedHashMap<>();
        for (HistoricActivityInstance item : finished) {
            String activityId = item.getActivityId();
            if (StringUtils.isBlank(activityId) || activityId.equals(currentNodeId) || unique.containsKey(activityId)) {
                continue;
            }
            Instant endTime = item.getEndTime() == null ? null : item.getEndTime().toInstant();
            String name = StringUtils.isBlank(item.getActivityName()) ? activityId : item.getActivityName();
            unique.put(activityId, new ReturnableNode(activityId, name, endTime));
        }
        return new ArrayList<>(unique.values());
    }

    private static boolean hasAttachmentChange(FormSchema schema, Map<String, Object> fields) {
        if (schema == null) return false;
        return schema.fields().stream()
                .anyMatch(field -> field.type() == FormSchema.FieldType.ATTACHMENT && fields.containsKey(field.key()));
    }

    private TaskDelegation delegation(Task task) {
        return new TaskDelegation(task.getOwner(), task.getDelegationState() == DelegationState.PENDING);
    }

    private CountersignProgress countersign(Task task) {
        var variables = taskService.getVariables(task.getId(), List.of(FlowableCountersignMembers.MEMBERS,
                FlowableCountersignMembers.TOTAL, FlowableCountersignMembers.COMPLETED, FlowableCountersignMembers.MODE,
                FlowableCountersignMembers.PERCENTAGE, FlowableCountersignMembers.REQUIRED));
        if (!variables.containsKey(FlowableCountersignMembers.MEMBERS)) return null;
        int total = ((Number) variables.get(FlowableCountersignMembers.TOTAL)).intValue();
        int completed = ((Number) variables.get(FlowableCountersignMembers.COMPLETED)).intValue();
        if (!variables.containsKey(FlowableCountersignMembers.MODE)) return new CountersignProgress(total, completed);
        var mode = io.agentflow.definition.DefinitionModels.ApprovalMode.valueOf((String) variables.get(FlowableCountersignMembers.MODE));
        Integer percentage = variables.get(FlowableCountersignMembers.PERCENTAGE) instanceof Number number ? number.intValue() : null;
        return new CountersignProgress(total, completed, mode, percentage,
                ((Number) variables.get(FlowableCountersignMembers.REQUIRED)).intValue());
    }

    private void recordDecisionAssignee(Task task, Actor actor, ApprovalProxyUse proxyUse) {
        if (proxyUse != null) {
            taskService.addUserIdentityLink(task.getId(), proxyUse.principal(), FlowableProxyParticipation.PRINCIPAL_LINK);
            taskService.setAssignee(task.getId(), actor.userId());
        } else if (task.getAssignee() == null) {
            taskService.claim(task.getId(), actor.userId());
        }
    }

    private String audit(Task task, Application application, Actor actor, String action, String comment,
                         ApplicationStatus previousStatus, ApprovalProxyUse proxyUse) {
        return auditPort.record(new TaskAuditPort.TaskOperation(
                actor.tenantId(), application.businessNo(), task.getId(), application.id(),
                application.version(), application.roundNo(), task.getProcessInstanceId(), actor.userId(), action,
                comment, null, task.getTaskDefinitionKey(), task.getName(), previousStatus, application.status(), null, proxyUse));
    }

    /**
     * 可退回的历史人工节点摘要。
     * @author owlzhangfq@gmail.com
     */
    public record ReturnableNode(String nodeId, String nodeName, Instant completedAt) { }
}
