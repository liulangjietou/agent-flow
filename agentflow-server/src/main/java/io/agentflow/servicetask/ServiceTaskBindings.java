package io.agentflow.servicetask;

import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionModels.Graph;
import io.agentflow.definition.DefinitionModels.Node;
import io.agentflow.definition.DefinitionModels.NodeType;
import io.agentflow.definition.DefinitionValidationException;
import io.agentflow.definition.ServiceTaskInputs;
import io.agentflow.definition.ServiceTaskPolicy;
import io.agentflow.form.FormSchema;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 目录、字段权限与原参数绑定的跨聚合检查；发布、模拟和两类发起入口共用同一语义。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ServiceTaskBindings {
    private final ServiceTaskCatalog catalog;

    /** 目录只读，检查不安装契约、不访问远端，也不创建执行命令。 */
    public ServiceTaskBindings(ServiceTaskCatalog catalog) { this.catalog = catalog; }

    /** 草稿可保留已停用的原引用继续修订，但不能凭空声明操作或绕过字段权限。 */
    public void requireDeclared(String tenant, Graph graph, FormSchema schema) {
        requireNoErrors(inspect(tenant, graph, schema, false, null));
    }

    /** 发布就绪错误定位到节点，包含当前部署明确启用的原契约及原目标。 */
    public List<String> inspect(String tenant, Graph graph, FormSchema schema) {
        return inspect(tenant, graph, schema, true, null);
    }

    /** 发起及模拟预先检查所有声明节点的实际映射值，避免后续审批进入节点时才发现缺项。 */
    public void requireReady(String tenant, Graph graph, FormSchema schema, Map<String, Object> values) {
        requireNoErrors(inspect(tenant, graph, schema, true, values));
    }

    private List<String> inspect(String tenant, Graph graph, FormSchema schema, boolean requireAvailable, Map<String, Object> values) {
        var errors = new ArrayList<String>();
        for (Node node : graph.nodes()) {
            if (node.type() != NodeType.SERVICE_TASK) continue;
            try {
                var policy = ServiceTaskPolicy.fromProperties(node.properties());
                var installed = catalog.referenced(tenant, policy);
                var inputs = ServiceTaskInputs.bind(policy, node.id(), schema, installed.contract());
                if (requireAvailable && !catalog.available(tenant, installed)) {
                    errors.add("SERVICE_TASK_CONTRACT_UNAVAILABLE:" + node.id());
                } else if (values != null) inputs.validateSource(values);
            } catch (DomainException invalid) {
                errors.add(invalid.code() + ":" + node.id());
            }
        }
        return List.copyOf(errors);
    }

    private static void requireNoErrors(List<String> errors) {
        if (!errors.isEmpty()) throw new DefinitionValidationException(errors);
    }
}
