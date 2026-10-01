package io.agentflow.definition;

import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static io.agentflow.definition.DefinitionModels.*;

/**
 * 发布时遍历同租户的固定版本依赖，校验每条输入边，向引擎适配器交付根图的真实定义编号。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SubprocessDeploymentBindings {
    private static final int MAX_DEPENDENCIES = 256;
    private static final int MAX_CALL_NODES = 4096;
    private final SubprocessDefinitionResolver resolver;
    private final DefinitionReferenceInspector references;

    /** 固定定义解析与实际启动复用同一输入和业务入口约束。 */
    public SubprocessDeploymentBindings(SubprocessDefinitionResolver resolver, DefinitionReferenceInspector references) {
        this.resolver = resolver;
        this.references = references;
    }

    /** 只检查发布依赖，不启动子申请；任何缺失均使原发布事务回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, String> bind(DefinitionDraft draft) {
        var traversal = new Traversal(draft.tenantId());
        var ancestors = new HashSet<Reference>();
        ancestors.add(new Reference(draft.key(), draft.version()));
        traversal.visit(draft.graph(), draft.formSchema(), 0, ancestors);
        return Map.copyOf(traversal.rootBindings);
    }

    /**
     * 每次部署独占遍历状态；共享后代只展开一次，但每条进入边都重新验证自己的字段映射。
     * @author owlzhangfq@gmail.com
     */
    private final class Traversal {
        private final String tenant;
        private final Map<Reference, SubprocessDefinitionResolver.Bound> targets = new HashMap<>();
        private final Map<Reference, Integer> heights = new HashMap<>();
        private final Map<String, String> rootBindings = new LinkedHashMap<>();
        private int calls;

        private Traversal(String tenant) { this.tenant = tenant; }

        private int visit(Graph graph, FormSchema schema, int depth, Set<Reference> ancestors) {
            int height = 0;
            for (Node node : graph.nodes()) {
                if (node.type() != NodeType.SUB_PROCESS) continue;
                if (++calls > MAX_CALL_NODES) throw limit();
                if (depth >= SubprocessPolicy.MAX_CALL_DEPTH) throw depthExceeded();
                var policy = SubprocessPolicy.fromProperties(node.properties());
                var reference = new Reference(policy.processKey(), policy.version());
                if (!ancestors.add(reference)) {
                    throw new DomainException("SUBPROCESS_RECURSION_FORBIDDEN", "A subprocess cannot recursively call an ancestor version");
                }
                var target = targets.get(reference);
                if (target == null) {
                    if (targets.size() >= MAX_DEPENDENCIES) throw limit();
                    target = resolver.inspect(tenant, policy, node.id(), schema);
                    var errors = references.inspect(tenant, target.graph());
                    if (!errors.isEmpty()) throw new DefinitionValidationException(errors);
                    targets.put(reference, target);
                } else {
                    SubprocessInputs.bind(policy, node.id(), schema, target.formSchema());
                }
                if (depth == 0) rootBindings.put(node.id(), target.runtimeDefinitionId());
                Integer childHeight = heights.get(reference);
                if (childHeight == null) {
                    childHeight = visit(target.graph(), target.formSchema(), depth + 1, ancestors);
                    heights.put(reference, childHeight);
                }
                // 共享后代可能从更深的路径再次进入，不能因缓存跳过完整路径的深度约束。
                if (depth + 1 + childHeight > SubprocessPolicy.MAX_CALL_DEPTH) throw depthExceeded();
                height = Math.max(height, 1 + childHeight);
                ancestors.remove(reference);
            }
            return height;
        }
    }

    private static DomainException depthExceeded() {
        return new DomainException("SUBPROCESS_DEPTH_EXCEEDED", "Subprocess nesting exceeds the supported depth");
    }
    private static DomainException limit() {
        return new DomainException("SUBPROCESS_DEPENDENCY_LIMIT_EXCEEDED", "Subprocess dependency graph exceeds the supported size");
    }

    /** @author owlzhangfq@gmail.com */
    private record Reference(String key, long version) { }
}
