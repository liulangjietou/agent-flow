package io.agentflow.notification;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.definition.DefinitionModels.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

/** 本人设置、同意撤销、审批事务与外发意向的真实数据库边界。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.sla.reminders-enabled=false", "agentflow.timers.enabled=false"})
@AutoConfigureMockMvc
class NotificationPreferencesIntegrationTest {
    private static final String PATH = "/api/v1/notifications/preferences";
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationPreferencesService service;
    @Autowired NotificationPreferencesRepository preferences;
    @Autowired InboxRepository inbox;
    @Autowired DefinitionApplicationService definitions;
    @SpyBean NotificationDispatchPlanner planner;

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_NOTIFICATION_PREF_URL", "jdbc:h2:mem:notification-preferences;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_NOTIFICATION_PREF_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_NOTIFICATION_PREF_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_NOTIFICATION_PREF_PASSWORD", ""));
    }

    @Test void personalApiRejectsIdentityOverridesAndReplaysWithoutReenablingLaterDisabledChannel() throws Exception {
        var defaults = read("alice"); assertThat(defaults.path("inAppEnabled").asBoolean()).isTrue();
        assertThat(defaults.path("version").asLong()).isZero(); assertThat(defaults.path("updatedAt").isNull()).isTrue();
        assertThat(count("notification_preferences", "demo", "alice")).isZero();
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH + "?recipient=alice").header("Authorization", token("admin"))).andExpect(status().isBadRequest());
        String key=UUID.randomUUID().toString(); var body=Map.of("emailEnabled",true,"enterpriseImEnabled",false,"expectedVersion",0);
        var first=write("alice",body,key,200); assertThat(first.path("version").asLong()).isEqualTo(1);
        assertThat(read("admin").path("emailEnabled").asBoolean()).isFalse();
        write("alice",Map.of("emailEnabled",false,"enterpriseImEnabled",false,"expectedVersion",1),UUID.randomUUID().toString(),200);
        assertThat(write("alice",body,key,200)).isEqualTo(first);
        assertThat(read("alice").path("emailEnabled").asBoolean()).isFalse();
        write("alice",body,UUID.randomUUID().toString(),409);
        for (var extra : Map.of("tenantId","other","recipient","admin","inAppEnabled",false,"emailAddress","arbitrary@example.invalid").entrySet()) {
            var invalid=new java.util.HashMap<String,Object>(body); invalid.put(extra.getKey(),extra.getValue());
            write("alice",invalid,UUID.randomUUID().toString(),400);
        }
        write("alice",Map.of("emailEnabled",true,"expectedVersion",2),UUID.randomUUID().toString(),400);
        write("alice",Map.of("emailEnabled",true,"enterpriseImEnabled",true,"expectedVersion",-1),UUID.randomUUID().toString(),400);
        assertThat(count("notification_preference_change","demo","alice")).isEqualTo(2);
    }

    @Test void defaultsDoNotQueueAndEnablingDoesNotBackfillOrDuplicateAnExistingEvent() {
        Actor actor=actor(); var old=message(actor);
        inbox.append("old",old); assertThat(dispatches(actor)).isEmpty();
        service.revise(actor,0,true,true);
        inbox.append("old",old); assertThat(dispatches(actor)).isEmpty();
        var current=message(actor); inbox.append("new",current); inbox.append("new",current);
        assertThat(dispatches(actor)).hasSize(2);
        assertThat(dispatches(actor)).allSatisfy(row -> { assertThat(row.get("STATUS")).isEqualTo("PENDING"); assertThat(((Number)row.get("CONSENT_GENERATION")).longValue()).isEqualTo(1); });
        assertThat(count("notification_inbox",actor.tenantId(),actor.userId())).isEqualTo(2);
    }

    @Test void disablingKeepsInAppHistoryAndReenablingNeverRevivesOldConsent() {
        Actor actor=actor();service.revise(actor,0,true,true);inbox.append("first",message(actor));
        service.revise(actor,1,false,true);
        var pending=dispatches(actor);assertThat(pending.stream().filter(row->row.get("CHANNEL").equals("EMAIL"))).allSatisfy(row->assertThat(row.get("STATUS")).isEqualTo("SUPPRESSED"));
        assertThat(pending.stream().filter(row->row.get("CHANNEL").equals("ENTERPRISE_IM"))).allSatisfy(row->assertThat(row.get("STATUS")).isEqualTo("PENDING"));
        service.revise(actor,2,true,true);inbox.append("second",message(actor));
        assertThat(dispatches(actor)).hasSize(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_dispatch WHERE tenant_id=? AND recipient_id=? AND channel='EMAIL' AND status='PENDING' AND consent_generation=3",Long.class,actor.tenantId(),actor.userId())).isEqualTo(1);
        assertThat(count("notification_inbox",actor.tenantId(),actor.userId())).isEqualTo(2);
        assertThat(preferences.get("other",actor.userId()).emailEnabled()).isFalse();
    }

    @Test void concurrentFirstWritesHaveExactlyOneWinnerAndOneHistoryRecord() throws Exception {
        Actor actor=actor(); var executor=Executors.newFixedThreadPool(4);var start=new CountDownLatch(1);
        try {
            var results=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int index=0;index<4;index++) results.add(executor.submit(()->{
                start.await();try { service.revise(actor,0,true,true);return true; }
                catch(DomainException conflict) { assertThat(conflict.code()).isEqualTo("CONCURRENCY_CONFLICT");return false; }
            }));
            start.countDown();int winners=0;for(var result:results) if(result.get(15,TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);assertThat(count("notification_preference_change",actor.tenantId(),actor.userId())).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @Test void suppressionFailureRollsBackPreferenceAndHistory() {
        Actor actor=actor();service.revise(actor,0,true,false);inbox.append("first",message(actor));
        doAnswer(call->{call.callRealMethod();throw new DomainException("DEPENDENCY_UNAVAILABLE","Synthetic dispatch failure");}).when(dispatchSpy()).suppressRevoked(any());
        assertThatThrownBy(()->service.revise(actor,1,false,false)).isInstanceOf(DomainException.class);
        assertThat(service.get(actor).emailEnabled()).isTrue();assertThat(service.get(actor).version()).isEqualTo(1);
        assertThat(dispatches(actor)).allSatisfy(row->assertThat(row.get("STATUS")).isEqualTo("PENDING"));
        assertThat(count("notification_preference_change",actor.tenantId(),actor.userId())).isEqualTo(1);
    }

    @Test void dispatchFailureRollsBackSubmissionAndOriginalRequestCanRetry() throws Exception {
        Actor finance=new Actor("demo","finance",Set.of("FINANCE"));var prior=service.get(finance);service.revise(finance,prior.version(),true,true);
        String process="notify-pref-"+UUID.randomUUID();
        var graph=new Graph(List.of(new Node("start","开始",NodeType.START,Map.of()),new Node("review","财务复核",NodeType.USER_TASK,Map.of("assigneeRule","role:FINANCE")),new Node("end","结束",NodeType.END,Map.of())),
                List.of(new Edge("e1","start","review",""),new Edge("e2","review","end","")));
        var definition=definitions.create("demo",process,"通知偏好事务",graph);definitions.publish(new Actor("demo","admin",Set.of("ADMIN")),definition.id(),0,"偏好验收");
        var response=mvc.perform(post("/api/v1/applications").header("Authorization",token("alice")).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("businessNo",process,"processKey",process,"definitionVersion",1,"title","通知事务验收","payload",Map.of())))).andExpect(status().isCreated()).andReturn();
        String id=json.read(response.getResponse().getContentAsString(),JsonNode.class).path("id").asText(),key=UUID.randomUUID().toString();
        doAnswer(call->{call.callRealMethod();if(((InboxMessage)call.getArgument(0)).recipient().equals("finance")) throw new DomainException("DEPENDENCY_UNAVAILABLE","Synthetic outbox write failure");return null;}).when(dispatchSpy()).append(any());
        submit(id,key,503);
        assertThat(jdbc.queryForObject("SELECT version FROM approval_application WHERE id=?",Long.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=?",Long.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_submission_round WHERE application_id=?",Long.class,id)).isZero();
        doCallRealMethod().when(dispatchSpy()).append(any());submit(id,key,200);submit(id,key,200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_dispatch d JOIN notification_inbox n ON n.id=d.inbox_id WHERE n.application_id=? AND n.recipient_id='finance'",Long.class,id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT version FROM approval_application WHERE id=?",Long.class,id)).isEqualTo(2);
    }

    private NotificationDispatchPlanner dispatchSpy() { return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(planner); }
    private Actor actor() { return new Actor("pref-"+UUID.randomUUID(),"alice",Set.of()); }
    private InboxMessage message(Actor actor) { return new InboxMessage(UUID.randomUUID(),actor.tenantId(),actor.userId(),UUID.randomUUID(),"消息标题","NO",InboxMessage.Kind.COMMENT_MENTIONED,"manager",null,null,1,Instant.now(),null,"原私有正文不能复制至外发意向"); }
    private List<Map<String,Object>> dispatches(Actor actor) { return jdbc.queryForList("SELECT * FROM notification_dispatch WHERE tenant_id=? AND recipient_id=?",actor.tenantId(),actor.userId()); }
    private long count(String table,String tenant,String user) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=? AND recipient_id=?",Long.class,tenant,user); }
    private String token(String user) { return "Bearer "+auth.login("demo",user,"demo").token(); }
    private JsonNode read(String user) throws Exception { return json.read(mvc.perform(get(PATH).header("Authorization",token(user))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString(),JsonNode.class); }
    private JsonNode write(String user,Object body,String key,int expected) throws Exception { return json.read(mvc.perform(put(PATH).header("Authorization",token(user)).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.write(body))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(),JsonNode.class); }
    private void submit(String id,String key,int expected) throws Exception { mvc.perform(post("/api/v1/applications/"+id+"/submit").header("Authorization",token("alice")).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}")).andExpect(status().is(expected)); }
}
