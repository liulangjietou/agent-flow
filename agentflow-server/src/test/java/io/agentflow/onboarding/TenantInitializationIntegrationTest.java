package io.agentflow.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentflow.auth.AuthService;
import io.agentflow.calendar.BusinessCalendarService;
import io.agentflow.calendar.CalendarRules;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.notification.NotificationChannel;
import io.agentflow.notification.NotificationDestinations;
import io.agentflow.notification.NotificationPreferencesService;
import io.agentflow.organization.OrganizationRepository;
import io.agentflow.organization.OrganizationService;
import io.agentflow.organization.OrganizationUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 初始化必须落到真实租户组织、日历、偏好与审计，而不是可手工勾选的完成状态。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${AGENTFLOW_INITIALIZATION_TEST_URL:jdbc:h2:mem:tenant-initialization;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000}",
        "spring.datasource.username=${AGENTFLOW_INITIALIZATION_TEST_USER:sa}",
        "spring.datasource.password=${AGENTFLOW_INITIALIZATION_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=${AGENTFLOW_INITIALIZATION_TEST_DRIVER:org.h2.Driver}",
        "agentflow.auth.demo-enabled=false", "agentflow.sla.reminders-enabled=false", "agentflow.timers.enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class TenantInitializationIntegrationTest {
    private static final String API = "/api/v1/system/initialization";
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationService organizations;
    @Autowired OrganizationRepository directory;
    @Autowired BusinessCalendarService calendars;
    @Autowired NotificationPreferencesService preferences;
    @MockitoSpyBean AuthService auth;
    @MockitoSpyBean JdbcTenantInitializationRepository initializations;
    @MockitoSpyBean NotificationDestinations destinations;
    private Actor actor;
    private String token;

    @BeforeEach void identity() {
        actor = new Actor("init-" + UUID.randomUUID(), "issuer:admin/中文", Set.of("ADMIN", "APPROVER", "EMPLOYEE"));
        token = UUID.randomUUID().toString();
        doReturn(actor).when(auth).authenticate(token);
    }

    @Test void createsActualResourcesAndReplaysWithoutDuplicatingInitialization() throws Exception {
        var before = read();
        assertThat(before.path("initialization").isNull()).isTrue();
        assertThat(before.path("organizationRevision").asLong()).isZero();
        String requestKey = UUID.randomUUID().toString();
        var body = creation();
        var first = write(body, requestKey, 201);
        assertThat(first.path("tenantId").asText()).isEqualTo(actor.tenantId());
        assertThat(first.path("initializedBy").asText()).isEqualTo(actor.userId());
        assertThat(first.path("administratorRoles")).contains(json.read("\"ADMIN\"", JsonNode.class));
        assertThat(first.path("organization").path("subject").asText()).isEqualTo(actor.userId());
        assertThat(first.path("calendar").path("revision").asLong()).isEqualTo(1);
        assertThat(write(body, requestKey, 201)).isEqualTo(first);
        write(body, UUID.randomUUID().toString(), 409);
        assertThat(read().path("initialization")).isEqualTo(first);
        assertThat(count("organization_unit")).isEqualTo(3);
        assertThat(count("organization_person")).isEqualTo(1);
        assertThat(count("organization_appointment")).isEqualTo(1);
        assertThat(count("business_calendar")).isEqualTo(1);
        assertThat(count("tenant_initialization")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE tenant_id=? AND action='TENANT_INITIALIZE'", Long.class, actor.tenantId())).isEqualTo(1);
        assertThat(count("notification_dispatch")).isZero();
        mvc.perform(get("/api/v1/operations/audit").header("Authorization", "Bearer " + token)
                        .param("source", "TenantInitialization").param("action", "TENANT_INITIALIZE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].aggregateId").value(first.path("id").asText()));
    }

    @Test void adoptsOwnAppointmentAndExactCalendarVersionWithoutChangingExistingResources() throws Exception {
        UUID appointment = existingOrganization(false);
        var calendar = calendars.create(actor, "existing", "原日历", rules());
        calendars.update(actor, calendar.id(), "后来的日历", rules(), 1);
        long revision = directory.revision(actor.tenantId());
        var body = draft(); body.put("expectedOrganizationRevision", revision);
        body.set("organization", object(Map.of("source", "EXISTING", "appointmentId", appointment)));
        body.set("calendar", object(Map.of("source", "EXISTING", "id", calendar.id(), "revision", 1)));
        var receipt = write(body, UUID.randomUUID().toString(), 201);
        assertThat(receipt.path("calendar").path("name").asText()).isEqualTo("原日历");
        assertThat(directory.revision(actor.tenantId())).isEqualTo(revision);
        assertThat(directory.personBySubject(actor.tenantId(), actor.userId()).orElseThrow().approvalEligible()).isFalse();
        assertThat(count("organization_unit")).isEqualTo(3);
        assertThat(count("business_calendar_version")).isEqualTo(2);
        calendars.update(actor, calendar.id(), "再修改日历", rules(), 2);
        var person = directory.personBySubject(actor.tenantId(), actor.userId()).orElseThrow();
        organizations.updatePerson(actor, person.id(), "后来姓名", true, true, person.revision());
        assertThat(read().path("initialization")).isEqualTo(receipt);
    }

    @Test void createsAdditionalUnitsButReusesExistingPersonWithoutElevatingEligibility() throws Exception {
        existingOrganization(false);
        var body = draft(); body.put("expectedOrganizationRevision", directory.revision(actor.tenantId()));
        write(body, UUID.randomUUID().toString(), 201);
        assertThat(count("organization_person")).isEqualTo(1);
        assertThat(count("organization_appointment")).isEqualTo(2);
        assertThat(count("organization_unit")).isEqualTo(6);
        assertThat(directory.personBySubject(actor.tenantId(), actor.userId()).orElseThrow().approvalEligible()).isFalse();
    }

    @Test void administratorWithoutApproverRoleDoesNotAcquireApprovalEligibility() throws Exception {
        actor = new Actor(actor.tenantId(), actor.userId(), Set.of("ADMIN"));
        doReturn(actor).when(auth).authenticate(token);
        var receipt = write(creation(), UUID.randomUUID().toString(), 201);
        assertThat(receipt.path("administratorRoles").size()).isEqualTo(1);
        assertThat(directory.personBySubject(actor.tenantId(), actor.userId()).orElseThrow().approvalEligible()).isFalse();
    }

    @Test void rejectsStaleDirectoryAndInactiveExistingPersonWithoutPartialWrites() throws Exception {
        existingOrganization(true);
        assertThat(write(creation(), UUID.randomUUID().toString(), 409).path("code").asText()).isEqualTo("CONCURRENCY_CONFLICT");
        var person = directory.personBySubject(actor.tenantId(), actor.userId()).orElseThrow();
        organizations.updatePerson(actor, person.id(), person.displayName(), false, true, person.revision());
        var body = draft(); body.put("expectedOrganizationRevision", directory.revision(actor.tenantId()));
        assertThat(write(body, UUID.randomUUID().toString(), 409).path("code").asText()).isEqualTo("INITIALIZATION_PERSON_CHANGED");
        assertThat(count("organization_unit")).isEqualTo(3);
        assertThat(count("tenant_initialization")).isZero();
        assertThat(count("business_calendar")).isZero();
    }

    @Test void rejectsOtherPersonsAppointmentAndOtherTenantsCalendar() throws Exception {
        UUID own = existingOrganization(true);
        var original = directory.appointment(actor.tenantId(), own).orElseThrow();
        var other = organizations.createPerson(actor, "other-admin", "其他管理员", true, true);
        var otherAppointment = organizations.createAppointment(actor, other.id(), original.departmentId(), original.positionId(), true);
        var body = draft(); body.put("expectedOrganizationRevision", directory.revision(actor.tenantId()));
        body.set("organization", object(Map.of("source", "EXISTING", "appointmentId", otherAppointment.id())));
        assertThat(write(body, UUID.randomUUID().toString(), 422).path("code").asText()).isEqualTo("INITIATOR_APPOINTMENT_UNAVAILABLE");
        var outside = calendars.create(new Actor("outside-" + UUID.randomUUID(), actor.userId(), actor.roles()), "outside", "另一租户日历", rules());
        body.set("organization", object(Map.of("source", "EXISTING", "appointmentId", own)));
        body.set("calendar", object(Map.of("source", "EXISTING", "id", outside.id(), "revision", 1)));
        write(body, UUID.randomUUID().toString(), 404);
        assertThat(count("tenant_initialization")).isZero();
        assertThat(count("business_calendar")).isZero();
    }

    @Test void calendarCollisionRollsBackNewDirectoryAndFailedKeyIsNotReserved() throws Exception {
        calendars.create(actor, "default-work", "已有同名键日历", rules());
        String key = UUID.randomUUID().toString();
        write(creation(), key, 409);
        assertEmptyInitialization();
        assertThat(count("business_calendar")).isEqualTo(1);
        assertThat(count("request_idempotency")).isZero();
        var body = draft(); ((ObjectNode) body.get("calendar")).put("key", "confirmed-work");
        write(body, key, 201);
        assertThat(count("business_calendar")).isEqualTo(2);
    }

    @Test void failureAfterReceiptAndAuditAppendRollsBackEveryResourceAndSuccessfulReplayRecord() throws Exception {
        bindEmail("a");
        var body = draft(); enableEmail(body, "a");
        JdbcTenantInitializationRepository spy = AopTestUtils.getUltimateTargetObject(initializations);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DomainException("CONCURRENCY_CONFLICT", "Injected initialization persistence failure");
        }).when(spy).append(any());
        String key = UUID.randomUUID().toString();
        write(body, key, 409);
        assertEmptyInitialization();
        for (String table : List.of("business_calendar", "business_calendar_version", "notification_preferences", "notification_preference_change", "audit_event", "request_idempotency")) {
            assertThat(count(table)).as(table).isZero();
        }
        doCallRealMethod().when(spy).append(any());
        write(body, key, 201);
        assertThat(count("notification_preference_change")).isEqualTo(1);
    }

    @Test void enabledChannelRequiresCurrentBindingAndOnlyChangesOwnPreferenceWithoutBackfill() throws Exception {
        var body = draft(); enableEmail(body, "a");
        assertThat(write(body, UUID.randomUUID().toString(), 409).path("code").asText()).isEqualTo("INITIALIZATION_CHANNEL_CHANGED");
        assertEmptyInitialization();
        bindEmail("b");
        write(body, UUID.randomUUID().toString(), 409);
        enableEmail(body, "b");
        String key = UUID.randomUUID().toString();
        var before = read();
        assertThat(before.toString()).doesNotContain("private@example.invalid", "smtp-secret");
        var other = new Actor(actor.tenantId(), "other-person", actor.roles());
        preferences.revise(other, 0, true, true);
        var receipt = write(body, key, 201);
        assertThat(preferences.get(actor).emailEnabled()).isTrue();
        assertThat(preferences.get(other).enterpriseImEnabled()).isTrue();
        preferences.revise(actor, 1, false, false);
        assertThat(write(body, key, 201)).isEqualTo(receipt);
        assertThat(preferences.get(actor).emailEnabled()).isFalse();
        assertThat(preferences.get(actor).version()).isEqualTo(2);
        assertThat(read().path("initialization").path("notifications").path("emailEnabled").asBoolean()).isTrue();
        assertThat(count("notification_dispatch")).isZero();
    }

    @Test void stalePersonalPreferencesRollBackOrganizationAndCalendar() throws Exception {
        preferences.revise(actor, 0, true, false);
        write(creation(), UUID.randomUUID().toString(), 409);
        assertEmptyInitialization();
        assertThat(count("business_calendar")).isZero();
        assertThat(preferences.get(actor).emailEnabled()).isTrue();
    }

    @Test void concurrentFreshInitializationHasExactlyOneWinner() throws Exception { concurrentInitialization(draft()); }

    @Test void concurrentInitializationOfExistingDirectoryHasExactlyOneWinner() throws Exception {
        existingOrganization(true);
        var body = draft(); body.put("expectedOrganizationRevision", directory.revision(actor.tenantId()));
        concurrentInitialization(body);
        assertThat(count("organization_unit")).isEqualTo(6);
    }

    @Test void authorizationPrecedesReplayAndTenantStateNeverCrossesScopes() throws Exception {
        String key = UUID.randomUUID().toString();
        write(creation(), key, 201);
        doReturn(new Actor(actor.tenantId(), actor.userId(), Set.of("EMPLOYEE"))).when(auth).authenticate(token);
        write(creation(), key, 403);
        mvc.perform(get(API).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        doReturn(new Actor("elsewhere-" + UUID.randomUUID(), actor.userId(), actor.roles())).when(auth).authenticate(token);
        assertThat(read().path("initialization").isNull()).isTrue();
        mvc.perform(get(API)).andExpect(status().isUnauthorized());
    }

    @Test void rejectsIdentityOverridesMissingConsentAndConflictingSourcesBeforeCreatingResources() throws Exception {
        for (String field : List.of("tenantId", "initializedBy", "administratorRoles")) {
            var body = draft(); body.put(field, "forged"); write(body, UUID.randomUUID().toString(), 400);
        }
        var body = draft(); ((ObjectNode) body.get("organization")).put("approvalEligible", true);
        write(body, UUID.randomUUID().toString(), 400);
        body = draft(); ((ObjectNode) body.get("notifications")).put("address", "forged@example.invalid");
        write(body, UUID.randomUUID().toString(), 400);
        body = draft(); body.put("confirmLocalDirectory", false); write(body, UUID.randomUUID().toString(), 400);
        body = draft(); body.remove("expectedOrganizationRevision"); write(body, UUID.randomUUID().toString(), 400);
        body = draft(); ((ObjectNode) body.get("calendar")).put("id", UUID.randomUUID().toString());
        write(body, UUID.randomUUID().toString(), 400);
        body = draft(); ((ObjectNode) body.get("notifications")).remove("emailEnabled");
        write(body, UUID.randomUUID().toString(), 400);
        mvc.perform(get(API).header("Authorization", "Bearer " + token).param("tenantId", "forged")).andExpect(status().isBadRequest());
        mvc.perform(post(API).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(json.write(creation())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        assertEmptyInitialization();
    }

    private void concurrentInitialization(Object body) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> request = () -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Concurrent requests did not start");
                return mvc.perform(post(API).header("Authorization", "Bearer " + token).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json.write(body))).andReturn().getResponse().getStatus();
            };
            var first = executor.submit(request); var second = executor.submit(request); start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
            assertThat(count("tenant_initialization")).isEqualTo(1);
            assertThat(count("business_calendar")).isEqualTo(1);
            assertThat(count("request_idempotency")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE tenant_id=? AND action='TENANT_INITIALIZE'", Long.class, actor.tenantId())).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    private UUID existingOrganization(boolean eligible) {
        organizations.initialize(actor);
        var legal = organizations.createUnit(actor, OrganizationUnit.Kind.LEGAL_ENTITY, "原法人", null, null, true);
        var department = organizations.createUnit(actor, OrganizationUnit.Kind.DEPARTMENT, "原部门", legal.id(), null, true);
        var position = organizations.createUnit(actor, OrganizationUnit.Kind.POSITION, "原岗位", legal.id(), null, true);
        var person = organizations.createPerson(actor, actor.userId(), "验收管理员", true, eligible);
        return organizations.createAppointment(actor, person.id(), department.id(), position.id(), true).id();
    }
    private CalendarRules rules() { return json.read(draft().path("calendar").path("rules").toString(), CalendarRules.class); }
    private ObjectNode draft() { return object(creation()); }
    private ObjectNode object(Object value) { return json.read(json.write(value), ObjectNode.class); }
    private void bindEmail(String value) {
        doReturn(Optional.of(new NotificationDestinations.Destination("init-binding", actor.tenantId(), actor.userId(), NotificationChannel.EMAIL,
                "private@example.invalid", null, "https://private.invalid", true, value.repeat(64), null)))
                .when(destinations).find(actor.tenantId(), actor.userId(), NotificationChannel.EMAIL);
    }
    private void enableEmail(ObjectNode body, String digest) {
        ((ObjectNode) body.get("notifications")).put("emailEnabled", true).put("emailBindingDigest", digest.repeat(64));
    }
    private void assertEmptyInitialization() {
        for (String table : List.of("organization_directory", "organization_unit", "organization_person", "organization_appointment", "organization_change", "tenant_initialization")) {
            assertThat(count(table)).as(table).isZero();
        }
    }

    private Map<String, Object> creation() {
        return Map.of("workspaceName", "初始化验收空间", "expectedOrganizationRevision", 0, "confirmLocalDirectory", true,
                "organization", Map.of("source", "CREATE", "legalEntityName", "本地验收法人", "departmentName", "运营部", "positionName", "负责人", "administratorName", "验收管理员"),
                "calendar", Map.of("source", "CREATE", "key", "default-work", "name", "已确认工作日历", "rules", Map.of("zoneId", "Asia/Shanghai", "weeklyHours", Map.of("MONDAY", List.of(Map.of("start", "09:00", "end", "18:00"))), "overrides", List.of())),
                "notifications", Map.of("expectedVersion", 0, "emailEnabled", false, "enterpriseImEnabled", false));
    }
    private JsonNode read() throws Exception {
        String response = mvc.perform(get(API).header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
        return json.read(response, JsonNode.class);
    }
    private JsonNode write(Object body, String key, int status) throws Exception {
        String response = mvc.perform(post(API).header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.write(body))).andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
        return json.read(response, JsonNode.class);
    }
    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", Long.class, actor.tenantId());
    }
}
