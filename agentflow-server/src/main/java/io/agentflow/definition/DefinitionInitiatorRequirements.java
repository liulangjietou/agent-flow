package io.agentflow.definition;

import io.agentflow.common.DomainException;
import io.agentflow.organization.LocalOrganizationDirectory;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import static io.agentflow.definition.DefinitionModels.*;

/**
 * 根轮次必须提前固定所有后代可能使用的任职；查询只读取精确发布快照，不重新选人或修改启停状态。
 * @author owlzhangfq@gmail.com
 */
@Service
public class DefinitionInitiatorRequirements {
    private final DefinitionDraftRepository definitions;

    /** 使用同租户发布仓储，不能从审批人的当前任职补造申请人上下文。 */
    public DefinitionInitiatorRequirements(DefinitionDraftRepository definitions) { this.definitions = definitions; }

    /** 任意已声明路径需要动态主管或负责人时，根申请就必须选择任职。 */
    public boolean required(String tenantId, Graph graph) {
        return new Traversal(tenantId).required(graph, 0, new HashSet<>());
    }

    /**
     * 只返回固定版本与任职要求，不返回后代图、组织目录或候选人。
     * @author owlzhangfq@gmail.com
     */
    public record View(String processKey, long definitionVersion, boolean appointmentRequired) { }

    /** @author owlzhangfq@gmail.com */
    private final class Traversal {
        private final String tenant;
        private final Map<Reference, Boolean> requirements = new HashMap<>();
        private int calls;
        private int definitionsRead;
        private Traversal(String tenant) { this.tenant = tenant; }

        private boolean required(Graph graph, int depth, Set<Reference> ancestors) {
            if (graph.nodes().stream().anyMatch(node -> LocalOrganizationDirectory.isContextualRule(node.properties().get("assigneeRule"))
                    || LocalOrganizationDirectory.isContextualRule(node.properties().get("recipientRule"))
                    || LocalOrganizationDirectory.isContextualRule(node.properties().get(TaskEscalationPolicy.RECIPIENT_RULE)))) return true;
            for (var node : graph.nodes()) {
                if (node.type() != NodeType.SUB_PROCESS) continue;
                if (++calls > SubprocessPolicy.MAX_CALL_NODES) {
                    throw new DomainException("SUBPROCESS_DEPENDENCY_LIMIT_EXCEEDED", "Subprocess dependency graph exceeds the supported size");
                }
                if (depth >= SubprocessPolicy.MAX_CALL_DEPTH) throw new DomainException("SUBPROCESS_DEPTH_EXCEEDED", "Subprocess nesting exceeds the supported depth");
                var policy = SubprocessPolicy.fromProperties(node.properties());
                var reference = new Reference(policy.processKey(), policy.version());
                if (!ancestors.add(reference)) throw new DomainException("SUBPROCESS_RECURSION_FORBIDDEN", "A subprocess cannot recursively call an ancestor version");
                Boolean required = requirements.get(reference);
                if (required == null) {
                    if (++definitionsRead > SubprocessPolicy.MAX_DEPENDENCIES) {
                        throw new DomainException("SUBPROCESS_DEPENDENCY_LIMIT_EXCEEDED", "Subprocess dependency graph exceeds the supported size");
                    }
                    var target = definitions.findPublished(tenant, policy.processKey(), policy.version()).orElseThrow(() ->
                            new DomainException("SUBPROCESS_DEFINITION_UNAVAILABLE", "The referenced published subprocess version is unavailable"));
                    required = required(target.graph(), depth + 1, ancestors);
                    requirements.put(reference, required);
                }
                ancestors.remove(reference);
                if (required) return true;
            }
            return false;
        }
    }

    /** @author owlzhangfq@gmail.com */
    private record Reference(String key, long version) { }
}
