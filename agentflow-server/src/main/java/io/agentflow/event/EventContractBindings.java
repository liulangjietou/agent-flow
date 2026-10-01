package io.agentflow.event;

import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionModels;
import io.agentflow.definition.EventWaitPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 发布与新轮次发起共享契约可用性规则；依赖其他聚合的检查不放进流程图或引擎表达式。
 * @author owlzhangfq@gmail.com
 */
@Service
public class EventContractBindings {
    private final EventContractRepository contracts;

    /** 只读取已发布的精确版本，来源名称或最新版不能代替引用。 */
    public EventContractBindings(EventContractRepository contracts) { this.contracts = contracts; }

    /** 设计预检返回具体节点问题，纯结构合法后才调用目录。 */
    public List<String> inspect(String tenantId, DefinitionModels.Graph graph) {
        var errors = new ArrayList<String>();
        graph.nodes().stream().filter(node -> node.type() == DefinitionModels.NodeType.EVENT_WAIT).forEach(node -> {
            var policy = EventWaitPolicy.fromProperties(node.properties());
            var contract = contracts.find(tenantId, policy.contractKey(), policy.contractVersion());
            if (contract.isEmpty() || !contract.get().availability().enabled()) errors.add("EVENT_CONTRACT_UNAVAILABLE:" + node.id());
        });
        return List.copyOf(errors);
    }

    /** 发布及新轮次启动锁定全部引用，按稳定顺序避免不同流程的多契约锁顺序相反。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAvailable(String tenantId, DefinitionModels.Graph graph) {
        var policies = graph.nodes().stream().filter(node -> node.type() == DefinitionModels.NodeType.EVENT_WAIT)
                .map(node -> EventWaitPolicy.fromProperties(node.properties())).distinct()
                .sorted(Comparator.comparing(EventWaitPolicy::contractKey).thenComparingLong(EventWaitPolicy::contractVersion)).toList();
        for (var policy : policies) {
            var value = contracts.lockVersion(tenantId, policy.contractKey(), policy.contractVersion());
            if (value.isEmpty() || !value.get().availability().enabled()) {
                throw new DomainException("EVENT_CONTRACT_UNAVAILABLE", "The referenced event contract version is unavailable");
            }
        }
    }
}
