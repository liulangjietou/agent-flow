package io.agentflow.definition;

import io.agentflow.common.DomainException;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 Flowable 仓储验证受限设计器生成的 BPMN 可以部署。
 * @author owlzhangfq@gmail.com
 */
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

    @Test
    void allocatesNextVersionForAnotherDraftWithSameKey() {
        String key = "versioned-" + UUID.randomUUID();
        Graph graph = new Graph(List.of(
                new Node("start", "开始", NodeType.START, Map.of()),
                new Node("approve", "审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("start-approve", "start", "approve", ""),
                        new Edge("approve-end", "approve", "end", "")));

        DefinitionDraft first = service.create("demo", key, "第一版", graph);
        DefinitionDraft firstPublished = service.publish("demo", first.id(), 0);
        DefinitionDraft second = service.create("demo", key, "第二版", graph);
        DefinitionDraft secondPublished = service.publish("demo", second.id(), 0);

        assertThat(firstPublished.version()).isEqualTo(1);
        assertThat(secondPublished.version()).isEqualTo(2);
        assertThat(repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(key).processDefinitionTenantId("demo").count()).isEqualTo(2);
    }

    @Test
    void keepsBundledAndTenantVersionsIndependentForTheSameKey() {
        String key = "expense-reimbursement";
        var bundled = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(key).processDefinitionWithoutTenantId().singleResult();
        DefinitionDraft draft = service.create("demo", key, "租户报销流程", approvalGraph("租户审批节点"));

        DefinitionDraft published = service.publish("demo", draft.id(), 0);

        var tenantDefinition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(key).processDefinitionTenantId("demo").singleResult();
        assertThat(bundled.getVersion()).isEqualTo(1);
        assertThat(published.version()).isEqualTo(1);
        assertThat(tenantDefinition.getVersion()).isEqualTo(1);
        assertThat(tenantDefinition.getId()).isNotEqualTo(bundled.getId());
        assertThat(repositoryService.getBpmnModel(tenantDefinition.getId()).getMainProcess()
                .getFlowElement("approve").getName()).isEqualTo("租户审批节点");
    }

    @Test
    void rejectsAnUnrelatedExistingEngineVersionWithoutPublishingTheNewGraph() {
        String key = "occupied-" + UUID.randomUUID();
        DefinitionDraft unrelated = DefinitionDraft.create(UUID.randomUUID(), "demo", key, "已有引擎流程", approvalGraph("已有审批节点"));
        var existingDeployment = repositoryService.createDeployment().tenantId("demo")
                .addString("existing.bpmn20.xml", FlowableDefinitionDeploymentAdapter.RestrictedBpmnWriter.write(unrelated))
                .deploy();
        DefinitionDraft draft = service.create("demo", key, "新的平台流程", approvalGraph("新的审批节点"));

        assertThatThrownBy(() -> service.publish("demo", draft.id(), 0))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("DEFINITION_DEPLOYMENT_CONFLICT"));

        assertUnpublished(draft);
        var definitions = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(key).processDefinitionTenantId("demo").list();
        assertThat(definitions).hasSize(1);
        assertThat(definitions.get(0).getDeploymentId()).isEqualTo(existingDeployment.getId());
        assertThat(repositoryService.createDeploymentQuery().deploymentKey(key).deploymentTenantId("demo").count()).isZero();
    }

    @Test
    void rollsBackBothPlatformAndEngineWhenTheirVersionSequencesDiverge() {
        String key = "removed-history-" + UUID.randomUUID();
        DefinitionDraft first = service.create("demo", key, "第一版", approvalGraph("第一版审批"));
        service.publish("demo", first.id(), 0);
        var firstEngineDefinition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(key).processDefinitionTenantId("demo").singleResult();
        repositoryService.deleteDeployment(firstEngineDefinition.getDeploymentId(), true);
        DefinitionDraft nextDraft = service.create("demo", key, "第二版", approvalGraph("第二版审批"));

        assertThatThrownBy(() -> service.publish("demo", nextDraft.id(), 0))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("DEFINITION_DEPLOYMENT_CONFLICT"));

        assertUnpublished(nextDraft);
        assertThat(service.get("demo", first.id()).version()).isEqualTo(1);
        assertThat(repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(key).processDefinitionTenantId("demo").count()).isZero();
        assertThat(repositoryService.createDeploymentQuery().deploymentKey(key).deploymentTenantId("demo").count()).isZero();
    }

    private void assertUnpublished(DefinitionDraft draft) {
        DefinitionDraft persisted = service.get("demo", draft.id());
        assertThat(persisted.status()).isEqualTo(DraftStatus.DRAFT);
        assertThat(persisted.version()).isZero();
        assertThat(persisted.revision()).isZero();
    }

    private Graph approvalGraph(String taskName) {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("approve", taskName, NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("start-approve", "start", "approve", ""),
                        new Edge("approve-end", "approve", "end", "")));
    }
}
