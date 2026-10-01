package io.agentflow.approval;

import io.agentflow.approval.model.SubmissionRisk;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.model.SubprocessCall;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.approval.repository.SubprocessCallRepository;
import io.agentflow.approval.service.ApplicationAuditPort;
import io.agentflow.attachment.SubprocessAttachmentService;
import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionDraftRepository;
import io.agentflow.definition.DefinitionModels;
import io.agentflow.definition.SubprocessDefinitionResolver;
import io.agentflow.definition.SubprocessPolicy;
import io.agentflow.event.EventContractBindings;
import io.agentflow.organization.InitiatorContext;
import io.agentflow.organization.LocalOrganizationDirectory;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原生子调用的业务编排：固定输入、原任职及独立申请身份，与引擎共享事务而不依赖其执行对象。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SubprocessStartService {
    public static final String SYSTEM_ACTOR = "system:subprocess";
    private final ApplicationRepository applications;
    private final SubmissionRoundRepository rounds;
    private final SubprocessCallRepository calls;
    private final DefinitionDraftRepository definitions;
    private final SubprocessDefinitionResolver resolver;
    private final EventContractBindings events;
    private final SubprocessAttachmentService attachments;
    private final ApplicationAuditPort audit;

    /** 固定版本解析、业务仓储、事件与附件通过组合完成，不在流程变量中保存完整父申请。 */
    public SubprocessStartService(ApplicationRepository applications, SubmissionRoundRepository rounds,
            SubprocessCallRepository calls, DefinitionDraftRepository definitions, SubprocessDefinitionResolver resolver,
            EventContractBindings events, SubprocessAttachmentService attachments, ApplicationAuditPort audit) {
        this.applications = applications; this.rounds = rounds; this.calls = calls; this.definitions = definitions;
        this.resolver = resolver; this.events = events; this.attachments = attachments; this.audit = audit;
    }

    /** 原生实例创建前核对实际父身份并准备独立子输入；任何失败都留在当前启动事务内。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Prepared prepare(Parent parent, String calledDefinitionId) {
        var application = applications.lockById(parent.tenantId(), parent.applicationId()).orElseThrow(SubprocessStartService::invalidParent);
        validateParent(application, parent);
        var definition = definitions.findPublished(parent.tenantId(), application.processKey(), application.definitionVersion())
                .orElseThrow(SubprocessStartService::invalidParent);
        var node = definition.graph().node(parent.nodeId());
        if (node == null || node.type() != DefinitionModels.NodeType.SUB_PROCESS) {
            throw new DomainException("SUBPROCESS_NODE_REQUIRED", "The active node is not a platform subprocess call");
        }
        if (calls.findByActivation(parent.tenantId(), parent.processInstanceId(), parent.activationId()).isPresent()) {
            throw new DomainException("SUBPROCESS_CALL_EXISTS", "The native activation already has a child application");
        }
        var policy = SubprocessPolicy.fromProperties(node.properties());
        var bound = resolver.resolve(parent.tenantId(), policy, node.id(), application.formSchema());
        if (!bound.runtimeDefinitionId().equals(calledDefinitionId)) {
            throw new DomainException("SUBPROCESS_DEFINITION_MISMATCH", "Native called definition differs from the bound subprocess version");
        }
        requireAcyclic(parent, bound.runtimeDefinitionId());
        events.requireAvailable(parent.tenantId(), bound.graph());
        if (parent.initiatorContext() == null && bound.graph().nodes().stream().anyMatch(childNode ->
                LocalOrganizationDirectory.isContextualRule(childNode.properties().get("assigneeRule"))
                || LocalOrganizationDirectory.isContextualRule(childNode.properties().get("recipientRule"))
                || LocalOrganizationDirectory.isContextualRule(childNode.properties().get(io.agentflow.definition.TaskEscalationPolicy.RECIPIENT_RULE)))) {
            throw new DomainException("INITIATOR_APPOINTMENT_REQUIRED", "The subprocess requires the original initiator appointment");
        }
        var childId = UUID.randomUUID(); var at = Instant.now();
        var preparedFiles = attachments.prepare(application, childId, bound.formSchema(), bound.inputs().project(parent.payload()), SYSTEM_ACTOR, at);
        var child = Application.draft(childId, parent.tenantId(), "subprocess-" + childId, bound.processKey(), bound.version(),
                application.createdBy(), node.name(), preparedFiles.values(), bound.formSchema(), bound.runtimeDefinitionId(), bound.notificationTexts());
        var risk = bound.graph().riskPolicy() == null ? SubmissionRisk.unassessed()
                : bound.graph().riskPolicy().assess(bound.definitionId(), bound.version(), bound.formSchema(),
                        bound.graph().conditionLanguageVersion(), child.payload());
        return new Prepared(UUID.randomUUID(), parent, policy, bound.definitionId(), node.name(), child, preparedFiles, at, risk);
    }

    /** 拿到实际子实例标识后，在首个节点激活前保存全部业务身份、首轮、来源和系统审计。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SubprocessCall persist(Prepared prepared, String childInstanceId) {
        var child = prepared.child(); var parent = prepared.parent();
        applications.save(child);
        record(child, null, ApplicationAuditPort.Action.CREATE, null);
        child.submit(child.version());
        applications.update(child, child.version() - 1);
        rounds.append(SubmissionRound.submitted(child, childInstanceId, SYSTEM_ACTOR, prepared.at(), parent.initiatorContext(), prepared.risk()));
        var call = new SubprocessCall(prepared.callId(), parent.tenantId(), parent.applicationId(), parent.roundNo(),
                parent.processInstanceId(), parent.runtimeDefinitionId(), parent.nodeId(), prepared.nodeName(), parent.activationId(),
                child.id(), childInstanceId, prepared.childDefinitionId(), child.runtimeDefinitionId(), prepared.policy(), prepared.at());
        calls.append(call); attachments.persist(call, prepared.attachments());
        record(child, childInstanceId, ApplicationAuditPort.Action.SUBMIT, ApplicationStatus.DRAFT);
        return call;
    }

    private void validateParent(Application application, Parent parent) {
        if (!Objects.equals(application.runtimeDefinitionId(), parent.runtimeDefinitionId())
                || !application.payload().equals(parent.payload())
                || parent.initiatorContext() != null && !application.createdBy().equals(parent.initiatorContext().subject())) throw invalidParent();
        var round = rounds.findByRound(parent.tenantId(), application.id(), parent.roundNo());
        // 首提或重提时父轮次在引擎返回后保存；已在审的调用必须匹配原轮次，不能套用这项时序例外。
        if (application.status() == ApplicationStatus.IN_APPROVAL) {
            if (application.roundNo() != parent.roundNo() || round.isEmpty()
                    || round.get().status() != SubmissionRound.Status.IN_APPROVAL
                    || !round.get().processInstanceId().equals(parent.processInstanceId())
                    || !Objects.equals(round.get().initiatorContext(), parent.initiatorContext())) throw invalidParent();
        } else if (!application.editable() || application.nextSubmissionRound() != parent.roundNo() || round.isPresent()) {
            throw invalidParent();
        }
    }

    private void requireAcyclic(Parent parent, String childDefinitionId) {
        var ancestors = new HashSet<UUID>();
        UUID current = parent.applicationId(); String definitionId = parent.runtimeDefinitionId(); int depth = 1;
        // 沿不可变调用来源检查实际发布版本，避免同一版本递归创建申请；名称相同不代表同一版本。
        while (true) {
            if (!ancestors.add(current) || definitionId.equals(childDefinitionId)) {
                throw new DomainException("SUBPROCESS_RECURSION_FORBIDDEN", "A subprocess cannot recursively call an active ancestor version");
            }
            if (depth > SubprocessPolicy.MAX_CALL_DEPTH) throw new DomainException("SUBPROCESS_DEPTH_EXCEEDED", "Subprocess nesting exceeds the supported depth");
            var origin = calls.findByChild(parent.tenantId(), current);
            if (origin.isEmpty()) return;
            current = origin.get().parentApplicationId(); definitionId = origin.get().parentRuntimeDefinitionId(); depth++;
        }
    }

    private void record(Application application, String instanceId, ApplicationAuditPort.Action action, ApplicationStatus previous) {
        audit.record(new ApplicationAuditPort.ApplicationOperation(application.tenantId(), application.id(), application.version(),
                application.roundNo(), instanceId, SYSTEM_ACTOR, action, previous, application.status(), "由父流程调用节点发起"));
    }

    private static DomainException invalidParent() {
        return new DomainException("SUBPROCESS_PARENT_MISMATCH", "Native parent context differs from the active application round");
    }

    /**
     * 引擎适配器传入受控上下文；应用服务复核实际申请轮次，不信任表单中的身份字段。
     * @author owlzhangfq@gmail.com
     */
    public record Parent(String tenantId, UUID applicationId, int roundNo, String processInstanceId,
                         String runtimeDefinitionId, String nodeId, String activationId,
                         Map<String, Object> payload, InitiatorContext initiatorContext) { }

    /**
     * 只在原生启动命令内暂存，不能序列化成流程变量或暴露为可重放的客户端启动令牌。
     * @author owlzhangfq@gmail.com
     */
    public record Prepared(UUID callId, Parent parent, SubprocessPolicy policy, UUID childDefinitionId,
                           String nodeName, Application child, SubprocessAttachmentService.Prepared attachments, Instant at, SubmissionRisk risk) { }
}
