package io.agentflow.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.model.Application;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.support.MutationRequests.post;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真实请求、授权、引擎和审计共同验证业务关联，拒绝使用请求头和重提后的新轮次补造旧事实。 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:approval-business-trace;DB_CLOSE_DELAY=-1",
        "agentflow.auth.demo-enabled=true", "agentflow.webhooks.worker-enabled=false",
        "agentflow.notifications.worker-enabled=false", "agentflow.sla.reminders-enabled=false"})
@AutoConfigureMockMvc
class ApprovalBusinessTraceIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired CurrentActor actors;
    @Autowired ApprovalApplicationFacade applications;
    @Autowired DefinitionApplicationService definitions;
    @Autowired TaskService tasks;
    @Autowired JdbcTemplate jdbc;
    private final Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>() {
        @Override protected void append(ILoggingEvent event) { event.prepareForDeferredProcessing(); super.append(event); }
    };

    @BeforeEach void capture() { events.start(); logger.addAppender(events); }
    @AfterEach void cleanup() { logger.detachAppender(events); events.stop(); actors.clear(); MDC.clear(); }

    @Test void authorizedApplicationReadAssociatesOnlyPersistedBusinessIdentity() throws Exception {
        var app = draft();
        var response = mvc.perform(get("/api/v1/applications/" + app.id()).header("Authorization", token("alice"))
                        .header("businessNo", "forged-business").header("processInstanceId", "forged-instance")
                        .header("taskId", "forged-task")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(contexts(response)).anySatisfy(context -> assertThat(context).containsEntry("businessNo", app.businessNo())
                .containsEntry("tenantId", "demo").doesNotContainKeys("processInstanceId", "taskId"));
        assertSafeAndClean(response);
    }

    @Test void deniedResourceReadCannotAssociateThePrivateBusinessNumber() throws Exception {
        var app = draft();
        var response = mvc.perform(get("/api/v1/applications/" + app.id()).header("Authorization", token("bob"))
                        .header("businessNo", app.businessNo()).header("processInstanceId", "forged-instance")
                        .header("taskId", "forged-task")).andExpect(status().isNotFound()).andReturn().getResponse();
        assertThat(contexts(response)).allSatisfy(context -> assertThat(context).doesNotContainKeys("businessNo", "processInstanceId", "taskId"));
        assertSafeAndClean(response);
    }

    @Test void engineCreationAndTaskDecisionKeepTheirOwnNativeIdentifiers() throws Exception {
        var app = draft();
        var submitted = submit(app.id(), 1);
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", app.id().toString()).singleResult();
        assertThat(contexts(submitted)).anySatisfy(context -> assertThat(context).containsEntry("businessNo", app.businessNo())
                .containsEntry("processInstanceId", task.getProcessInstanceId()).containsEntry("taskId", task.getId()));
        var decision = action(task.getId(), "APPROVE", 2);
        assertThat(contexts(decision)).anySatisfy(context -> assertThat(context).containsEntry("businessNo", app.businessNo())
                .containsEntry("processInstanceId", task.getProcessInstanceId()).containsEntry("taskId", task.getId()));
        var audit = taskAudit(task.getId());
        assertThat(audit.path("traceId").asText()).isEqualTo(decision.getHeader(DiagnosticContext.HEADER));
        assertThat(audit.path("businessNo").asText()).isEqualTo(app.businessNo());
        assertThat(audit.path("taskId").asText()).isEqualTo(task.getId());
        assertThat(audit.path("processInstanceId").asText()).isEqualTo(task.getProcessInstanceId());
        assertSafeAndClean(submitted); assertSafeAndClean(decision);
    }

    @Test void resubmissionDoesNotRelabelTheOriginalTaskAuditOrBorrowItsTask() throws Exception {
        var app = draft(); submit(app.id(), 1);
        var original = tasks.createTaskQuery().processVariableValueEquals("applicationId", app.id().toString()).singleResult();
        action(original.getId(), "RETURN", 2);
        var before = taskAudit(original.getId());
        var resubmitted = submit(app.id(), 3);
        var current = tasks.createTaskQuery().processVariableValueEquals("applicationId", app.id().toString()).singleResult();
        assertThat(current.getProcessInstanceId()).isNotEqualTo(original.getProcessInstanceId());
        assertThat(taskAudit(original.getId())).isEqualTo(before);
        assertThat(before.path("businessNo").asText()).isEqualTo(app.businessNo());
        assertThat(before.path("taskId").asText()).isEqualTo(original.getId());
        assertThat(contexts(resubmitted)).filteredOn(context -> context.containsKey("processInstanceId"))
                .isNotEmpty().allSatisfy(context -> assertThat(context.get("processInstanceId")).isEqualTo(current.getProcessInstanceId()));
        assertThat(contexts(resubmitted)).allSatisfy(context -> assertThat(context.get("taskId")).isNotEqualTo(original.getId()));
        assertSafeAndClean(resubmitted);
    }

    private List<Map<String, String>> contexts(MockHttpServletResponse response) {
        return events.list.stream().filter(event -> response.getHeader(DiagnosticContext.HEADER).equals(event.getMDCPropertyMap().get("traceId")))
                .map(ILoggingEvent::getMDCPropertyMap).toList();
    }
    private void assertSafeAndClean(MockHttpServletResponse response) {
        assertThat(events.list).filteredOn(event -> response.getHeader(DiagnosticContext.HEADER).equals(event.getMDCPropertyMap().get("traceId")))
                .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("private-body-sentinel", "private-comment-sentinel", "forged-"));
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
    private JsonNode taskAudit(String taskId) {
        return json.read(jdbc.queryForObject("SELECT payload_json FROM audit_event WHERE tenant_id='demo' AND aggregate_type='Task' AND aggregate_id=?", String.class, taskId), JsonNode.class);
    }
    private MockHttpServletResponse submit(UUID id, long version) throws Exception {
        return mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedVersion", version))))
                .andExpect(status().isOk()).andReturn().getResponse();
    }
    private MockHttpServletResponse action(String id, String action, long version) throws Exception {
        return mvc.perform(post("/api/v1/tasks/" + id + "/actions").header("Authorization", token("finance"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("action", action, "expectedVersion", version,
                                "comment", "private-comment-sentinel"))))
                .andExpect(status().isOk()).andReturn().getResponse();
    }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private Application draft() {
        String key = "business-trace-" + UUID.randomUUID();
        var definition = definitions.create("demo", key, "业务关联验证", new Graph(List.of(
                new Node("start", "开始", NodeType.START, Map.of()),
                new Node("finance", "财务", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")),
                new Node("end", "结束", NodeType.END, Map.of())), List.of(
                new Edge("a", "start", "finance", ""), new Edge("b", "finance", "end", ""))));
        definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), definition.id(), 0, "测试发布");
        actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE", "APPROVER")));
        try { return applications.create("BUSINESS-" + UUID.randomUUID(), key, 1, "private-body-sentinel", Map.of()); }
        finally { actors.clear(); }
    }
}
