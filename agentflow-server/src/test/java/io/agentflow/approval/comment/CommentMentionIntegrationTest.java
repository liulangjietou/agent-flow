package io.agentflow.approval.comment;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.notification.InboxMessage;
import io.agentflow.notification.JdbcInboxRepository;
import io.agentflow.organization.OrganizationRepository;
import io.agentflow.organization.OrganizationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 评论提醒通过实际组织、Flowable、幂等事务和消息表验证，不以保存名单代替实际通知。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.sla.reminders-enabled=false", "agentflow.timers.enabled=false"})
@AutoConfigureMockMvc
class CommentMentionIntegrationTest {
    private static final Actor ADMIN = new Actor("demo", "admin", Set.of("ADMIN", "APPROVER", "PROCESS_ADMIN", "EMPLOYEE"));
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationService organization;
    @Autowired OrganizationRepository people;
    @Autowired ApplicationRepository applications;
    @SpyBean JdbcInboxRepository inbox;

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_COMMENT_MENTION_URL", "jdbc:h2:mem:comment-mentions;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_COMMENT_MENTION_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_COMMENT_MENTION_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_COMMENT_MENTION_PASSWORD", ""));
    }

    @BeforeEach void identities() {
        if (!people.initialized("demo")) organization.initialize(ADMIN);
        for (String subject : List.of("alice", "finance", "manager", "bob")) {
            var person = people.personBySubject("demo", subject);
            if (person.isEmpty()) organization.createPerson(ADMIN, subject, subject, true, !subject.equals("alice"));
            else if (!person.get().active()) organization.updatePerson(ADMIN, person.get().id(), subject, true, !subject.equals("alice"), person.get().revision());
        }
    }

    @Test void directoryRequiresCurrentVisibilityAndOnlyListsExistingReadersWithBoundedPages() throws Exception {
        String id = application(false);
        assertThat(options(id, "alice", "").path("items").toString()).isEqualTo("[\"finance\"]");
        assertThat(options(id, "finance", "").path("items").toString()).isEqualTo("[\"alice\"]");
        var first = options(id, "admin", "?limit=1");
        assertThat(first.path("items").toString()).isEqualTo("[\"alice\"]");
        assertThat(first.path("nextAfter").asText()).isEqualTo("alice");
        assertThat(options(id, "admin", "?limit=1&afterUser=alice").path("items").toString()).isEqualTo("[\"finance\"]");
        assertThat(options(id, "admin", "?q=FIN").path("items").toString()).isEqualTo("[\"finance\"]");
        assertThat(first.path("applicationId").asText()).isEqualTo(id);
        assertThat(first.path("applicationVersion").asInt()).isEqualTo(2);
        assertThat(first.path("roundNo").asInt()).isEqualTo(1);
        mvc.perform(get(path(id) + "/mention-options").header("Authorization", token("bob"))).andExpect(status().isNotFound());
        mvc.perform(get(path(id) + "/mention-options")).andExpect(status().isUnauthorized());
        mvc.perform(get(path(id) + "/mention-options?roundNo=1").header("Authorization", token("alice"))).andExpect(status().isBadRequest());
        mvc.perform(get(path(id) + "/mention-options?limit=51").header("Authorization", token("alice"))).andExpect(status().isBadRequest());
        assertThat(count("application_comment", id)).isZero();
    }

    @Test void mentionPersistsMinimalNotificationAndReplaysWithoutNewAuditOrVersion() throws Exception {
        String id = application(false), key = UUID.randomUUID().toString();
        var before = jdbc.queryForMap("SELECT * FROM approval_application WHERE id=?", id);
        var audit = jdbc.queryForList("SELECT * FROM audit_event WHERE application_id=? ORDER BY id", id);
        var body = Map.of("content", "仅评论可见的说明 @bob", "expectedVersion", 2, "mentions", List.of("finance"));
        var first = send(path(id), "alice", body, key, 201);
        assertThat(first.path("mentions").toString()).isEqualTo("[\"finance\"]");
        assertThat(send(path(id), "alice", body, key, 201)).isEqualTo(first);
        assertThat(count("application_comment", id)).isEqualTo(1);
        var messages = mentions(id);
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("RECIPIENT_ID").toString()).isEqualTo("finance");
        assertThat(messages.toString()).doesNotContain("仅评论可见的说明", "@bob");
        assertThat(jdbc.queryForMap("SELECT * FROM approval_application WHERE id=?", id)).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM audit_event WHERE application_id=? ORDER BY id", id)).isEqualTo(audit);
        assertThat(send(path(id), "alice", Map.of("content", "仅评论可见的说明 @bob", "expectedVersion", 2, "mentions", List.of()), key, 409).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test void copiedSnapshotDoesNotGrantCommentAccessAndAnotherTenantCannotEnumerateRecipients() throws Exception {
        String id = application(false, true);
        mvc.perform(get("/api/v1/copies/" + id + "/rounds/1").header("Authorization", token("bob"))).andExpect(status().isOk());
        mvc.perform(get(path(id) + "/mention-options").header("Authorization", token("bob"))).andExpect(status().isNotFound());
        mvc.perform(get(path(id)).header("Authorization", token("bob"))).andExpect(status().isNotFound());
        assertThat(options(id, "alice", "").path("items").toString()).isEqualTo("[\"finance\"]");
        add(id, List.of("bob"), 2, 409);
        Application foreign = Application.draft(UUID.randomUUID(), "other", "MENTION-OTHER-" + UUID.randomUUID(),
                "expense-reimbursement", 1, "alice", "其他租户", Map.of());
        applications.save(foreign);
        mvc.perform(get(path(foreign.id().toString()) + "/mention-options").header("Authorization", token("admin"))).andExpect(status().isNotFound());
        assertThat(count("application_comment", id)).isZero(); assertThat(mentions(id)).isEmpty();
    }

    @Test void forgedSelfDuplicateAndMalformedRecipientsDoNotSaveAnyPart() throws Exception {
        String id = application(false);
        for (var users : List.of(List.of("bob"), List.of("alice"), List.of("not-a-person"))) {
            assertThat(add(id, users, 2, 409).path("code").asText()).isEqualTo("COMMENT_MENTION_UNAVAILABLE");
        }
        add(id, List.of("finance", "finance"), 2, 400);
        add(id, List.of(" "), 2, 400);
        add(id, java.util.Collections.nCopies(21, "finance"), 2, 400);
        add(id, List.of("finance"), 1, 409);
        assertThat(count("application_comment", id)).isZero(); assertThat(mentions(id)).isEmpty();
    }

    @Test void disablingAfterSelectionRejectsNewDeliveryButKeepsOriginalReminderHistory() throws Exception {
        String id = application(false), key = UUID.randomUUID().toString();
        var body = Map.of("content", "原提醒", "expectedVersion", 2, "mentions", List.of("finance"));
        var original = send(path(id), "alice", body, key, 201);
        var person = people.personBySubject("demo", "finance").orElseThrow();
        organization.updatePerson(ADMIN, person.id(), person.displayName(), false, true, person.revision());
        assertThat(options(id, "alice", "").path("items")).isEmpty();
        add(id, List.of("finance"), 2, 409);
        assertThat(send(path(id), "alice", body, key, 201)).isEqualTo(original);
        assertThat(mentions(id)).hasSize(1); assertThat(count("application_comment", id)).isEqualTo(1);
    }

    @Test void notificationFailureRollsBackCommentAndOriginalKeyCanBeRetried() throws Exception {
        String id = application(false), key = UUID.randomUUID().toString();
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (((InboxMessage) call.getArgument(1)).kind() == InboxMessage.Kind.COMMENT_MENTIONED) throw new DomainException("DEPENDENCY_UNAVAILABLE", "Synthetic mention write failure");
            return result;
        }).when(inbox).append(anyString(), any(InboxMessage.class));
        var body = Map.of("content", "等待提醒事务成功", "expectedVersion", 2, "mentions", List.of("finance"));
        send(path(id), "alice", body, key, 503);
        assertThat(mentions(id)).isEmpty(); assertThat(count("application_comment", id)).isZero();
        doCallRealMethod().when(inbox).append(anyString(), any(InboxMessage.class));
        send(path(id), "alice", body, key, 201); assertThat(mentions(id)).hasSize(1); assertThat(count("application_comment", id)).isEqualTo(1);
    }

    @Test void completedReaderRemainsInThisRoundButIsNotCarriedIntoResubmission() throws Exception {
        String id = application(true);
        act(id, "finance", "APPROVE", 2);
        assertThat(options(id, "alice", "").path("items").toString()).isEqualTo("[\"finance\",\"manager\"]");
        add(id, List.of("finance", "manager"), 3, 201);
        act(id, "manager", "RETURN", 3);
        send("/api/v1/applications/" + id + "/submit", "alice", Map.of("expectedVersion", 4), UUID.randomUUID().toString(), 200);
        assertThat(options(id, "alice", "").path("items").toString()).isEqualTo("[\"finance\"]");
        add(id, List.of("manager"), 5, 409);
        assertThat(count("application_comment", id)).isEqualTo(1);
        assertThat(mentions(id)).hasSize(2);
    }

    private String application(boolean twoSteps) throws Exception {
        return application(twoSteps, false);
    }

    private String application(boolean twoSteps, boolean copyBob) throws Exception {
        var nodes = new ArrayList<Map<String,Object>>();
        nodes.add(Map.of("id","start","name","开始","type","START","properties",Map.of()));
        if (copyBob) nodes.add(Map.of("id", "copy", "name", "本轮抄送", "type", "COPY", "properties",
                Map.of("recipientRule", "role:ORG_PERSON_" + people.personBySubject("demo", "bob").orElseThrow().id())));
        nodes.add(userTask("finance", "finance"));
        if (twoSteps) nodes.add(userTask("manager", "manager"));
        nodes.add(Map.of("id","end","name","结束","type","END","properties",Map.of()));
        var edges = new ArrayList<Map<String,Object>>();
        for (int i=1;i<nodes.size();i++) edges.add(Map.of("id","e"+i,"source",nodes.get(i-1).get("id"),"target",nodes.get(i).get("id"),"condition",""));
        String key="mention-"+UUID.randomUUID();
        var draft=send("/api/v1/process-definitions","admin",Map.of("key",key,"name","评论提醒验收","graph",Map.of("nodes",nodes,"edges",edges)),UUID.randomUUID().toString(),200);
        send("/api/v1/process-definitions/"+draft.path("id").asText()+"/publish?expectedRevision="+draft.path("revision").asInt(),"admin",Map.of("changeNote","核对提醒原读取关系"),UUID.randomUUID().toString(),200);
        var app=send("/api/v1/applications","alice",Map.of("businessNo","MENTION-"+UUID.randomUUID(),"processKey",key,"definitionVersion",1,"title","评论提醒测试","payload",Map.of()),UUID.randomUUID().toString(),201);
        String id=app.path("id").asText();send("/api/v1/applications/"+id+"/submit","alice",Map.of("expectedVersion",1),UUID.randomUUID().toString(),200);return id;
    }
    private Map<String,Object> userTask(String id,String subject) {
        return Map.of("id",id,"name",subject+"复核","type","USER_TASK","properties",Map.of("assigneeRule","role:ORG_PERSON_"+people.personBySubject("demo",subject).orElseThrow().id()));
    }
    private JsonNode options(String id,String user,String query) throws Exception {
        var r=mvc.perform(get(path(id)+"/mention-options"+query).header("Authorization",token(user))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        return json.read(r.getResponse().getContentAsString(),JsonNode.class);
    }
    private JsonNode add(String id,List<String> mentions,int version,int status) throws Exception { return send(path(id),"alice",Map.of("content","本次提醒","expectedVersion",version,"mentions",mentions),UUID.randomUUID().toString(),status); }
    private JsonNode send(String path,String user,Object body,String key,int status) throws Exception {
        var r=mvc.perform(post(path).header("Authorization",token(user)).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.write(body))).andExpect(status().is(status)).andReturn();
        return json.read(r.getResponse().getContentAsString(),JsonNode.class);
    }
    private void act(String id,String user,String action,long version) throws Exception {
        String task=jdbc.queryForObject("SELECT ID_ FROM ACT_RU_TASK WHERE PROC_INST_ID_=(SELECT process_instance_id FROM approval_submission_round WHERE application_id=? AND round_no=(SELECT round_no FROM approval_application WHERE id=?))",String.class,id,id);
        send("/api/v1/tasks/"+task+"/actions",user,Map.of("action",action,"expectedVersion",version,"comment","核对本轮"),UUID.randomUUID().toString(),200);
    }
    private List<Map<String,Object>> mentions(String id) { return jdbc.queryForList("SELECT recipient_id AS RECIPIENT_ID,content AS CONTENT FROM notification_inbox WHERE application_id=? AND kind='COMMENT_MENTIONED'",id); }
    private long count(String table,String id) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE application_id=?",Long.class,id); }
    private String path(String id) { return "/api/v1/applications/"+id+"/comments"; }
    private String token(String user) { return "Bearer "+auth.login("demo",user,"demo").token(); }
}
