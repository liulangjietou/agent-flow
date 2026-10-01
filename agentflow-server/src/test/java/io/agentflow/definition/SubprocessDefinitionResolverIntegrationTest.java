package io.agentflow.definition;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 通过真实发布仓储和 Flowable 证明固定版本、同租户来源及停用互斥，不以模拟目录代替绑定验收。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.auth.headers-enabled=false"})
class SubprocessDefinitionResolverIntegrationTest {
    @Autowired SubprocessDefinitionResolver resolver;
    @Autowired DefinitionApplicationService definitions;
    @Autowired DefinitionAvailabilityService availability;
    @Autowired DefinitionDraftRepository drafts;
    @Autowired RepositoryService engine;
    @Autowired org.flowable.engine.RuntimeService runtime;
    @Autowired org.flowable.engine.TaskService tasks;
    @Autowired PlatformTransactionManager transactions;
    private static final Actor ADMIN = new Actor("demo", "admin", Set.of("ADMIN"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_URL", "jdbc:h2:mem:subprocess-definitions;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_PASSWORD", ""));
    }

    @Test
    void bindsTheRequestedPublishedVersionAfterANewerVersionIsPublished() {
        String key = key(); var first = publish(key, "原版本", schema("total"));
        var second = publish(key, "新版本", schema("changed"));
        var bound = resolve("demo", new SubprocessPolicy(key, 1, Map.of("total", "amount")), schema("amount"));
        assertThat(bound.definitionId()).isEqualTo(first.id()).isNotEqualTo(second.id());
        assertThat(bound.version()).isEqualTo(1);
        assertThat(bound.inputs().project(Map.of("amount", "12.300")).values()).containsExactlyEntriesOf(Map.of("total", "12.300"));
        var nativeDefinition = engine.getProcessDefinition(bound.runtimeDefinitionId());
        assertThat(nativeDefinition.getTenantId()).isEqualTo("demo");
        assertThat(nativeDefinition.getKey()).isEqualTo(key);
        assertThat(nativeDefinition.getVersion()).isEqualTo(1);
        assertThat(bound.formSchema().fieldTypes()).containsOnlyKeys("total");
    }

    @Test
    void neverUsesADraftANewerVersionOrAnotherTenantAsFallback() {
        String key = key();
        definitions.create("demo", key, "未发布", graph(), schema("total"));
        failure(() -> resolve("demo", new SubprocessPolicy(key, 1, Map.of("total", "amount")), schema("amount")), "SUBPROCESS_DEFINITION_UNAVAILABLE");
        publish(key, "本租户发布", schema("total"));
        failure(() -> resolve("another-tenant", new SubprocessPolicy(key, 1, Map.of("total", "amount")), schema("amount")), "SUBPROCESS_DEFINITION_UNAVAILABLE");
        failure(() -> resolve("demo", new SubprocessPolicy(key, 2, Map.of("total", "amount")), schema("amount")), "SUBPROCESS_DEFINITION_UNAVAILABLE");
    }

    @Test
    void requiresRestoringTheOriginalVersionEvenWhenANewerOneIsEnabled() {
        String key = key(); var first = publish(key, "第一版", schema("total"));
        publish(key, "第二版", schema("total"));
        var disabled = availability.change(ADMIN, first.id(), first.revision(), false, "本地验收：暂停原版调用");
        var policy = new SubprocessPolicy(key, 1, Map.of("total", "amount"));
        failure(() -> resolve("demo", policy, schema("amount")), "DEFINITION_DISABLED");
        availability.change(ADMIN, first.id(), disabled.revision(), true, "本地验收：恢复原版调用");
        assertThat(resolve("demo", policy, schema("amount")).definitionId()).isEqualTo(first.id());
    }

    @Test
    void sourceAndTargetContractsAreCheckedBeforeAnEngineBindingIsReturned() {
        String key = key(); publish(key, "固定输入", schema("total"));
        failure(() -> resolve("demo", new SubprocessPolicy(key, 1, Map.of()), schema("amount")), "SUBPROCESS_REQUIRED_INPUT_MISSING");
        failure(() -> resolve("demo", new SubprocessPolicy(key, 1, Map.of("total", "unknown")), schema("amount")), "SUBPROCESS_INPUT_FIELD_UNKNOWN");
    }

    @Test
    void aPlatformVersionDoesNotAuthorizeAnUnrelatedBundledEngineDefinition() {
        var draft = DefinitionDraft.create(UUID.randomUUID(), "isolated-tenant", "expense-reimbursement", "同名平台引用", graph());
        drafts.save(draft); draft.publish(0, 1); drafts.save(draft);
        failure(() -> resolve("isolated-tenant", new SubprocessPolicy(draft.key(), 1, Map.of()), null), "PROCESS_DEFINITION_NOT_FOUND");
    }

    @Test
    void financialFormsCannotSkipTheirBusinessSubmissionGuards() {
        String key = key();
        var schema = new FormSchema(1, List.of(new FormSchema.Field(io.agentflow.expense.ExpenseFormContract.DETAILS,
                "财务原件", FormSchema.FieldType.TEXT, true, null, null, null, null, null)));
        // 此记录模拟已存在的财务发布快照；解析应在引擎查找前拒绝普通子流程发起。
        var draft = DefinitionDraft.create(UUID.randomUUID(), "demo", key, "财务绑定", graph(), schema);
        drafts.save(draft); draft.publish(0, 1); drafts.save(draft);
        failure(() -> resolve("demo", new SubprocessPolicy(key, 1, Map.of()), null), "BUSINESS_ENDPOINT_REQUIRED");
    }

    @Test
    void exactVersionLockIsHeldUntilTheInvokingTransactionEnds() throws Exception {
        String key = key(); var definition = publish(key, "锁定原版", schema("total"));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var binding = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                var result = resolver.resolve("demo", new SubprocessPolicy(key, 1, Map.of("total", "amount")), "call", schema("amount"));
                entered.countDown();
                try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Release deadline exceeded"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                return result;
            }));
            assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var changing = new java.util.concurrent.CountDownLatch(1);
            var disabling = executor.submit(() -> { changing.countDown(); return availability.change(ADMIN, definition.id(), definition.revision(), false, "本地验收：并发停用原版"); });
            assertThat(changing.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> disabling.get(250, java.util.concurrent.TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            assertThat(binding.get(10, java.util.concurrent.TimeUnit.SECONDS).definitionId()).isEqualTo(definition.id());
            assertThat(disabling.get(10, java.util.concurrent.TimeUnit.SECONDS).startEnabled()).isFalse();
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void nativeCallActivityUsesTheBoundIdAndDoesNotInheritTheParentVariables() {
        String key = key(); publish(key, "子流程第一版", schema("total")); publish(key, "子流程第二版", schema("newField"));
        var bound = resolve("demo", new SubprocessPolicy(key, 1, Map.of("total", "amount")), schema("amount"));
        String parentId = startNativeCall(bound);
        try {
            var child = runtime.createProcessInstanceQuery().superProcessInstanceId(parentId).singleResult();
            assertThat(child.getProcessDefinitionId()).isEqualTo(bound.runtimeDefinitionId());
            assertThat(child.getTenantId()).isEqualTo("demo");
            assertThat(runtime.getVariables(child.getId())).containsEntry("tenantId", "demo").containsEntry("formData", Map.of("total", "12.300"))
                    .doesNotContainKeys("parentSecret", "childData");
            assertThat(tasks.createTaskQuery().processInstanceId(parentId).count()).isZero();
            var childTask = tasks.createTaskQuery().processInstanceId(child.getId()).singleResult();
            tasks.complete(childTask.getId());
            assertThat(runtime.createProcessInstanceQuery().processInstanceId(child.getId()).count()).isZero();
            assertThat(tasks.createTaskQuery().processInstanceId(parentId).singleResult().getTaskDefinitionKey()).isEqualTo("parentReview");
            assertThat(runtime.getVariable(parentId, "formData")).isEqualTo(Map.of("amount", "999"));
        } finally { runtime.deleteProcessInstance(parentId, "Local subprocess engine contract verification completed"); }
    }

    @Test
    void nativeParentSuspensionRequiresExplicitChildCoordinationButDeletionCascades() {
        String key = key(); publish(key, "暂停范围验证", schema("total"));
        var bound = resolve("demo", new SubprocessPolicy(key, 1, Map.of("total", "amount")), schema("amount"));
        String parentId = startNativeCall(bound);
        var child = runtime.createProcessInstanceQuery().superProcessInstanceId(parentId).singleResult();
        try {
            runtime.suspendProcessInstanceById(parentId);
            assertThat(runtime.createProcessInstanceQuery().processInstanceId(parentId).singleResult().isSuspended()).isTrue();
            // 引擎的 child executions 不等于被调用的独立 process instance；平台必须逐个协调暂停和 SLA。
            assertThat(runtime.createProcessInstanceQuery().processInstanceId(child.getId()).singleResult().isSuspended()).isFalse();
            assertThat(tasks.createTaskQuery().processInstanceId(child.getId()).singleResult().isSuspended()).isFalse();
            runtime.suspendProcessInstanceById(child.getId());
            assertThat(tasks.createTaskQuery().processInstanceId(child.getId()).singleResult().isSuspended()).isTrue();
            runtime.activateProcessInstanceById(parentId); runtime.activateProcessInstanceById(child.getId());
        } finally { runtime.deleteProcessInstance(parentId, "Local subprocess cascade verification completed"); }
        assertThat(runtime.createProcessInstanceQuery().processInstanceId(child.getId()).count()).isZero();
        assertThat(tasks.createTaskQuery().processInstanceId(child.getId()).count()).isZero();
    }

    /** 原生协议探针尚不创建平台父子申请；固定版本和变量隔离结论不能冒充完整审批功能验收。 */
    private String startNativeCall(SubprocessDefinitionResolver.Bound bound) {
        String key = key();
        String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://agentflow.io/test">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/><sequenceFlow id="a" sourceRef="start" targetRef="call"/>
                    <callActivity id="call" calledElement="%s" flowable:calledElementType="id" flowable:inheritVariables="false" flowable:fallbackToDefaultTenant="false">
                      <extensionElements><flowable:in source="tenantId" target="tenantId"/><flowable:in source="childData" target="formData"/></extensionElements>
                    </callActivity>
                    <sequenceFlow id="b" sourceRef="call" targetRef="parentReview"/>
                    <userTask id="parentReview" name="父流程人工确认" flowable:assignee="finance"/>
                    <sequenceFlow id="c" sourceRef="parentReview" targetRef="end"/><endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, bound.runtimeDefinitionId());
        var deployment = engine.createDeployment().tenantId("demo").addString(key + ".bpmn20.xml", bpmn).deploy();
        String parentDefinitionId = engine.createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult().getId();
        return runtime.createProcessInstanceBuilder().processDefinitionId(parentDefinitionId).tenantId("demo")
                .variables(Map.of("tenantId", "demo", "formData", Map.of("amount", "999"), "parentSecret", "未映射私密输入",
                        "childData", bound.inputs().project(Map.of("amount", "12.300")).values())).start().getId();
    }

    private SubprocessDefinitionResolver.Bound resolve(String tenant, SubprocessPolicy policy, FormSchema source) {
        return new TransactionTemplate(transactions).execute(status -> resolver.resolve(tenant, policy, "call", source));
    }
    private DefinitionDraft publish(String key, String name, FormSchema schema) {
        var draft = definitions.create("demo", key, name, graph(), schema);
        return definitions.publish(ADMIN, draft.id(), 0, "子流程固定引用本地验收");
    }
    private FormSchema schema(String key) {
        return new FormSchema(1, List.of(new FormSchema.Field(key, key, FormSchema.FieldType.NUMBER, true, null, null, null, null, null)));
    }
    private Graph graph() {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("review", "人工审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:finance")),
                new Node("end", "结束", NodeType.END, Map.of())), List.of(new Edge("a", "start", "review", ""), new Edge("b", "review", "end", "")));
    }
    private String key() { return "subprocess-" + UUID.randomUUID(); }
    private void failure(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
