package io.agentflow.definition;

import io.agentflow.common.DomainException;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.Map;
import java.util.Objects;

import static io.agentflow.definition.DefinitionDeploymentPort.DeploymentResult;
import static io.agentflow.definition.DefinitionModels.*;

/**
 * Flowable 发布适配器。BPMN 由受限流程图生成，用户输入不会直接进入 JUEL 或脚本执行器。
 * @author owlzhangfq@gmail.com
 */
@Component
public class FlowableDefinitionDeploymentAdapter implements DefinitionDeploymentPort {
    private static final String DEPLOYMENT_CONFLICT = "DEFINITION_DEPLOYMENT_CONFLICT";
    private final RepositoryService repositoryService;
    private final SubprocessDeploymentBindings subprocesses;

    /** 使用与流程定义仓储共用事务的数据源发布流程。 */
    public FlowableDefinitionDeploymentAdapter(RepositoryService repositoryService, SubprocessDeploymentBindings subprocesses) {
        this.repositoryService = repositoryService;
        this.subprocesses = subprocesses;
    }

    @Override
    @Transactional
    public DeploymentResult deploy(DefinitionDraft draft) {
        String resourceName = draft.key() + "-v" + draft.version() + ".bpmn20.xml";
        var bindings = subprocesses.bind(draft);
        var deployment = repositoryService.createDeployment()
                .name(draft.name())
                .key(draft.key())
                .tenantId(draft.tenantId())
                .addString(resourceName, RestrictedBpmnWriter.write(draft, bindings))
                .deploy();
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
        if (definition == null) {
            throw new DomainException(DEPLOYMENT_CONFLICT, "Flowable process definition was not created");
        }
        // 引擎版本由引擎分配，不能复用同版本的陌生流程图；序列不一致时回滚本次发布。
        if (definition.getVersion() != draft.version()) {
            throw new DomainException(DEPLOYMENT_CONFLICT,
                    "Engine definition version does not match the platform version");
        }
        return new DeploymentResult(deployment.getId(), definition.getId(), definition.getKey(), definition.getVersion());
    }

    /**
     * 只生成有限节点类型，条件以扩展属性保存供安全路由适配器消费。
     * @author owlzhangfq@gmail.com
     */
    static final class RestrictedBpmnWriter {
        private RestrictedBpmnWriter() { }

        static String write(DefinitionDraft draft) {
            return write(draft, Map.of());
        }

        static String write(DefinitionDraft draft, Map<String, String> subprocesses) {
            Graph graph = draft.graph();
            var decisionSources = graph.nodes().stream().filter(node -> node.type() == NodeType.USER_TASK)
                    .flatMap(node -> ApprovalResponsibilityPolicy.fromProperties(node.properties()).differentApproverFrom().stream())
                    .collect(java.util.stream.Collectors.toSet());
            var eventMessages = eventMessages(graph, draft.key());
            StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    .append("<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" ")
                    .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
                    .append("xmlns:flowable=\"http://flowable.org/bpmn\" targetNamespace=\"http://agentflow.io/process\">");
            eventMessages.values().forEach(id -> xml.append("<message id=\"").append(id).append("\" name=\"").append(id).append("\"/>"));
            xml.append("<process id=\"").append(escape(draft.key())).append("\" name=\"")
                    .append(escape(draft.name())).append("\" isExecutable=\"true\">");
            for (Node node : graph.nodes()) {
                switch (node.type()) {
                    case START -> xml.append("<startEvent id=\"").append(escape(node.id())).append("\" name=\"")
                            .append(escape(node.name())).append("\"/>");
                    case END -> xml.append("<endEvent id=\"").append(escape(node.id())).append("\" name=\"")
                            .append(escape(node.name())).append("\"/>");
                    case USER_TASK -> appendUserTask(xml, node, decisionSources.contains(node.id()));
                    case SUB_PROCESS -> xml.append("<callActivity id=\"").append(escape(node.id()))
                            .append("\" name=\"").append(escape(node.name())).append("\" calledElement=\"")
                            .append(escape(Objects.requireNonNull(subprocesses.get(node.id()), "Bound subprocess definition is required")))
                            .append("\" flowable:calledElementType=\"id\" flowable:inheritVariables=\"false\" ")
                            .append("flowable:inheritBusinessKey=\"false\" flowable:fallbackToDefaultTenant=\"false\"/>");
                    case EVENT_WAIT -> xml.append("<intermediateCatchEvent id=\"").append(escape(node.id()))
                            .append("\" name=\"").append(escape(node.name())).append("\"><messageEventDefinition messageRef=\"")
                            .append(eventMessages.get(node.id())).append("\"/></intermediateCatchEvent>");
                    case TIMER_WAIT -> xml.append("<intermediateCatchEvent id=\"").append(escape(node.id()))
                            .append("\" name=\"").append(escape(node.name())).append("\"><timerEventDefinition><timeDuration>")
                            .append(TimerWaitPolicy.fromProperties(node.properties()).duration())
                            .append("</timeDuration></timerEventDefinition></intermediateCatchEvent>");
                    case SERVICE_TASK -> xml.append("<receiveTask id=\"").append(escape(node.id())).append("\" name=\"")
                            .append(escape(node.name())).append("\"><extensionElements><flowable:executionListener event=\"start\" ")
                            .append("delegateExpression=\"${flowableServiceTaskArrival}\"/></extensionElements></receiveTask>");
                    case COPY -> {
                        String encodedRule = Base64.getEncoder().encodeToString(node.properties().get("recipientRule")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        xml.append("<serviceTask id=\"").append(escape(node.id())).append("\" name=\"")
                                .append(escape(node.name())).append("\" flowable:expression=\"${flowableCopyRecipients.deliver(execution, '")
                                .append(encodedRule).append("')}\"/>");
                    }
                    case EXCLUSIVE_GATEWAY -> appendGateway(xml, node, graph);
                    case PARALLEL_GATEWAY -> xml.append("<parallelGateway id=\"").append(escape(node.id()))
                            .append("\" name=\"").append(escape(node.name())).append("\"/>");
                    default -> throw new IllegalArgumentException("Unsupported publish node type: " + node.type());
                }
            }
            for (Edge edge : graph.edges()) {
                xml.append("<sequenceFlow id=\"").append(escape(edge.id())).append("\" sourceRef=\"")
                        .append(escape(edge.source())).append("\" targetRef=\"").append(escape(edge.target())).append("\">");
                if (!edge.condition().isBlank()) {
                    String encodedCondition = Base64.getEncoder().encodeToString(edge.condition().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    xml.append("<conditionExpression xsi:type=\"tFormalExpression\">${flowableConditionEvaluator.matches(execution, '")
                            .append(encodedCondition).append("', ").append(graph.conditionLanguageVersion()).append(")}</conditionExpression>");
                }
                xml.append("</sequenceFlow>");
            }
            return xml.append("</process></definitions>").toString();
        }

        /** 消息标识完全由适配器生成，并避开同一 BPMN 文档的业务标识，不对外提供广播名称。 */
        private static java.util.Map<String, String> eventMessages(Graph graph, String processKey) {
            var used = new java.util.HashSet<String>(); used.add(processKey);
            graph.nodes().forEach(node -> used.add(node.id())); graph.edges().forEach(edge -> used.add(edge.id()));
            var messages = new java.util.LinkedHashMap<String, String>();
            int sequence = 1;
            for (Node node : graph.nodes()) {
                if (node.type() != NodeType.EVENT_WAIT) continue;
                String id;
                do { id = "agentflowEventMessage" + sequence++; } while (!used.add(id));
                messages.put(node.id(), id);
            }
            return messages;
        }

        private static void appendGateway(StringBuilder xml, Node node, Graph graph) {
            String defaultEdge = graph.edges().stream()
                    .filter(edge -> edge.source().equals(node.id()) && edge.defaultBranch())
                    .map(Edge::id).findFirst()
                    .orElseGet(() -> graph.edges().stream()
                            .filter(edge -> edge.source().equals(node.id()) && edge.condition().isBlank())
                            .map(Edge::id).findFirst().orElse(null));
            xml.append("<exclusiveGateway id=\"").append(escape(node.id()))
                    .append("\" name=\"").append(escape(node.name())).append("\"");
            if (defaultEdge != null) {
                xml.append(" default=\"").append(escape(defaultEdge)).append("\"");
            }
            xml.append("/>");
        }

        private static void appendUserTask(StringBuilder xml, Node node, boolean recordDecision) {
            String rule = Objects.requireNonNull(node.properties().get("assigneeRule"), "assigneeRule");
            xml.append("<userTask id=\"").append(escape(node.id())).append("\" name=\"")
                    .append(escape(node.name())).append("\"");
            ApprovalPolicy policy = node.approvalPolicy();
            var responsibilities = ApprovalResponsibilityPolicy.fromProperties(node.properties());
            String encodedRule = Base64.getEncoder().encodeToString(rule.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String encodedReferences = Base64.getEncoder().encodeToString(String.join(",", responsibilities.differentApproverFrom())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (policy.multiInstance()) {
                // 全员模式保留既有表达式；其他方式只传入已校验枚举及整数，不能插入自定义表达式。
                xml.append(" flowable:assignee=\"${agentflowCountersignUser}\">");
                if (recordDecision) appendDecisionListener(xml);
                xml.append("<multiInstanceLoopCharacteristics isSequential=\"false\" ")
                        .append("flowable:collection=\"${flowableCountersignMembers.")
                        .append(responsibilities.enabled() ? "resolveWithResponsibilities" : "resolve")
                        .append("(execution, '").append(encodedRule).append("'");
                if (policy.mode() != ApprovalMode.ALL || responsibilities.enabled()) xml.append(", '").append(policy.mode().name()).append("', ").append(policy.percentage());
                if (responsibilities.enabled()) xml.append(", ").append(responsibilities.excludeApplicant()).append(", '").append(encodedReferences).append("'");
                xml.append(")}\" flowable:elementVariable=\"agentflowCountersignUser\">");
                if (policy.mode() != ApprovalMode.ALL) {
                    xml.append("<completionCondition xsi:type=\"tFormalExpression\">${nrOfCompletedInstances &gt;= agentflowCountersignRequired}</completionCondition>");
                }
                xml.append("</multiInstanceLoopCharacteristics></userTask>");
                return;
            }
            if (responsibilities.enabled()) {
                // 角色必须先展开成真实人员再排除，不能保留候选组使冲突账号仍能领取。
                xml.append(" flowable:candidateUsers=\"${flowableApprovalResponsibilities.resolve(execution, '")
                        .append(encodedRule).append("', ").append(responsibilities.excludeApplicant()).append(", '")
                        .append(encodedReferences).append("')}\"");
            } else if (FormAssigneePolicy.isFieldRule(rule) || io.agentflow.organization.LocalOrganizationDirectory.isLocalRule(rule)) {
                String encoded = java.util.Base64.getEncoder().encodeToString(rule.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                xml.append(" flowable:candidateUsers=\"${flowableOrganizationMembers.resolve(execution, '")
                        .append(encoded).append("')}\"");
            } else if (rule.startsWith("role:")) {
                xml.append(" flowable:candidateGroups=\"").append(escape(rule.substring("role:".length()))).append("\"");
            } else if (rule.startsWith("user:")) {
                xml.append(" flowable:assignee=\"").append(escape(rule.substring("user:".length()))).append("\"");
            } else {
                throw new IllegalArgumentException("Unsupported assigneeRule: " + rule);
            }
            if (recordDecision) {
                xml.append(">"); appendDecisionListener(xml); xml.append("</userTask>");
            } else xml.append("/>");
        }

        private static void appendDecisionListener(StringBuilder xml) {
            xml.append("<extensionElements><flowable:taskListener event=\"complete\" delegateExpression=\"${flowableApprovalResponsibilities}\"/></extensionElements>");
        }

        private static String escape(String value) {
            return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;").replace("'", "&apos;");
        }
    }
}
