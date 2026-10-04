package io.agentflow.servicetask;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionDraftRepository;
import io.agentflow.definition.FlowableDefinitionDeploymentAdapter;
import io.agentflow.form.FormSchema;
import io.agentflow.form.FieldVisibility;
import io.agentflow.notification.InboxMessage;
import io.agentflow.notification.InboxRepository;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.support.MutationRequests.post;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 固定测试定义绕过尚未开放的设计入口，实际提交、等待、HTTP、审批和事务均使用生产链路。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.service-tasks.worker-enabled=false", "agentflow.timers.enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ServiceTaskRuntimeIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired AuthService auth;
    @Autowired DefinitionDraftRepository definitions;
    @Autowired FlowableDefinitionDeploymentAdapter deployments;
    @Autowired ServiceTaskGatewayConfiguration configuration;
    @Autowired ServiceTaskCatalog catalog;
    @Autowired ServiceTaskOperationService service;
    @Autowired ServiceTaskGateway gateway;
    @Autowired ServiceTaskWorker worker;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean JdbcServiceTaskOperationRepository operations;
    @MockitoSpyBean InboxRepository inbox;
    private ServiceTaskTestProvider provider;
    private ServiceTaskGatewayConfiguration.Operation declaration;
    private ServiceTaskContract contract;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_SERVICE_TASK_URL", "jdbc:h2:mem:service-tasks;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        properties.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_SERVICE_TASK_DRIVER", "org.h2.Driver"));
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_SERVICE_TASK_USER", "sa"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_SERVICE_TASK_PASSWORD", ""));
    }

    @BeforeEach
    void installProvider() throws Exception {
        provider = new ServiceTaskTestProvider(json); declaration = provider.declaration("receipt." + UUID.randomUUID());
        configuration.setEnabled(true); configuration.setTenants(Map.of("demo", List.of(declaration))); catalog.install();
        contract = configuration.find("demo", declaration.getKey(), 1).orElseThrow().contract();
    }
    @AfterEach void close() { provider.close(); configuration.setEnabled(false); }

    @Test
    void sequentialNodesMayReuseExecutionButLateFirstReceiptCannotAdvanceSecond() throws Exception {
        String id = submitted("service1", "service2", "review"); var first = operation(id, "service1");
        var claimed = apply(first); advance(first.input().command().id(), Instant.now());
        var second = operation(id, "service2");
        assertThat(second.input().command().id()).isNotEqualTo(first.input().command().id());
        assertThat(second.input().command().binding().executionId()).isEqualTo(first.input().command().binding().executionId());
        assertThat(tasksFor(id)).isEmpty();
        service.finish(claimed, new ServiceTaskGateway.Observed(provider.observations.get(first.input().command().id())), Instant.now());
        assertThat(operation(id, "service2").attempts()).isZero();
        apply(second); advance(second.input().command().id(), Instant.now());
        assertThat(tasksFor(id)).hasSize(1); assertThat(audits(id, "SERVICE_TASK_COMPLETED")).isEqualTo(2);
        assertThat(application(id).path("status").asText()).isEqualTo("IN_APPROVAL");
        var history = mvc.perform(get("/api/v1/applications/" + id + "/audit?action=SERVICE_TASK_COMPLETED").header("Authorization", token("alice")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.read(history, JsonNode.class).path("items").size()).isEqualTo(2);
        approve(id); assertThat(application(id).path("status").asText()).isEqualTo("APPROVED");
        assertThat(provider.effects).hasValue(2);
    }

    @Test
    void workerSendsOutsideTransactionAndSuccessAfterManualApprovalCompletesOriginalRound() throws Exception {
        String id = submitted("review", "service1"); approve(id);
        var operation = operation(id, "service1");
        for (int i = 0; i < 4 && operations.find("demo", operation.input().command().id()).orElseThrow().progress() == JdbcServiceTaskOperationRepository.Progress.PENDING; i++) worker.poll();
        assertThat(application(id).path("status").asText()).isEqualTo("APPROVED");
        assertThat(provider.effects).hasValue(1);
        assertThat(operations.find("demo", operation.input().command().id()).orElseThrow().progress()).isEqualTo(JdbcServiceTaskOperationRepository.Progress.ADVANCED);
        worker.poll(); assertThat(provider.effects).hasValue(1); assertThat(audits(id, "SERVICE_TASK_COMPLETED")).isEqualTo(1);
    }

    @Test
    void originalQueryRecoversEffectAfterResponseLossWithoutCreatingAnotherOperation() throws Exception {
        String id = submitted("service1", "review"); var queued = operation(id, "service1");
        provider.loseNextExecuteResponse.set(true);
        var claimed = service.claim("demo", queued.input().command().id(), Instant.now());
        service.finish(claimed, gateway.execute(claimed.input()), Instant.now());
        var unknown = operation(id, "service1"); assertThat(unknown.status()).isEqualTo(ServiceTaskOperation.Status.UNKNOWN);
        var query = service.claim("demo", queued.input().command().id(), unknown.nextAttemptAt());
        assertThat(query.status()).isEqualTo(ServiceTaskOperation.Status.QUERYING);
        service.finish(query, gateway.query(query.input()), query.updatedAt().plusSeconds(1));
        advance(queued.input().command().id(), query.updatedAt().plusSeconds(2));
        assertThat(provider.effects).hasValue(1); assertThat(provider.calls).extracting(ServiceTaskTestProvider.Call::path).containsExactly("/execute", "/query");
        assertThat(tasksFor(id)).hasSize(1); assertThat(operationCount(id)).isEqualTo(1);
    }

    @Test
    void expiredClaimFencesOldWorkerAndOnlyOriginalQueryCanSupplyResult() throws Exception {
        String id = submitted("service1", "review"); var queued = operation(id, "service1");
        var old = service.claim("demo", queued.input().command().id(), Instant.now()); var outcome = gateway.execute(old.input());
        assertThat(service.claim("demo", old.input().command().id(), old.leaseUntil())).isNull();
        var query = service.claim("demo", old.input().command().id(), old.leaseUntil());
        assertThat(query.status()).isEqualTo(ServiceTaskOperation.Status.QUERYING);
        service.finish(old, outcome, old.leaseUntil().plusSeconds(1));
        assertThat(operation(id, "service1").version()).isEqualTo(query.version());
        service.finish(query, gateway.query(query.input()), query.updatedAt().plusSeconds(1));
        advance(query.input().command().id(), query.updatedAt().plusSeconds(2));
        assertThat(tasksFor(id)).hasSize(1); assertThat(provider.effects).hasValue(1);
    }

    @Test
    void concurrentClaimsHaveOneOwnerAndTenantMismatchDoesNotReadOperation() throws Exception {
        String id = submitted("service1", "review"); var queued = operation(id, "service1"); UUID operationId = queued.input().command().id();
        var pool = Executors.newFixedThreadPool(2); var barrier = new CyclicBarrier(2);
        try {
            var first = pool.submit(() -> { barrier.await(); return service.claim("demo", operationId, Instant.now()); });
            var second = pool.submit(() -> { barrier.await(); return service.claim("demo", operationId, Instant.now()); });
            var claims = java.util.Arrays.asList(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(claims.stream().filter(java.util.Objects::nonNull).count()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
        assertThat(operation(id, "service1").attempts()).isEqualTo(1);
        assertThat(service.claim("foreign", operationId, Instant.now())).isNull();
    }

    @Test
    void pauseHoldsUnsentAndAppliedWorkWithoutRepeatingSideEffect() throws Exception {
        String id = submitted("service1", "review"); var queued = operation(id, "service1"); UUID operationId = queued.input().command().id();
        control(id, "pause"); assertThat(service.claim("demo", operationId, Instant.now())).isNull();
        assertThat(operation(id, "service1").attempts()).isZero(); assertThat(provider.calls).isEmpty();
        control(id, "resume"); apply(queued); control(id, "pause");
        advance(operationId, Instant.now()); assertThat(tasksFor(id)).isEmpty();
        assertThat(operations.find("demo", operationId).orElseThrow().progress()).isEqualTo(JdbcServiceTaskOperationRepository.Progress.PENDING);
        control(id, "resume"); advance(operationId, Instant.now());
        assertThat(tasksFor(id)).hasSize(1); assertThat(provider.effects).hasValue(1);
    }

    @Test
    void stopCancelsUnsentButQueriesAlreadySentEffectsAndNeverResumesOldRound() throws Exception {
        String firstId = submitted("service1", "review"); var first = operation(firstId, "service1");
        control(firstId, "terminate"); advance(first.input().command().id(), Instant.now());
        assertThat(operation(firstId, "service1").status()).isEqualTo(ServiceTaskOperation.Status.CANCELLED);
        assertThat(provider.calls).isEmpty();
        String secondId = submitted("service1", "review"); var second = operation(secondId, "service1");
        var sent = service.claim("demo", second.input().command().id(), Instant.now());
        provider.loseNextExecuteResponse.set(true); service.finish(sent, gateway.execute(sent.input()), Instant.now());
        control(secondId, "terminate"); var unknown = operation(secondId, "service1");
        var query = service.claim("demo", second.input().command().id(), unknown.nextAttemptAt());
        assertThat(query.status()).isEqualTo(ServiceTaskOperation.Status.QUERYING);
        service.finish(query, gateway.query(query.input()), query.updatedAt().plusSeconds(1)); advance(query.input().command().id(), query.updatedAt().plusSeconds(2));
        assertThat(operations.find("demo", query.input().command().id()).orElseThrow().progress()).isEqualTo(JdbcServiceTaskOperationRepository.Progress.STALE);
        assertThat(tasksFor(secondId)).isEmpty(); assertThat(audits(secondId, "SERVICE_TASK_COMPLETED")).isZero(); assertThat(provider.effects).hasValue(1);
    }

    @Test
    void notificationFailureRollsBackEngineAndProgressButKeepsConfirmedRemoteReceipt() throws Exception {
        String id = submitted("service1", "review"); var queued = operation(id, "service1"); apply(queued);
        doAnswer(call -> { Object result = call.callRealMethod(); if (((InboxMessage) call.getArgument(1)).kind() == InboxMessage.Kind.TASK_PENDING) throw new DomainException("DEPENDENCY_UNAVAILABLE", "Synthetic notification failure"); return result; })
                .when(inbox).append(anyString(), any(InboxMessage.class));
        try { assertThatThrownBy(() -> advance(queued.input().command().id(), Instant.now())).isInstanceOf(DomainException.class); }
        finally { doCallRealMethod().when(inbox).append(anyString(), any(InboxMessage.class)); }
        assertThat(tasksFor(id)).isEmpty(); assertThat(audits(id, "SERVICE_TASK_COMPLETED")).isZero();
        assertThat(operation(id, "service1").status()).isEqualTo(ServiceTaskOperation.Status.APPLIED);
        advance(queued.input().command().id(), Instant.now()); assertThat(tasksFor(id)).hasSize(1); assertThat(provider.effects).hasValue(1);
    }

    @Test
    void queueFailureRollsBackFirstSubmissionIncludingNativeInstanceAndRound() throws Exception {
        String id = draft("service1", "review");
        JdbcServiceTaskOperationRepository target = AopTestUtils.getUltimateTargetObject(operations);
        doAnswer(call -> { call.callRealMethod(); throw new DomainException("DEPENDENCY_UNAVAILABLE", "Synthetic queue failure"); }).when(target).create(any());
        try { submit(id, 503); } finally { doCallRealMethod().when(target).create(any()); }
        assertThat(application(id).path("status").asText()).isEqualTo("DRAFT"); assertThat(application(id).path("version").asLong()).isEqualTo(1);
        assertThat(operationCount(id)).isZero();
        assertThat(runtime.createProcessInstanceQuery().variableValueEquals("applicationId", id).count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_submission_round WHERE tenant_id='demo' AND application_id=?", Integer.class, id)).isZero();
        submit(id, 200); assertThat(operationCount(id)).isEqualTo(1);
    }

    @Test
    void serviceResultCannotReplaceRequiredHumanApproval() throws Exception {
        String id = submitted("service1"); var queued = operation(id, "service1"); apply(queued);
        assertThatThrownBy(() -> advance(queued.input().command().id(), Instant.now()))
                .isInstanceOfSatisfying(DomainException.class, value -> assertThat(value.code()).isEqualTo("SERVICE_TASK_REQUIRES_APPROVAL"));
        assertThat(application(id).path("status").asText()).isEqualTo("IN_APPROVAL");
        assertThat(operations.find("demo", queued.input().command().id()).orElseThrow().progress()).isEqualTo(JdbcServiceTaskOperationRepository.Progress.PENDING);
        assertThat(audits(id, "SERVICE_TASK_COMPLETED")).isZero();
    }

    @Test
    void sameVersionCannotChangeTargetAndDisabledOperationIsHeldWithoutNewAttempt() throws Exception {
        String id = submitted("service1", "review"); var queued = operation(id, "service1");
        declaration.setEndpoint(provider.endpoint() + "changed/");
        assertThatThrownBy(catalog::install).isInstanceOfSatisfying(DomainException.class, value -> assertThat(value.code()).isEqualTo("SERVICE_TASK_CONTRACT_REDEFINED"));
        assertThat(service.claim("demo", queued.input().command().id(), Instant.now())).isNull(); assertThat(provider.calls).isEmpty();
        declaration.setEndpoint(provider.endpoint()); declaration.setEnabled(false);
        assertThat(service.claim("demo", queued.input().command().id(), Instant.now())).isNull(); assertThat(operation(id, "service1").attempts()).isZero();
        declaration.setEnabled(true); assertThat(service.claim("demo", queued.input().command().id(), Instant.now())).isNotNull();
    }

    @Test
    void equivalentStoredJsonOrderingDoesNotPreventClaimAfterRestart() throws Exception {
        String id = submitted("service1", "review"); var queued = operation(id, "service1"); UUID operationId = queued.input().command().id();
        String previous = jdbc.queryForObject("SELECT input_json FROM service_task_operation WHERE tenant_id='demo' AND id=?", String.class, operationId.toString());
        var tree = json.read(previous, JsonNode.class); var reordered = new LinkedHashMap<String, Object>();
        reordered.put("targetDigest", tree.get("targetDigest")); reordered.put("command", tree.get("command")); String changed = json.write(reordered);
        assertThat(changed).isNotEqualTo(previous);
        jdbc.update("UPDATE service_task_operation SET input_json=? WHERE tenant_id='demo' AND id=?", changed, operationId.toString());
        assertThat(service.claim("demo", operationId, Instant.now())).isNotNull();
        assertThat(jdbc.queryForObject("SELECT input_json FROM service_task_operation WHERE tenant_id='demo' AND id=?", String.class, operationId.toString())).isEqualTo(changed);
    }

    @Test
    void definitionAvailabilityResaveDoesNotInvalidateOriginalWaitWhenJsonOrderChanges() throws Exception {
        String id = draft("service1", "review");
        var definition = definitions.findPublished("demo", application(id).path("processKey").asText(), 1).orElseThrow();
        String original = jdbc.queryForObject("SELECT graph_json FROM approval_definition WHERE id=?", String.class, definition.id().toString());
        var tree = json.read(original, JsonNode.class); var reordered = new LinkedHashMap<String, Object>();
        var keys = new ArrayList<String>(); tree.fieldNames().forEachRemaining(keys::add);
        java.util.Collections.reverse(keys); keys.forEach(key -> reordered.put(key, tree.get(key)));
        String previousJvm = json.write(reordered);
        assertThat(previousJvm).isNotEqualTo(original);
        assertThat(json.read(previousJvm, JsonNode.class)).isEqualTo(tree);
        jdbc.update("UPDATE approval_definition SET graph_json=? WHERE id=?", previousJvm, definition.id().toString());
        submit(id, 200); var queued = operation(id, "service1");
        mvc.perform(post("/api/v1/process-definitions/" + definition.id() + "/availability")
                .header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("startEnabled", false, "expectedRevision", definition.revision(), "reason", "仅停止新的发起"))))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT graph_json FROM approval_definition WHERE id=?", String.class, definition.id().toString())).isNotEqualTo(previousJvm);
        apply(queued); advance(queued.input().command().id(), Instant.now());
        assertThat(tasksFor(id)).hasSize(1); assertThat(provider.effects).hasValue(1);
    }

    @Test
    void withdrawnRoundResultCannotAdvanceResubmittedRound() throws Exception {
        String id = submitted("service1", "review"); var first = operation(id, "service1");
        var sent = service.claim("demo", first.input().command().id(), Instant.now());
        provider.loseNextExecuteResponse.set(true); service.finish(sent, gateway.execute(sent.input()), Instant.now());
        mvc.perform(post("/api/v1/applications/" + id + "/withdraw").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedVersion", 2, "comment", "撤回原等待"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedVersion", 3))))
                .andExpect(status().isOk());
        String secondId = jdbc.queryForObject("SELECT id FROM service_task_operation WHERE tenant_id='demo' AND application_id=? AND round_no=2", String.class, id);
        var second = operations.find("demo", UUID.fromString(secondId)).orElseThrow().operation();
        assertThat(second.input().command().binding().processInstanceId()).isNotEqualTo(first.input().command().binding().processInstanceId());
        var unknown = operations.find("demo", first.input().command().id()).orElseThrow().operation();
        var query = service.claim("demo", first.input().command().id(), unknown.nextAttemptAt());
        service.finish(query, gateway.query(query.input()), query.updatedAt().plusSeconds(1));
        advance(query.input().command().id(), query.updatedAt().plusSeconds(2));
        assertThat(operations.find("demo", first.input().command().id()).orElseThrow().progress()).isEqualTo(JdbcServiceTaskOperationRepository.Progress.STALE);
        assertThat(tasksFor(id)).isEmpty(); assertThat(audits(id, "SERVICE_TASK_COMPLETED")).isZero();
        assertThat(operations.find("demo", second.input().command().id()).orElseThrow().operation().attempts()).isZero();
        apply(second); advance(second.input().command().id(), Instant.now());
        assertThat(tasksFor(id)).hasSize(1); assertThat(provider.effects).hasValue(2);
    }

    @Test
    void rejectedServiceRemainsBlockedAndDoesNotPretendToBeApproved() throws Exception {
        String id = submitted("service1", "review"); var queued = operation(id, "service1");
        provider.nextStatus = ServiceTaskObservation.Status.REJECTED;
        var claimed = service.claim("demo", queued.input().command().id(), Instant.now());
        service.finish(claimed, gateway.execute(claimed.input()), Instant.now());
        assertThat(operation(id, "service1").status()).isEqualTo(ServiceTaskOperation.Status.REJECTED);
        advance(queued.input().command().id(), Instant.now());
        assertThat(tasksFor(id)).isEmpty(); assertThat(audits(id, "SERVICE_TASK_COMPLETED")).isZero();
        assertThat(application(id).path("status").asText()).isEqualTo("IN_APPROVAL");
        assertThat(provider.calls).hasSize(1); assertThat(provider.effects).hasValue(0);
    }

    @Test
    void persistedInputCannotChangeBehindOriginalCommandDigest() throws Exception {
        String id = submitted("service1", "review"); var queued = operation(id, "service1");
        String original = jdbc.queryForObject("SELECT input_json FROM service_task_operation WHERE tenant_id='demo' AND id=?", String.class, queued.input().command().id().toString());
        String tampered = original.replace("private-original", "modified-input"); assertThat(tampered).isNotEqualTo(original);
        jdbc.update("UPDATE service_task_operation SET input_json=? WHERE tenant_id='demo' AND id=?", tampered, queued.input().command().id().toString());
        try {
            assertThatThrownBy(() -> service.claim("demo", queued.input().command().id(), Instant.now())).isInstanceOf(IllegalStateException.class);
            assertThat(provider.calls).isEmpty();
        } finally {
            jdbc.update("UPDATE service_task_operation SET input_json=? WHERE tenant_id='demo' AND id=?", original, queued.input().command().id().toString());
        }
    }

    @Test
    void changedPublishedInputPermissionsCannotAuthorizeOriginalQueuedCommand() throws Exception {
        String id = submitted("service1", "review"); var queued = operation(id, "service1");
        var definition = definitions.findPublished("demo", application(id).path("processKey").asText(), 1).orElseThrow();
        var hidden = new FormSchema(2, List.of(new FormSchema.Field("reason", "说明", FormSchema.FieldType.TEXT, true,
                null, null, null, null, null, null, null, false, Map.of("service1", FieldVisibility.HIDDEN))));
        jdbc.update("UPDATE approval_definition SET form_schema_json=? WHERE id=?", json.write(hidden), definition.id().toString());
        assertThat(service.claim("demo", queued.input().command().id(), Instant.now())).isNull();
        assertThat(operation(id, "service1").status()).isEqualTo(ServiceTaskOperation.Status.CANCELLED);
        assertThat(provider.calls).isEmpty(); assertThat(tasksFor(id)).isEmpty();
    }

    private String submitted(String... steps) throws Exception { String id = draft(steps); submit(id, 200); return id; }
    private String draft(String... steps) throws Exception {
        String key = "service-" + UUID.randomUUID(); var nodes = new ArrayList<Node>(); var edges = new ArrayList<Edge>();
        nodes.add(new Node("start", "开始", NodeType.START, Map.of())); String previous = "start";
        for (String step : steps) {
            nodes.add(new Node(step, step, step.equals("review") ? NodeType.USER_TASK : NodeType.SERVICE_TASK, step.equals("review") ? Map.of("assigneeRule", "user:finance")
                    : Map.of("serviceOperationKey", contract.key(), "serviceOperationVersion", "1", "serviceContractDigest", contract.digest(), "serviceInput.memo", "reason")));
            edges.add(new Edge("edge" + edges.size(), previous, step, "")); previous = step;
        }
        nodes.add(new Node("end", "结束", NodeType.END, Map.of())); edges.add(new Edge("edge" + edges.size(), previous, "end", ""));
        var schema = new FormSchema(2, List.of(new FormSchema.Field("reason", "说明", FormSchema.FieldType.TEXT, true, null, null, null, null, null)));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var draft = DefinitionDraft.create(UUID.randomUUID(), "demo", key, "服务任务运行夹具", new Graph(nodes, edges), schema);
            definitions.save(draft); draft.publish(0, 1); definitions.save(draft); deployments.deploy(draft);
        });
        var response = mvc.perform(post("/api/v1/applications").header("Authorization", token("alice")).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("businessNo", key, "processKey", key, "definitionVersion", 1, "title", "服务任务验收", "payload", Map.of("reason", "private-original")))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.read(response, JsonNode.class).path("id").asText();
    }
    private void submit(String id, int expectedStatus) throws Exception {
        mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice")).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("expectedVersion", 1)))).andExpect(status().is(expectedStatus));
    }
    private ServiceTaskOperation operation(String id, String node) {
        String operationId = jdbc.queryForObject("SELECT id FROM service_task_operation WHERE tenant_id='demo' AND application_id=? AND node_id=?", String.class, id, node);
        return operations.find("demo", UUID.fromString(operationId)).orElseThrow().operation();
    }
    private ServiceTaskOperation apply(ServiceTaskOperation operation) {
        var claimed = service.claim("demo", operation.input().command().id(), Instant.now()); assertThat(claimed).isNotNull();
        service.finish(claimed, gateway.execute(claimed.input()), Instant.now());
        assertThat(operations.find("demo", claimed.input().command().id()).orElseThrow().operation().status()).isEqualTo(ServiceTaskOperation.Status.APPLIED); return claimed;
    }
    private void advance(UUID id, Instant now) { assertThat(service.claim("demo", id, now)).isNull(); }
    private List<org.flowable.task.api.Task> tasksFor(String id) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", id).list(); }
    private void approve(String id) throws Exception {
        mvc.perform(post("/api/v1/tasks/" + tasksFor(id).get(0).getId() + "/actions").header("Authorization", token("finance")).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("action", "APPROVE", "expectedVersion", application(id).path("version").asLong(), "comment", "保留人工意见")))).andExpect(status().isOk());
    }
    private void control(String id, String action) throws Exception {
        mvc.perform(post("/api/v1/applications/" + id + "/rounds/1/runtime/" + action).header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("expectedVersion", application(id).path("version").asLong(), "reason", "合成运行控制验收")))).andExpect(status().isOk());
    }
    private JsonNode application(String id) throws Exception { return json.read(mvc.perform(get("/api/v1/applications/" + id).header("Authorization", token("alice"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), JsonNode.class); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private int operationCount(String id) { return jdbc.queryForObject("SELECT count(*) FROM service_task_operation WHERE tenant_id='demo' AND application_id=?", Integer.class, id); }
    private int audits(String id, String action) { return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE application_id=? AND action=?", Integer.class, id, action); }
}
