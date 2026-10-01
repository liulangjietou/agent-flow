package io.agentflow.definition;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.process.FlowableTaskFacade;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubprocessCallRepository;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flowable.bpmn.model.CallActivity;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 使用实际部署适配器生成调用活动；公开发布门禁仍关闭，夹具只绕过该阶段门禁。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.sla.reminders-enabled=false"})
class SubprocessDeploymentIntegrationTest {
    private static final Actor ADMIN = new Actor("demo", "admin", Set.of("ADMIN"));
    @Autowired DefinitionApplicationService definitions;
    @Autowired DefinitionDeploymentPort deployment;
    @Autowired DefinitionDraftRepository drafts;
    @Autowired DefinitionAvailabilityService availability;
    @Autowired io.agentflow.event.EventContractService contracts;
    @Autowired RepositoryService engine;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired FlowableTaskFacade actions;
    @Autowired ApprovalApplicationFacade applications;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired SubprocessCallRepository calls;
    @Autowired CurrentActor actors;
    @Autowired PlatformTransactionManager transactions;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_DEPLOYMENT_URL", "jdbc:h2:mem:subprocess-deployment;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_DEPLOYMENT_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_DEPLOYMENT_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_DEPLOYMENT_PASSWORD", ""));
    }

    @AfterEach void clearActor() { actors.clear(); }

    @Test
    void generatedCallStartsTheFixedVersionWithIndependentBusinessIdentity() {
        var first = child(key(), "total"); child(first.key(), "changed");
        var parent = deploy(key(), callGraph(first.key(), 1, "amount", "total"), schema("amount"));
        var callNode = (CallActivity) engine.getBpmnModel(nativeId(parent)).getMainProcess().getFlowElement("call");
        assertThat(callNode.getCalledElement()).isEqualTo(nativeId(first));
        assertThat(callNode.getCalledElementType()).isEqualTo("id");
        assertThat(callNode.isInheritVariables()).isFalse();
        assertThat(callNode.getFallbackToDefaultTenant()).isFalse();
        assertThat(callNode.getInParameters()).isEmpty();
        assertThat(callNode.getOutParameters()).isEmpty();
        actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE")));
        var application = applications.create("deploy-" + UUID.randomUUID(), parent.key(), parent.version(), "固定版本子流程", Map.of("amount", "12.30"));
        applications.submit(application.id(), 1);
        var call = calls.findByParentRound("demo", application.id(), 1).get(0);
        var child = applicationRepository.findById("demo", call.childApplicationId()).orElseThrow();
        assertThat(child.createdBy()).isEqualTo("alice");
        assertThat(child.definitionVersion()).isEqualTo(1);
        assertThat(child.payload()).containsExactlyEntriesOf(Map.of("total", "12.30"));
        assertThat(runtime.getVariables(call.childProcessInstanceId())).containsEntry("applicationId", child.id().toString())
                .containsEntry("formData", child.payload()).doesNotContainKey("amount");
        actors.set(new Actor("demo", "manager", Set.of("APPROVER")));
        actions.action(tasks.createTaskQuery().processInstanceId(call.childProcessInstanceId()).singleResult().getId(),
                "APPROVE", "子流程实际核对通过", null, child.version());
        var continued = applicationRepository.findById("demo", application.id()).orElseThrow();
        assertThat(continued.payload()).containsExactlyEntriesOf(Map.of("amount", "12.30"));
        actors.set(new Actor("demo", "finance", Set.of("APPROVER")));
        actions.action(tasks.createTaskQuery().processInstanceId(call.parentProcessInstanceId()).singleResult().getId(),
                "APPROVE", "父流程实际核对通过", null, continued.version());
        assertThat(applicationRepository.findById("demo", application.id()).orElseThrow().status())
                .isEqualTo(io.agentflow.approval.model.ApplicationStatus.APPROVED);
        assertThat(applicationRepository.findById("demo", child.id()).orElseThrow().status())
                .isEqualTo(io.agentflow.approval.model.ApplicationStatus.APPROVED);
    }

    @Test
    void rejectsDisabledNestedVersionAndRollsBackTheParentPublication() {
        var leaf = child(key(), "leafValue");
        var middle = deploy(key(), callGraph(leaf.key(), 1, "middleValue", "leafValue"), schema("middleValue"));
        availability.change(ADMIN, leaf.id(), leaf.revision(), false, "停用原依赖版本");
        String parentKey = key();
        failure(() -> deploy(parentKey, callGraph(middle.key(), 1, "amount", "middleValue"), schema("amount")), "DEFINITION_DISABLED");
        assertThat(drafts.findPublished("demo", parentKey, 1)).isEmpty();
        assertThat(engine.createProcessDefinitionQuery().processDefinitionTenantId("demo").processDefinitionKey(parentKey).count()).isZero();
    }

    @Test
    void deploymentNeverFallsBackToADraftAnotherTenantOrAnotherVersion() {
        String key = key();
        definitions.create("demo", key, "未发布的子审批", reviewGraph(), schema("total"));
        failure(() -> deploy(key(), callGraph(key, 1, "amount", "total"), schema("amount")), "SUBPROCESS_DEFINITION_UNAVAILABLE");
        child(key, "total");
        failure(() -> deploy(key(), callGraph(key, 2, "amount", "total"), schema("amount")), "SUBPROCESS_DEFINITION_UNAVAILABLE");
        var foreign = definitions.create("foreign", key(), "其他租户发布", reviewGraph(), schema("total"));
        drafts.save(published(foreign));
        failure(() -> deploy(key(), callGraph(foreign.key(), 1, "amount", "total"), schema("amount")), "SUBPROCESS_DEFINITION_UNAVAILABLE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"expenseDetails", "expensePlanDetails", "advanceRequestDetails", "procurementPaymentDetails", "budgetAdjustmentDetails"})
    void financialChildRequiresItsOwnBusinessSubmission(String field) {
        var financial = DefinitionDraft.create(UUID.randomUUID(), "demo", key(), "财务定义夹具", reviewGraph(), schema(field));
        drafts.save(financial); drafts.save(published(financial));
        failure(() -> deploy(key(), callGraph(financial.key(), 1, "amount", field), schema("amount")), "BUSINESS_ENDPOINT_REQUIRED");
    }

    @Test
    void rejectsMappingsThatWouldLoseSensitiveSourcePermissions() {
        var child = child(key(), "total");
        var source = new FormSchema(2, List.of(new FormSchema.Field("amount", "敏感金额", FormSchema.FieldType.NUMBER,
                true, null, null, null, null, null, null, null, true, Map.of("call", io.agentflow.form.FieldVisibility.READ_ONLY))));
        failure(() -> deploy(key(), callGraph(child.key(), 1, "amount", "total"), source), "SUBPROCESS_INPUT_SENSITIVITY_LOSS");
    }

    @Test
    void anotherVersionOfTheSameProcessKeyIsNotARecursiveCall() {
        var first = child(key(), "amount");
        var second = deploy(first.key(), callGraph(first.key(), 1, "amount", "amount"), schema("amount"));
        assertThat(second.version()).isEqualTo(2);
        assertThat(((CallActivity) engine.getBpmnModel(nativeId(second)).getMainProcess().getFlowElement("call")).getCalledElement())
                .isEqualTo(nativeId(first));
    }

    @Test
    void disablingAfterDeploymentStillBlocksActivationAndRollsBackTheSubmission() {
        var child = child(key(), "total");
        var parent = deploy(key(), callGraph(child.key(), 1, "amount", "total"), schema("amount"));
        availability.change(ADMIN, child.id(), child.revision(), false, "发布后停用原子版本");
        actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE")));
        var application = applications.create("disabled-" + UUID.randomUUID(), parent.key(), 1, "激活时复核", Map.of("amount", "10"));
        failure(() -> applications.submit(application.id(), 1), "DEFINITION_DISABLED");
        assertThat(applicationRepository.findById("demo", application.id()).orElseThrow().status())
                .isEqualTo(io.agentflow.approval.model.ApplicationStatus.DRAFT);
        assertThat(calls.findByParentRound("demo", application.id(), 1)).isEmpty();
        assertThat(runtime.createProcessInstanceQuery().processDefinitionId(nativeId(parent)).count()).isZero();
    }

    @Test
    void rejectsUnavailableReferencesInsideAnExistingChild() {
        String contractKey = key();
        var contract = contracts.publish(ADMIN, contractKey, 0, "子审批原事件", "erp", "GoodsAccepted", "固定事件验收");
        var nodes = new java.util.ArrayList<>(reviewGraph().nodes());
        nodes.add(new Node("wait", "等待原事件", NodeType.EVENT_WAIT, Map.of("eventContractKey", contractKey, "eventContractVersion", "1")));
        var graph = new Graph(nodes, List.of(new Edge("a", "start", "review", ""), new Edge("b", "review", "wait", ""), new Edge("c", "wait", "end", "")));
        var child = definitions.create("demo", key(), "含事件的子审批", graph, schema("total"));
        definitions.publish(ADMIN, child.id(), 0, "发布原事件依赖");
        contracts.changeAvailability(ADMIN, contractKey, 1, contract.availability().revision(), false, "停用子审批依赖的原事件");
        assertThatThrownBy(() -> deploy(key(), callGraph(child.key(), 1, "amount", "total"), schema("amount")))
                .isInstanceOfSatisfying(DefinitionValidationException.class,
                        error -> assertThat(error.errors()).contains("EVENT_CONTRACT_UNAVAILABLE:wait"));
    }

    @Test
    void publicCreationRemainsClosedUntilTheCompleteSubprocessFeatureIsAccepted() {
        var child = child(key(), "total");
        assertThatThrownBy(() -> definitions.create("demo", key(), "待完整验收", callGraph(child.key(), 1, "amount", "total"), schema("amount")))
                .isInstanceOfSatisfying(DefinitionValidationException.class,
                        error -> assertThat(error.errors()).contains("SUBPROCESS_RUNTIME_NOT_READY:call"));
        assertThat(definitions.validate("demo", callGraph(child.key(), 1, "amount", "total"), schema("amount")))
                .contains("SUBPROCESS_RUNTIME_NOT_READY:call");
    }

    @Test
    void designPreflightReportsMissingDependenciesWhileKeepingThePublicationGateClosed() {
        var graph = callGraph(key(), 1, "amount", "total");
        var result = definitions.inspect("demo", graph, schema("amount"), key());
        assertThat(result.errors()).contains("SUBPROCESS_DEFINITION_UNAVAILABLE:call", "SUBPROCESS_RUNTIME_NOT_READY:call");
        assertThat(definitions.inspect(graph, schema("amount"), key()).errors()).containsExactly("SUBPROCESS_RUNTIME_NOT_READY:call");
    }

    @Test
    void preflightRejectsAChildWithoutAnActualApprovalPath() {
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "end", "")));
        var child = definitions.create("demo", key(), "没有人工审批的旧定义", graph, schema("total"));
        definitions.publish(ADMIN, child.id(), 0, "保持既有普通定义能力的夹具");
        assertThat(definitions.inspect("demo", callGraph(child.key(), 1, "amount", "total"), schema("amount"), key()).errors())
                .contains("SUBPROCESS_REQUIRES_APPROVAL_PATH:call");
    }

    private DefinitionDraft child(String key, String field) {
        var draft = definitions.create("demo", key, "独立子审批", reviewGraph(), schema(field));
        return definitions.publish(ADMIN, draft.id(), 0, "固定版本依赖验收");
    }

    private DefinitionDraft deploy(String key, Graph graph, FormSchema schema) {
        return new TransactionTemplate(transactions).execute(status -> {
            var draft = DefinitionDraft.create(UUID.randomUUID(), "demo", key, "父审批", graph, schema);
            drafts.save(draft); draft.publish(0, drafts.nextVersion("demo", key)); drafts.save(draft);
            deployment.deploy(draft);
            return draft;
        });
    }

    private DefinitionDraft published(DefinitionDraft draft) { draft.publish(0, 1); return draft; }

    private Graph callGraph(String key, long version, String source, String target) {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("call", "固定版本调用", NodeType.SUB_PROCESS, new SubprocessPolicy(key, version, Map.of(target, source)).properties()),
                new Node("review", "父级确认", NodeType.USER_TASK, Map.of("assigneeRule", "user:finance")),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "call", ""), new Edge("b", "call", "review", ""), new Edge("c", "review", "end", "")));
    }

    private Graph reviewGraph() {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "人工审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:manager")), new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "review", ""), new Edge("b", "review", "end", "")));
    }

    private FormSchema schema(String field) {
        return new FormSchema(2, List.of(new FormSchema.Field(field, field, FormSchema.FieldType.NUMBER, true, null, null, null, null, null)));
    }
    private String nativeId(DefinitionDraft definition) {
        return engine.createProcessDefinitionQuery().processDefinitionTenantId("demo").processDefinitionKey(definition.key())
                .processDefinitionVersion((int) definition.version()).singleResult().getId();
    }
    private String key() { return "subdeploy" + UUID.randomUUID().toString().replace("-", ""); }
    private void failure(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
