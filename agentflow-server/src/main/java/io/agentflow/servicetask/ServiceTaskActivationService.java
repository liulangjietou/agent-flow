package io.agentflow.servicetask;

import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionModels;
import io.agentflow.definition.ServiceTaskInputs;
import io.agentflow.definition.ServiceTaskPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * 节点激活在原流程事务中固定输入；首次轮次尚未落库时只入队，提交后再核验轮次并外发。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ServiceTaskActivationService {
    private final ApplicationRepository applications;
    private final ServiceTaskDefinitionSnapshots definitions;
    private final ServiceTaskCatalog catalog;
    private final JdbcServiceTaskOperationRepository operations;
    /** 跨目录、申请与命令的编排留在应用层。 */
    public ServiceTaskActivationService(ApplicationRepository applications, ServiceTaskDefinitionSnapshots definitions,
                                         ServiceTaskCatalog catalog, JdbcServiceTaskOperationRepository operations) {
        this.applications = applications; this.definitions = definitions; this.catalog = catalog; this.operations = operations;
    }

    /** 仅由实际引擎节点调用，不对 HTTP 暴露任意原命令登记入口。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void activate(String tenant, UUID applicationId, int roundNo, String runtimeDefinitionId, String processInstanceId,
                         String executionId, String nodeId, Map<String, Object> values) {
        var definition = definitions.forEngine(tenant, runtimeDefinitionId);
        var application = applications.findById(tenant, applicationId).orElseThrow(ServiceTaskActivationService::invalid);
        if (!application.processKey().equals(definition.key()) || application.definitionVersion() != definition.version()
                || application.runtimeDefinitionId() != null && !application.runtimeDefinitionId().equals(runtimeDefinitionId)) throw invalid();
        var node = definition.graph().node(nodeId);
        if (node == null || node.type() != DefinitionModels.NodeType.SERVICE_TASK) throw invalid();
        var policy = ServiceTaskPolicy.fromProperties(node.properties()); var installed = catalog.referenced(tenant, policy);
        var bound = ServiceTaskInputs.bind(policy, nodeId, definition.schema(), installed.contract());
        var origin = new ServiceTaskCommand.Binding(applicationId, roundNo, definition.key(), definition.version(), definition.digest(), processInstanceId, executionId, nodeId);
        var existing = operations.atWait(tenant, processInstanceId, executionId, nodeId);
        UUID id = existing.map(value -> value.operation().input().command().id()).orElseGet(UUID::randomUUID);
        var input = new ServiceTaskOperation.Input(bound.command(id, tenant, origin, values), installed.targetDigest());
        if (existing.isPresent()) {
            if (!existing.get().operation().input().equals(input)) throw invalid();
            return;
        }
        operations.create(ServiceTaskOperation.queue(input, Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }

    private static DomainException invalid() { return new DomainException("SERVICE_TASK_CONTEXT_INVALID", "Service task activation does not identify its original platform application and node"); }
}
