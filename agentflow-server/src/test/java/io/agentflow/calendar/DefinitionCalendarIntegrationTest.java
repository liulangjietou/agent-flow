package io.agentflow.calendar;

import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.definition.DefinitionValidationException;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 设计器只读目录沿用租户边界，发布必须解析明确日历修订，失败不部署引擎定义。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:business-calendar;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class DefinitionCalendarIntegrationTest {
    private static final String PATH = "/api/v1/process-definitions/calendar-options";
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired BusinessCalendarRepository calendars;
    @Autowired DefinitionApplicationService definitions;
    @Autowired RepositoryService engine;
    @Autowired CurrentActor currentActor;
    @Autowired DefinitionCalendarController options;
    @Autowired BusinessCalendarController management;

    @Test
    void designerReadsFixedRevisionsAndPaginationWithoutReceivingRules() throws Exception {
        var first = calendar("demo"); calendars.create(first);
        var second = first.revise("新作息", first.rules(), 1, "admin", Instant.now()); calendars.update(second, 1);
        mvc.perform(get(PATH).header("Authorization", token("admin"))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(PATH + "/" + first.id() + "/versions").param("limit", "1").header("Authorization", token("admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("items[0].revision").value(2)).andExpect(jsonPath("nextBeforeRevision").value(2));
        mvc.perform(get(PATH + "/" + first.id() + "/versions").param("limit", "1").param("beforeRevision", "2").header("Authorization", token("admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("items[0].revision").value(1)).andExpect(jsonPath("nextBeforeRevision").isEmpty());
        mvc.perform(get(PATH + "/" + first.id() + "/versions/1").header("Authorization", token("admin")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("name").value(first.name())).andExpect(jsonPath("revision").value(1))
                .andExpect(jsonPath("rules").doesNotExist()).andExpect(jsonPath("tenantId").doesNotExist());
    }

    @Test
    void processAdministratorCanSelectCalendarsWithoutGainingCalendarManagementPermission() {
        var calendar = calendar("demo"); calendars.create(calendar);
        currentActor.set(new Actor("demo", "designer", Set.of("PROCESS_ADMIN")));
        try {
            assertThat(options.list(Map.of()).getBody()).isNotNull();
            assertThat(options.versions(calendar.id(), Map.of()).getBody().items()).hasSize(1);
            assertThat(options.version(calendar.id(), 1).getBody().id()).isEqualTo(calendar.id());
            assertThatThrownBy(() -> management.list(Map.of())).isInstanceOf(DomainException.class).extracting("code").isEqualTo("FORBIDDEN");
        } finally { currentActor.clear(); }
    }

    @Test
    void deniesAnonymousOrdinaryAccountsForeignCalendarsAndTenantQueryInjection() throws Exception {
        var foreign = calendar("other-tenant"); calendars.create(foreign);
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        for (String suffix : List.of("", "/" + foreign.id() + "/versions", "/" + foreign.id() + "/versions/1")) {
            mvc.perform(get(PATH + suffix).header("Authorization", token("alice"))).andExpect(status().isForbidden());
        }
        for (UUID id : List.of(foreign.id(), UUID.randomUUID())) {
            mvc.perform(get(PATH + "/" + id + "/versions").header("Authorization", token("admin"))).andExpect(status().isNotFound());
            mvc.perform(get(PATH + "/" + id + "/versions/1").header("Authorization", token("admin"))).andExpect(status().isNotFound());
        }
        mvc.perform(get(PATH).param("tenantId", "other-tenant").header("Authorization", token("admin"))).andExpect(status().isBadRequest());
        mvc.perform(get(PATH + "/" + foreign.id() + "/versions").param("beforeRevision", "0").header("Authorization", token("admin"))).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingAndCrossTenantCalendarReferencesBeforePublishingAnything() throws Exception {
        var foreign = calendar("other-tenant"); calendars.create(foreign);
        var local = calendar("demo"); calendars.create(local);
        for (var graph : List.of(graph(foreign.id(), 1), graph(UUID.randomUUID(), 1), graph(local.id(), 2))) {
            String key = "missing-calendar-" + UUID.randomUUID();
            var draft = definitions.create("demo", key, "引用校验", graph);
            mvc.perform(post("/api/v1/process-definitions/validate").header("Authorization", token("admin"))
                    .contentType("application/json").content(json.write(Map.of("graph", graph))))
                    .andExpect(status().isOk()).andExpect(jsonPath("errors[0]").value("DEADLINE_CALENDAR_UNAVAILABLE:approve"));
            assertThatThrownBy(() -> definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), draft.id(), 0, "发布测试"))
                    .isInstanceOf(DefinitionValidationException.class);
            assertThat(definitions.get("demo", draft.id()).status()).isEqualTo(DraftStatus.DRAFT);
            assertThat(engine.createProcessDefinitionQuery().processDefinitionTenantId("demo").processDefinitionKey(key).count()).isZero();
        }
    }

    @Test
    void publicationPreservesExplicitOldCalendarRevisionAfterCalendarChanges() {
        var first = calendar("demo"); calendars.create(first);
        var draft = definitions.create("demo", "fixed-calendar-" + UUID.randomUUID(), "固定日历", graph(first.id(), 1));
        calendars.update(first.revise("新规则", first.rules(), 1, "admin", Instant.now()), 1);
        var published = definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), draft.id(), 0, "保留旧修订");
        assertThat(published.graph().nodes().get(1).properties().get("deadlineCalendarRevision")).isEqualTo("1");
        assertThat(engine.createProcessDefinitionQuery().processDefinitionTenantId("demo").processDefinitionKey(published.key()).count()).isEqualTo(1);
    }

    @Test
    void identifierPrecheckPreservesCalendarValidationAndOrdinaryAccountBoundary() throws Exception {
        var local = calendar("demo"); calendars.create(local);
        var configured = graph(local.id(), 1);
        assertThat(definitions.inspect("demo", configured, null, "approve").errors())
                .containsExactly("PROCESS_KEY_CONFLICT:approve");
        assertThat(definitions.inspect("demo", configured, null, "calendar-identifier-preview").errors()).isEmpty();

        var missing = graph(UUID.randomUUID(), 1);
        assertThat(definitions.inspect("demo", missing, null, "approve").errors())
                .containsExactly("PROCESS_KEY_CONFLICT:approve");
        assertThat(definitions.inspect("demo", missing, null, "calendar-identifier-preview").errors())
                .containsExactly("DEADLINE_CALENDAR_UNAVAILABLE:approve");
        // 普通账号可以检查标识和结构，但不能借目标标识预检探测日历目录。
        mvc.perform(post("/api/v1/process-definitions/validate").header("Authorization", token("alice"))
                        .contentType("application/json").content(json.write(Map.of("key", "calendar-identifier-preview", "graph", missing))))
                .andExpect(status().isOk()).andExpect(jsonPath("errors").isEmpty());
    }

    private Graph graph(UUID id, long revision) {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("approve", "审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE", "deadlineCalendarId", id.toString(),
                        "deadlineCalendarRevision", Long.toString(revision), "deadlineWorkingMinutes", "480")),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "approve", ""), new Edge("b", "approve", "end", "")));
    }
    private BusinessCalendar calendar(String tenant) {
        return BusinessCalendar.create(tenant, "reference-" + UUID.randomUUID(), "旧作息",
                new CalendarRules("UTC", Map.of(DayOfWeek.MONDAY, List.of(new CalendarRules.Period("09:00", "18:00"))), List.of()), "admin", Instant.now());
    }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
}
