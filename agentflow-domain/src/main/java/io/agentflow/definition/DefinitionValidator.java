package io.agentflow.definition;

import java.util.*;
import io.agentflow.form.FormSchema;
import io.agentflow.expense.ExpenseFormContract;
import io.agentflow.expense.ExpenseProcessPolicy;
import java.util.regex.Pattern;

import static io.agentflow.definition.DefinitionModels.*;

/**
 * 校验流程图的结构、节点职责和条件语法。
 * @author owlzhangfq@gmail.com
 */
public final class DefinitionValidator {
    private static final int MAX_SUBPROCESS_NODE_NAME_LENGTH = 200;
    private static final Pattern LITERAL_ASSIGNEE_RULE =
            Pattern.compile("(?:role|user):[\\p{L}\\p{N}_][\\p{L}\\p{N}_.@-]{0,127}");

    /** 返回全部校验问题；没有问题时返回空列表。 */
    public List<String> validate(Graph graph) {
        return validate(graph, null);
    }

    /** 在结构检查之外验证条件字段和表单类型。 */
    public List<String> validate(Graph graph, FormSchema formSchema) {
        return validate(graph, formSchema, null);
    }

    /** 图元素共享标识空间；已知流程标识时一并检查，避免部署时才发生冲突。 */
    public List<String> validate(Graph graph, FormSchema formSchema, String processKey) {
        List<String> errors = new ArrayList<>();
        Map<String, Node> nodes = new HashMap<>();
        for (Node n : graph.nodes()) {
            if (n.type() == NodeType.SUB_PROCESS) {
                if (n.id().length() > FormSchema.MAX_NODE_ID_LENGTH || n.name().length() > MAX_SUBPROCESS_NODE_NAME_LENGTH) errors.add("SUBPROCESS_NODE_LIMIT_EXCEEDED:" + n.id());
                try { SubprocessPolicy.fromProperties(n.properties()); }
                catch (io.agentflow.common.DomainException invalid) { errors.add(invalid.code() + ":" + n.id()); }
            }
            else if (SubprocessPolicy.hasProperties(n.properties())) errors.add("SUBPROCESS_REQUIRES_CALL_NODE:" + n.id());
            if (n.type() == NodeType.EVENT_WAIT) {
                if (n.id().length() > 128 || n.name().length() > 200) errors.add("EVENT_NODE_LIMIT_EXCEEDED:" + n.id());
                try { EventWaitPolicy.fromProperties(n.properties()); }
                catch (io.agentflow.common.DomainException invalid) { errors.add(invalid.code() + ":" + n.id()); }
            } else if (EventWaitPolicy.PROPERTY_KEYS.stream().anyMatch(n.properties()::containsKey)) errors.add("EVENT_REQUIRES_WAIT_NODE:" + n.id());
            if (n.type() == NodeType.TIMER_WAIT) {
                try { TimerWaitPolicy.fromProperties(n.properties()); }
                catch (io.agentflow.common.DomainException invalid) { errors.add(invalid.code() + ":" + n.id()); }
            } else if (n.properties().containsKey(TimerWaitPolicy.PROPERTY)) errors.add("TIMER_REQUIRES_WAIT_NODE:" + n.id());
            if (n.properties().containsKey(ExpenseProcessPolicy.PROPERTY)) {
                try { ExpenseProcessPolicy.stage(n); }
                catch (io.agentflow.common.DomainException invalid) { errors.add(invalid.code() + ":" + n.id()); }
                if (!ExpenseFormContract.structured(formSchema)) errors.add("EXPENSE_STAGE_REQUIRES_EXPENSE_FORM:" + n.id());
            }
            if (n.id().equals(processKey)) errors.add("PROCESS_KEY_CONFLICT:" + n.id());
            if (TaskDeadlinePolicy.PROPERTY_KEYS.stream().anyMatch(n.properties()::containsKey)) {
                if (n.type() != NodeType.USER_TASK) errors.add("DEADLINE_REQUIRES_USER_TASK:" + n.id());
                else {
                    try { TaskDeadlinePolicy.fromProperties(n.properties()); }
                    catch (io.agentflow.common.DomainException exception) { errors.add("DEADLINE_RULE_INVALID:" + n.id()); }
                }
            }
            if (TaskEscalationPolicy.PROPERTY_KEYS.stream().anyMatch(n.properties()::containsKey)) {
                if (n.type() != NodeType.USER_TASK) errors.add("ESCALATION_REQUIRES_USER_TASK:" + n.id());
                else {
                    if (TaskDeadlinePolicy.PROPERTY_KEYS.stream().noneMatch(n.properties()::containsKey)) errors.add("ESCALATION_REQUIRES_DEADLINE:" + n.id());
                    try { TaskEscalationPolicy.fromProperties(n.properties()); }
                    catch (io.agentflow.common.DomainException invalid) { errors.add(invalid.code() + ":" + n.id()); }
                }
            }
            if (n.properties().containsKey(ApprovalPolicy.MODE_PROPERTY) || n.properties().containsKey(ApprovalPolicy.PERCENTAGE_PROPERTY)) {
                if (n.type() != NodeType.USER_TASK) errors.add("APPROVAL_MODE_REQUIRES_USER_TASK:" + n.id());
                else {
                    try { n.approvalPolicy(); }
                    catch (io.agentflow.common.DomainException invalid) { errors.add(invalid.code() + ":" + n.id()); }
                }
            }
            if (nodes.put(n.id(), n) != null) errors.add("DUPLICATE_NODE:" + n.id());
            if (n.type() == NodeType.COPY && (n.id().length() > 128 || n.name().length() > 200)) {
                errors.add("COPY_NODE_LIMIT_EXCEEDED:" + n.id());
            }
            if (n.type() == NodeType.SERVICE_TASK) {
                errors.add("UNSUPPORTED_NODE_TYPE:" + n.id());
            }
            if (n.type() == NodeType.USER_TASK || n.type() == NodeType.COPY || n.type() == NodeType.TIMER_WAIT || n.type() == NodeType.EVENT_WAIT || n.type() == NodeType.SUB_PROCESS) {
                // Flowable 会对任务名称求值，业务标签必须保持字面量，不能成为访问 Spring Bean 的入口。
                if (n.name().contains("${") || n.name().contains("#{")) {
                    errors.add("TASK_NAME_EXPRESSION_FORBIDDEN:" + n.id());
                }
            }
            if (n.type() == NodeType.USER_TASK || n.type() == NodeType.COPY) {
                String assigneeRule = n.properties().get(n.type() == NodeType.COPY ? "recipientRule" : "assigneeRule");
                if (assigneeRule == null || assigneeRule.isBlank()) {
                    errors.add("ASSIGNEE_RULE_REQUIRED:" + n.id());
                } else if (!isLiteralAssigneeRule(assigneeRule)) {
                    errors.add("ASSIGNEE_RULE_INVALID:" + n.id());
                }
            }
        }
        if (formSchema != null) validateFieldNodes(formSchema.fields(), nodes, errors);
        long starts = graph.nodes().stream().filter(n -> n.type() == NodeType.START).count();
        long ends = graph.nodes().stream().filter(n -> n.type() == NodeType.END).count();
        if (starts != 1) errors.add("START_COUNT_MUST_BE_ONE");
        if (ends < 1) errors.add("END_REQUIRED");
        Set<String> incoming = new HashSet<>(), outgoing = new HashSet<>();
        Set<String> edgeIds = new HashSet<>();
        Map<String, List<Edge>> outgoingEdges = new HashMap<>();
        Map<String, Integer> incomingCounts = new HashMap<>();
        graph.edges().forEach(edge -> {
            outgoingEdges.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge);
            incomingCounts.merge(edge.target(), 1, Integer::sum);
        });
        Map<String, Integer> defaultBranches = new HashMap<>();
        ConditionParser parser = new ConditionParser();
        for (Edge e : graph.edges()) {
            if (!edgeIds.add(e.id())) errors.add("DUPLICATE_EDGE:" + e.id());
            if (nodes.containsKey(e.id())) errors.add("NODE_EDGE_ID_CONFLICT:" + e.id());
            if (e.id().equals(processKey)) errors.add("PROCESS_KEY_CONFLICT:" + e.id());
            if (!nodes.containsKey(e.source()) || !nodes.containsKey(e.target())) errors.add("EDGE_NODE_NOT_FOUND:" + e.id());
            outgoing.add(e.source()); incoming.add(e.target());
            try {
                ConditionAst condition = parser.parse(e.condition(), graph.conditionLanguageVersion());
                if (formSchema != null) formSchema.validateCondition(condition);
                else if (containsMembership(condition)) errors.add("CONDITION_MEMBERSHIP_REQUIRES_SCHEMA:" + e.id());
            } catch (ConditionSyntaxException ex) { errors.add("INVALID_CONDITION_AT:" + e.id() + ":" + ex.position());
            } catch (RuntimeException ex) { errors.add("INVALID_CONDITION:" + e.id()); }
            Node sourceNode = nodes.get(e.source());
            if (sourceNode != null && sourceNode.type() == NodeType.PARALLEL_GATEWAY && !e.condition().isBlank()) {
                errors.add("PARALLEL_CONDITION_FORBIDDEN:" + e.id());
            }
            if (sourceNode != null && sourceNode.type() == NodeType.EXCLUSIVE_GATEWAY
                    && outgoingEdges.get(sourceNode.id()).size() > 1
                    && !e.defaultBranch() && e.condition().isBlank()) {
                errors.add("GATEWAY_BRANCH_CONDITION_REQUIRED:" + e.id());
            }
            if (sourceNode != null && sourceNode.type() == NodeType.EXCLUSIVE_GATEWAY
                    && outgoingEdges.get(sourceNode.id()).size() == 1 && (!e.condition().isBlank() || e.defaultBranch())) {
                errors.add("GATEWAY_MERGE_EDGE_UNCONDITIONAL:" + e.id());
            }
            if (e.defaultBranch()) {
                defaultBranches.merge(e.source(), 1, Integer::sum);
                if (!e.condition().isBlank()) errors.add("DEFAULT_BRANCH_MUST_HAVE_NO_CONDITION:" + e.id());
                if (sourceNode != null && sourceNode.type() != NodeType.EXCLUSIVE_GATEWAY) {
                    errors.add("DEFAULT_BRANCH_REQUIRES_GATEWAY:" + e.id());
                }
            }
        }
        defaultBranches.forEach((source, count) -> {
            if (count > 1) errors.add("MULTIPLE_DEFAULT_BRANCHES:" + source);
        });
        for (Node n : graph.nodes()) {
            if (n.type() != NodeType.START && !incoming.contains(n.id())) errors.add("NODE_UNREACHABLE:" + n.id());
            int outgoingCount = outgoingEdges.getOrDefault(n.id(), List.of()).size();
            if (n.type() == NodeType.START && incoming.contains(n.id())) errors.add("START_MUST_HAVE_NO_INCOMING:" + n.id());
            if (n.type() == NodeType.END && outgoingCount != 0) errors.add("END_MUST_HAVE_NO_OUTGOING:" + n.id());
            // 并行必须显式建模，普通节点的多出线会在引擎中产生隐式并行。
            if ((n.type() == NodeType.START || n.type() == NodeType.USER_TASK || n.type() == NodeType.COPY
                    || n.type() == NodeType.TIMER_WAIT || n.type() == NodeType.EVENT_WAIT || n.type() == NodeType.SUB_PROCESS) && outgoingCount > 1) {
                errors.add("SINGLE_OUTGOING_REQUIRED:" + n.id());
            }
            if (n.type() != NodeType.END && !outgoing.contains(n.id())) errors.add("NODE_DEAD_END:" + n.id());
            // 多入单出是互斥路径的汇合，不再次判断条件，也不等待未选中的路径。
            if (n.type() == NodeType.EXCLUSIVE_GATEWAY && outgoingCount < 2
                    && !(outgoingCount == 1 && incomingCounts.getOrDefault(n.id(), 0) > 1)) {
                errors.add("GATEWAY_BRANCH_REQUIRED:" + n.id());
            }
        }
        Node start = graph.nodes().stream().filter(n -> n.type() == NodeType.START).findFirst().orElse(null);
        if (start != null && graph.nodes().stream().anyMatch(node -> node.type() == NodeType.COPY)) {
            // 抄送不是审批：含抄送的申请不能沿没有任何人工审批的路径直接结束。
            Set<String> visited = new HashSet<>();
            Deque<String> pending = new ArrayDeque<>(); pending.add(start.id());
            while (!pending.isEmpty()) {
                var node = nodes.get(pending.removeFirst());
                if (node == null || !visited.add(node.id()) || node.type() == NodeType.USER_TASK || node.type() == NodeType.SUB_PROCESS) continue;
                if (node.type() == NodeType.END) { errors.add("COPY_REQUIRES_APPROVAL_PATH:" + node.id()); break; }
                outgoingEdges.getOrDefault(node.id(), List.of()).stream().map(Edge::target).forEach(pending::addLast);
            }
        }
        if (start != null) {
            Set<String> reachable = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(start.id());
            while (!queue.isEmpty()) {
                String current = queue.removeFirst();
                if (!reachable.add(current)) continue;
                outgoingEdges.getOrDefault(current, List.of()).stream().map(Edge::target).forEach(queue::addLast);
            }
            for (Node n : graph.nodes()) {
                if (!reachable.contains(n.id()) && !errors.contains("NODE_UNREACHABLE:" + n.id())) {
                    errors.add("NODE_UNREACHABLE:" + n.id());
                }
            }
        }
        if (containsCycle(nodes.keySet(), outgoingEdges)) errors.add("GRAPH_LOOP");
        if (errors.isEmpty()) errors.addAll(new ParallelStructureValidator().validate(graph));
        if (errors.isEmpty() && graph.nodes().stream().anyMatch(n -> n.type() == NodeType.SUB_PROCESS)) {
            validateWaitApprovalPaths(graph, errors, "SUBPROCESS_REQUIRES_APPROVAL_PATH");
        }
        if (errors.isEmpty() && graph.nodes().stream().anyMatch(n -> n.type() == NodeType.TIMER_WAIT)) {
            validateWaitApprovalPaths(graph, errors, "TIMER_REQUIRES_APPROVAL_PATH");
        }
        if (errors.isEmpty() && graph.nodes().stream().anyMatch(n -> n.type() == NodeType.EVENT_WAIT)) {
            validateWaitApprovalPaths(graph, errors, "EVENT_REQUIRES_APPROVAL_PATH");
        }
        return List.copyOf(errors);
    }

    /** 审批、抄送和升级共用字面量语法；实际成员可用性由发布编排检查。 */
    static boolean isLiteralAssigneeRule(String value) {
        return value != null && LITERAL_ASSIGNEE_RULE.matcher(value).matches();
    }

    /** 并行汇合要求全部入口到达，因此另一分支的人工审批不能被误判为可绕过。 */
    private void validateWaitApprovalPaths(Graph graph, List<String> errors, String errorCode) {
        DefinitionApprovalPaths.endsWithoutApproval(graph).forEach(id -> errors.add(errorCode + ":" + id));
    }

    private boolean containsMembership(ConditionAst condition) {
        if (condition instanceof Membership) return true;
        if (condition instanceof Negation negation) return containsMembership(negation.term());
        return condition instanceof Logical logical && logical.terms().stream().anyMatch(this::containsMembership);
    }

    private boolean containsCycle(Set<String> nodeIds, Map<String, List<Edge>> outgoing) {
        Map<String, Integer> inDegree = new HashMap<>();
        nodeIds.forEach(id -> inDegree.put(id, 0));
        outgoing.forEach((source, edges) -> {
            if (nodeIds.contains(source)) edges.stream().filter(edge -> nodeIds.contains(edge.target()))
                    .forEach(edge -> inDegree.merge(edge.target(), 1, Integer::sum));
        });
        Deque<String> ready = new ArrayDeque<>();
        inDegree.forEach((id, count) -> { if (count == 0) ready.add(id); });
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            visited++;
            for (Edge edge : outgoing.getOrDefault(id, List.of())) {
                if (inDegree.containsKey(edge.target()) && inDegree.merge(edge.target(), -1, Integer::sum) == 0) ready.add(edge.target());
            }
        }
        return visited != nodeIds.size();
    }
    private static void validateFieldNodes(List<FormSchema.Field> fields, Map<String, Node> nodes, List<String> errors) {
        for (var field : fields) {
            if (field.nodeAccess() != null) for (String nodeId : field.nodeAccess().keySet()) {
                var node = nodes.get(nodeId);
                if (node == null || node.type() != NodeType.USER_TASK && node.type() != NodeType.COPY && node.type() != NodeType.SUB_PROCESS) errors.add("FIELD_PERMISSION_NODE_INVALID:" + field.key() + ":" + nodeId);
            }
            if (field.columns() != null) validateFieldNodes(field.columns(), nodes, errors);
        }
    }

}
