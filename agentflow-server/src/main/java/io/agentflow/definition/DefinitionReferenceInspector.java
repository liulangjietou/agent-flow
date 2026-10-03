package io.agentflow.definition;

import io.agentflow.approval.copy.CopyRecipient;
import io.agentflow.calendar.BusinessCalendarRepository;
import io.agentflow.event.EventContractBindings;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import static io.agentflow.definition.DefinitionModels.Graph;
import static io.agentflow.definition.DefinitionModels.NodeType;

/**
 * 根定义与固定版本子定义共用发布依赖检查，不因嵌套调用绕过身份、日历或事件的可用性。
 * @author owlzhangfq@gmail.com
 */
@Service
public class DefinitionReferenceInspector {
    private final DefinitionAssigneeDirectory assignees;
    private final BusinessCalendarRepository calendars;
    private final EventContractBindings eventContracts;

    /** 跨聚合读取属于应用编排，不放进流程图领域校验器。 */
    public DefinitionReferenceInspector(DefinitionAssigneeDirectory assignees, BusinessCalendarRepository calendars,
                                        EventContractBindings eventContracts) {
        this.assignees = assignees;
        this.calendars = calendars;
        this.eventContracts = eventContracts;
    }

    /** 结构校验通过后读取当前租户目录，只返回节点规则码，不修改引用内容。 */
    public List<String> inspect(String tenantId, Graph graph, io.agentflow.form.FormSchema schema) {
        var available = assignees.options(tenantId).stream().filter(option -> option.memberCount() > 0 || option.contextual())
                .map(DefinitionAssigneeDirectory.Option::rule).collect(Collectors.toSet());
        var errors = new ArrayList<>(graph.nodes().stream().filter(node -> node.type() == NodeType.USER_TASK)
                .filter(node -> !FormAssigneePolicy.isFieldRule(node.properties().get("assigneeRule")))
                .filter(node -> !available.contains(node.properties().get("assigneeRule")))
                .map(node -> "ASSIGNEE_NOT_AVAILABLE:" + node.id()).toList());
        var fieldNodes = graph.nodes().stream().filter(node -> node.type() == NodeType.USER_TASK
                && FormAssigneePolicy.isFieldRule(node.properties().get("assigneeRule"))).toList();
        if (!fieldNodes.isEmpty()) {
            var options = assignees.formOptions(tenantId).stream().collect(Collectors.toMap(DefinitionAssigneeDirectory.FormOption::id, value -> value));
            for (var node : fieldNodes) {
                var policy = FormAssigneePolicy.parse(node.properties().get("assigneeRule"));
                if (policy.references(schema).stream().anyMatch(id -> !options.containsKey(id) || !options.get(id).availableFor(policy.relation()))) {
                    errors.add("FORM_ASSIGNEE_OPTION_UNAVAILABLE:" + node.id());
                }
            }
        }
        if (graph.nodes().stream().anyMatch(node -> node.type() == NodeType.COPY || node.properties().containsKey(TaskEscalationPolicy.RECIPIENT_RULE))) {
            var copyRules = assignees.copyOptions(tenantId).stream().filter(option -> option.contextual()
                    || option.memberCount() > 0 && option.memberCount() <= CopyRecipient.MAX_RECIPIENTS)
                    .map(DefinitionAssigneeDirectory.Option::rule).collect(Collectors.toSet());
            graph.nodes().stream().filter(node -> node.type() == NodeType.COPY)
                    .filter(node -> !copyRules.contains(node.properties().get("recipientRule")))
                    .forEach(node -> errors.add("COPY_RECIPIENT_UNAVAILABLE:" + node.id()));
            graph.nodes().stream().filter(node -> node.properties().containsKey(TaskEscalationPolicy.RECIPIENT_RULE))
                    .filter(node -> !copyRules.contains(node.properties().get(TaskEscalationPolicy.RECIPIENT_RULE)))
                    .forEach(node -> errors.add("ESCALATION_RECIPIENT_UNAVAILABLE:" + node.id()));
        }
        for (var node : graph.nodes()) {
            TaskDeadlinePolicy.fromProperties(node.properties()).ifPresent(policy -> {
                if (calendars.findVersion(tenantId, policy.calendarId(), policy.calendarRevision()).isEmpty()) {
                    errors.add("DEADLINE_CALENDAR_UNAVAILABLE:" + node.id());
                }
            });
        }
        errors.addAll(eventContracts.inspect(tenantId, graph));
        return List.copyOf(errors);
    }
}
