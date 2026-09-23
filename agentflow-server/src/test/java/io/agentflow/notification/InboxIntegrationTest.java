package io.agentflow.notification;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.support.MutationRequests.post;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 在真实业务事务、引擎和 HTTP 上验证通知事实、接收范围及个人阅读状态。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:inbox;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class InboxIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired DefinitionApplicationService definitions;
    @Autowired TaskService tasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired InboxApplicationService service;
    @MockitoSpyBean InboxRepository inbox;

    @Test
    void submittingNotifiesOnlyApplicantAndActualCandidateMembersAndReplayDoesNotDuplicate() throws Exception {
        String id = draft("role:FINANCE", false);
        String key = UUID.randomUUID().toString();
        String first = submit(id, key, 200);
        assertThat(submit(id, key, 200)).isEqualTo(first);
        assertThat(recipients(id, "APPLICATION_SUBMITTED")).containsExactly("alice");
        assertThat(recipients(id, "TASK_PENDING")).containsExactly("admin", "finance");
        assertThat(inboxFor("employee").path("items").findValuesAsText("applicationId")).doesNotContain(id);
        assertThat(jdbc.queryForObject("SELECT title FROM notification_inbox WHERE application_id=? AND recipient_id='alice'", String.class, id))
                .hasSize(256);
        String task = task(id);
        act(id, task, "finance", "CLAIM", null);
        assertThat(recipients(id, "TASK_PENDING")).hasSize(2);
        act(id, task, "finance", "RELEASE", null);
        assertThat(recipients(id, "TASK_PENDING")).containsExactly("admin", "admin", "finance", "finance");
    }

    @Test
    void sequentialApprovalNotifiesNextNodeAndOnlyFinalDecisionNotifiesApplicant() throws Exception {
        String id = draft("user:manager", true);
        submit(id, UUID.randomUUID().toString(), 200);
        assertThat(recipients(id, "TASK_PENDING")).containsExactly("manager");
        act(id, task(id), "manager", "APPROVE", null);
        assertThat(recipients(id, "TASK_PENDING")).containsExactly("admin", "finance", "manager");
        assertThat(recipients(id, "APPLICATION_APPROVED")).isEmpty();
        act(id, task(id), "finance", "APPROVE", null);
        assertThat(recipients(id, "APPLICATION_APPROVED")).containsExactly("alice");
    }

    @Test
    void delegationResolutionAndTransferNotifyOnlyTheNewAssignee() throws Exception {
        String id = draft("user:finance", false);
        submit(id, UUID.randomUUID().toString(), 200);
        String task = task(id);
        act(id, task, "finance", "DELEGATE", "bob");
        assertThat(recipients(id, "TASK_DELEGATED")).containsExactly("bob");
        act(id, task, "bob", "RESOLVE", null);
        assertThat(recipients(id, "TASK_RESOLVED")).containsExactly("finance");
        act(id, task, "finance", "TRANSFER", "manager");
        assertThat(recipients(id, "TASK_TRANSFERRED")).containsExactly("manager");
        assertThat(recipients(id, "APPLICATION_APPROVED")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RETURN", "REJECT"})
    void negativeDecisionsNotifyApplicantWithoutPublishingPrivateFormOrComment(String action) throws Exception {
        String id = draft("user:finance", false);
        submit(id, UUID.randomUUID().toString(), 200);
        act(id, task(id), "finance", action, null);
        String kind = action.equals("RETURN") ? "APPLICATION_RETURNED" : "APPLICATION_REJECTED";
        assertThat(recipients(id, kind)).containsExactly("alice");
        assertThat(inboxFor("alice").toString()).doesNotContain("private-form-value", "private-decision-comment", "payload", "comment");
    }

    @Test
    void withdrawalNotifiesPreviousCandidatesButMessageDoesNotGrantApplicationAccess() throws Exception {
        String id = draft("role:FINANCE", false);
        submit(id, UUID.randomUUID().toString(), 200);
        mvc.perform(post("/api/v1/applications/" + id + "/withdraw").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedVersion", version(id), "comment", "撤回测试"))))
                .andExpect(status().isOk());
        assertThat(recipients(id, "APPLICATION_WITHDRAWN")).containsExactly("admin", "alice", "finance");
        assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", id).count()).isZero();
        mvc.perform(get("/api/v1/applications/" + id).header("Authorization", token("finance"))).andExpect(status().isNotFound());
        assertThat(inboxFor("finance").path("items").findValuesAsText("applicationId")).contains(id);
    }

    @Test
    void notificationFailureRollsBackSubmissionAndTaskDecisionIncludingAuditAndEngine() throws Exception {
        String id = draft("user:finance", false);
        failAfterNotificationInsert();
        submit(id, UUID.randomUUID().toString(), 503);
        assertThat(version(id)).isEqualTo(1);
        assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", id).count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='SUBMIT'", Integer.class, id)).isZero();
        doAnswer(call -> call.callRealMethod()).when(inbox).append(anyString(), any());
        submit(id, UUID.randomUUID().toString(), 200);
        String task = task(id);
        failAfterNotificationInsert();
        mvc.perform(post("/api/v1/tasks/" + task + "/actions").header("Authorization", token("finance"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"APPROVE\",\"expectedVersion\":2}"))
                .andExpect(status().isServiceUnavailable());
        assertThat(version(id)).isEqualTo(2);
        assertThat(task(id)).isEqualTo(task);
        assertThat(recipients(id, "APPLICATION_APPROVED")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='APPROVE'", Integer.class, id)).isZero();
    }

    @Test
    void readIsOwnerOnlyIdempotentAndDoesNotChangeApplicationOrAudit() throws Exception {
        String id = draft("user:finance", false);
        submit(id, UUID.randomUUID().toString(), 200);
        String messageId = jdbc.queryForObject("SELECT id FROM notification_inbox WHERE application_id=? AND recipient_id='finance'", String.class, id);
        mvc.perform(post("/api/v1/notifications/" + messageId + "/read").header("Authorization", token("admin"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/notifications/" + messageId + "/read").header("Authorization", token("alice"))).andExpect(status().isNotFound());
        var outsider = new Actor("other-tenant", "finance", Set.of("ADMIN"));
        assertThatThrownBy(() -> service.read(outsider, UUID.fromString(messageId))).isInstanceOf(DomainException.class);
        var before = jdbc.queryForList("SELECT * FROM audit_event WHERE application_id=?", id);
        String key = UUID.randomUUID().toString(), bearer = token("finance");
        var executor = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (int index = 0; index < 4; index++) futures.add(executor.submit(() -> markRead(messageId, bearer, key)));
            String original = futures.get(0).get(15, TimeUnit.SECONDS);
            for (var result : futures) assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo(original);
            assertThat(markRead(messageId, bearer, UUID.randomUUID().toString())).isEqualTo(original);
        } finally { executor.shutdownNow(); }
        assertThat(jdbc.queryForList("SELECT * FROM audit_event WHERE application_id=?", id)).isEqualTo(before);
        assertThat(version(id)).isEqualTo(2);
    }

    @Test
    void paginationUnreadCountsAndCursorContextRemainIsolated() throws Exception {
        String user = "isolated-inbox-reader";
        var actor = new Actor("notification-tenant", user, Set.of());
        Instant time = Instant.parse("2026-01-01T00:00:00Z");
        for (int index = 0; index < 35; index++) {
            var message = new InboxMessage(UUID.randomUUID(), actor.tenantId(), user, UUID.randomUUID(), "同时间消息", "INBOX-" + index,
                    InboxMessage.Kind.TASK_PENDING, "finance", "task", "节点", 1, time, null);
            inbox.append("event-" + index, message);
            inbox.append("event-" + index, message);
        }
        var parameters = InboxQueryParameters.parse(actor, Map.of());
        var first = service.list(actor, parameters);
        assertThat(first.items()).hasSize(30); assertThat(first.unreadCount()).isEqualTo(35);
        var second = service.list(actor, InboxQueryParameters.parse(actor, Map.of("cursor", first.nextCursor())));
        assertThat(second.items()).hasSize(5); assertThat(second.nextCursor()).isNull();
        assertThat(second.items()).noneMatch(first.items()::contains);
        service.read(actor, first.items().get(0).id());
        var unread = service.list(actor, InboxQueryParameters.parse(actor, Map.of("read", "unread", "limit", "100")));
        assertThat(unread.items()).hasSize(34); assertThat(unread.unreadCount()).isEqualTo(34);
        assertThat(unread.items()).allMatch(message -> message.readAt() == null);
        assertThatThrownBy(() -> InboxQueryParameters.parse(actor, Map.of("read", "unread", "cursor", first.nextCursor()))).isInstanceOf(DomainException.class);
        var other = new Actor("other-tenant", user, Set.of("ADMIN"));
        assertThat(service.list(other, InboxQueryParameters.parse(other, Map.of())).items()).isEmpty();
        assertThatThrownBy(() -> InboxQueryParameters.parse(other, Map.of("cursor", first.nextCursor()))).isInstanceOf(DomainException.class);
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        for (String query : List.of("recipient=finance", "tenantId=demo", "limit=101", "read=invalid", "cursor=bad")) {
            mvc.perform(get("/api/v1/notifications?" + query).header("Authorization", token("alice"))).andExpect(status().isBadRequest());
        }
    }

    private void failAfterNotificationInsert() {
        doAnswer(call -> { call.callRealMethod(); throw new DomainException("DEPENDENCY_UNAVAILABLE", "Notification write failed"); })
                .when(inbox).append(anyString(), any());
    }

    private String markRead(String id, String bearer, String key) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/notifications/" + id + "/read")
                .header("Authorization", bearer).header("Idempotency-Key", key)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode inboxFor(String user) throws Exception {
        return json.read(mvc.perform(get("/api/v1/notifications?limit=100").header("Authorization", token(user))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
    }

    private List<String> recipients(String id, String kind) {
        return jdbc.queryForList("SELECT recipient_id FROM notification_inbox WHERE application_id=? AND kind=? ORDER BY recipient_id", String.class, id, kind);
    }

    private long version(String id) { return jdbc.queryForObject("SELECT version FROM approval_application WHERE id=?", Long.class, id); }
    private String task(String id) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult().getId(); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }

    private void act(String applicationId, String taskId, String user, String action, String target) throws Exception {
        var body = new java.util.HashMap<String, Object>(Map.of("action", action, "expectedVersion", version(applicationId), "comment", "private-decision-comment"));
        if (target != null) body.put("targetUser", target);
        mvc.perform(post("/api/v1/tasks/" + taskId + "/actions").header("Authorization", token(user))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(body))).andExpect(status().isOk());
    }

    private String submit(String id, String key, int expected) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/applications/" + id + "/submit")
                .header("Authorization", token("alice")).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1}")).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    private String draft(String assigneeRule, boolean twoNodes) throws Exception {
        String key = "inbox-" + UUID.randomUUID();
        var nodes = new ArrayList<>(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "第一审批", NodeType.USER_TASK, Map.of("assigneeRule", assigneeRule)), new Node("end", "结束", NodeType.END, Map.of())));
        var edges = new ArrayList<>(List.of(new Edge("begin", "start", "review", "")));
        if (twoNodes) {
            nodes.add(new Node("finance", "财务审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")));
            edges.add(new Edge("next", "review", "finance", "")); edges.add(new Edge("finish", "finance", "end", ""));
        } else edges.add(new Edge("finish", "review", "end", ""));
        var definition = definitions.create("demo", key, "站内消息验收", new Graph(nodes, edges));
        definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), definition.id(), 0, "通知验收");
        return json.read(mvc.perform(post("/api/v1/applications").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("businessNo", key, "title", "消".repeat(256),
                        "processKey", key, "definitionVersion", 1, "payload", Map.of("reason", "private-form-value")))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), JsonNode.class).path("id").asText();
    }
}
