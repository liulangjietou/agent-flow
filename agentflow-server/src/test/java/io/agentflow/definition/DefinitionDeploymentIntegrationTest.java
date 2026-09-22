package io.agentflow.definition;

import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 使用真实 Flowable 仓储验证受限设计器生成的 BPMN 可以部署。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:definition-deployment;DB_CLOSE_DELAY=-1",
        "agentflow.auth.demo-enabled=true"
})
class DefinitionDeploymentIntegrationTest {
    @Autowired DefinitionApplicationService service;
    @Autowired RepositoryService repositoryService;

    @Test
    void publishesConditionalDefinitionToFlowable() {
        String key = "dynamic-" + UUID.randomUUID();
        DefinitionDraft draft = service.create("demo", key, "动态条件流程", new Graph(
                List.of(
                        new Node("start", "开始", NodeType.START, Map.of()),
                        new Node("gate", "金额判断", NodeType.EXCLUSIVE_GATEWAY, Map.of()),
                        new Node("low", "普通审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")),
                        new Node("high", "高额审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")),
                        new Node("end", "结束", NodeType.END, Map.of())),
                List.of(
                        new Edge("start-gate", "start", "gate", ""),
                        new Edge("gate-low", "gate", "low", "amount <= 5000"),
                        new Edge("gate-high", "gate", "high", "amount > 5000"),
                        new Edge("low-end", "low", "end", ""),
                        new Edge("high-end", "high", "end", ""))));

        service.publish("demo", draft.id(), 0);

        assertThat(repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(key).processDefinitionTenantId("demo").count()).isEqualTo(1);
    }
}
