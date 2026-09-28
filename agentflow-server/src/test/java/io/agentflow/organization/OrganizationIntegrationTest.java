package io.agentflow.organization;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 独立租户通过真实管理 API 验证多任职、同租户引用、部门环、乐观修订和审计原子性。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=false", "agentflow.sla.reminders-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class OrganizationIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("agentflow.organization-test.jdbc-url", "jdbc:h2:mem:organization;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        registry.add("spring.datasource.driver-class-name", () -> System.getProperty("agentflow.organization-test.jdbc-driver", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getProperty("agentflow.organization-test.jdbc-user", "sa"));
        registry.add("spring.datasource.password", () -> System.getProperty("agentflow.organization-test.jdbc-password", ""));
    }

    private static final String API = "/api/v1/organization";
    @Autowired MockMvc mvc;
    @MockitoSpyBean AuthService auth;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationRepository repository;
    @Autowired OrganizationService service;
    private String tenant;
    private String token;

    @BeforeEach
    void identity() {
        tenant = "org-" + UUID.randomUUID(); token = UUID.randomUUID().toString();
        doReturn(new Actor(tenant, "admin", Set.of("ADMIN", "APPROVER"))).when(auth).authenticate(token);
    }

    @Test
    void initializesExplicitlyAndWritesAreIdempotentBeforeAnyOrganizationRecordsExist() throws Exception {
        assertThat(read("").path("initialized").asBoolean()).isFalse();
        String requestId = UUID.randomUUID().toString();
        var first = write(post(API + "/initialize"), null, requestId, 201);
        assertThat(write(post(API + "/initialize"), null, requestId, 201)).isEqualTo(first);
        assertThat(read("").path("initialized").asBoolean()).isTrue();
        write(post(API + "/initialize"), null, UUID.randomUUID().toString(), 409);
        assertThat(read("/people").path("items")).isEmpty();
        assertThat(repository.initialized("other-" + tenant)).isFalse();
    }

    @Test
    void systemChecksReportInitializedLocalDirectoryWithoutChangingItsRecords() throws Exception {
        initialize();
        var person = person("private-organization-subject");
        var directoryBefore = jdbc.queryForList("SELECT * FROM organization_directory WHERE tenant_id=?", tenant);
        var peopleBefore = jdbc.queryForList("SELECT * FROM organization_person WHERE tenant_id=?", tenant);
        var changesBefore = jdbc.queryForList("SELECT * FROM organization_change WHERE tenant_id=?", tenant);

        var response = mvc.perform(get("/api/v1/system/checks").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("checks[?(@.id == 'organization')].status").value("UP"))
                .andExpect(jsonPath("checks[?(@.id == 'organization')].code").value("LOCAL_ORGANIZATION_ENABLED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(person.path("subject").asText(), person.path("id").asText());
        assertThat(jdbc.queryForList("SELECT * FROM organization_directory WHERE tenant_id=?", tenant)).isEqualTo(directoryBefore);
        assertThat(jdbc.queryForList("SELECT * FROM organization_person WHERE tenant_id=?", tenant)).isEqualTo(peopleBefore);
        assertThat(jdbc.queryForList("SELECT * FROM organization_change WHERE tenant_id=?", tenant)).isEqualTo(changesBefore);

        String otherTenant = "other-" + UUID.randomUUID();
        String otherToken = UUID.randomUUID().toString();
        doReturn(new Actor(otherTenant, "admin", Set.of("ADMIN"))).when(auth).authenticate(otherToken);
        mvc.perform(get("/api/v1/system/checks").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("checks[?(@.id == 'organization')].status").value("WARNING"))
                .andExpect(jsonPath("checks[?(@.id == 'organization')].code").value("LOCAL_ORGANIZATION_NOT_INITIALIZED"));
        assertThat(repository.initialized(otherTenant)).isFalse();
    }

    @Test
    void peopleUseStableSubjectsAndMultipleAppointmentsRetainHistory() throws Exception {
        initialize();
        var legal = unit("LEGAL_ENTITY", "公司", null, null);
        var firstDepartment = unit("DEPARTMENT", "一部", legal, null);
        var secondDepartment = unit("DEPARTMENT", "二部", legal, null);
        var position = unit("POSITION", "审核岗", legal, null);
        var person = person("issuer:subject/中文");
        var first = appointment(person, firstDepartment, position, 201);
        appointment(person, secondDepartment, position, 201);
        appointment(person, firstDepartment, position, 409);
        assertThat(read("/appointments?personId=" + person.path("id").asText()).path("items")).hasSize(2);
        var stopped = write(put(API + "/people/" + person.path("id").asText()),
                Map.of("displayName", "停用人员", "active", false, "approvalEligible", true, "expectedRevision", 1), 200);
        assertThat(stopped.path("subject").asText()).isEqualTo("issuer:subject/中文");
        assertThat(repository.personBySubject(tenant, "issuer:subject/中文").orElseThrow().canApprove()).isFalse();
        var ended = write(put(API + "/appointments/" + first.path("id").asText()), Map.of("active", false, "expectedRevision", 1), 200);
        assertThat(ended.path("personId")).isEqualTo(first.path("personId"));
        assertThat(read("/changes?limit=1").path("nextBeforeRevision").asLong()).isPositive();
        assertThat(repository.changes(tenant, null, 100)).anySatisfy(change -> {
            assertThat(change.actor()).isEqualTo("admin");
            assertThat(change.snapshotJson()).contains("issuer:subject/中文", "\"active\":true");
        });
    }

    @Test
    void rejectsDepartmentCyclesAndCrossLegalOrCrossTenantRelationsWithoutPartialWrites() throws Exception {
        initialize();
        var legal = unit("LEGAL_ENTITY", "公司", null, null);
        var parent = unit("DEPARTMENT", "父部", legal, null);
        var child = unit("DEPARTMENT", "子部", legal, parent);
        long before = changeCount();
        write(put(API + "/units/" + parent.path("id").asText()), Map.of("name", "环", "active", true,
                "parentDepartmentId", child.path("id").asText(), "expectedRevision", 1), 422);
        assertThat(changeCount()).isEqualTo(before);
        var otherCompany = unit("LEGAL_ENTITY", "另一个法人", null, null);
        var otherPosition = unit("POSITION", "岗位", otherCompany, null);
        var person = person("employee");
        appointment(person, child, otherPosition, 422);
        String otherTenant = "other-" + UUID.randomUUID();
        var foreignActor = new Actor(otherTenant, "foreign-admin", Set.of("ADMIN"));
        service.initialize(foreignActor);
        var foreign = service.createUnit(foreignActor, OrganizationUnit.Kind.LEGAL_ENTITY, "外国公司", null, null, true);
        write(post(API + "/units"), Map.of("kind", "DEPARTMENT", "name", "跨租户部门", "active", true, "legalEntityId", foreign.id().toString()), 404);
        assertThat(repository.unit(tenant, foreign.id())).isEmpty();
        assertThat(repository.changes(otherTenant, null, 100)).hasSize(1);
    }

    @Test
    void staleUpdatesDoNotAlterEntityOrAuditAndDuplicateSubjectsAreTenantScoped() throws Exception {
        initialize(); var person = person("same-user");
        write(post(API + "/people"), Map.of("subject", "same-user", "displayName", "重复", "active", true, "approvalEligible", true), 409);
        var second = write(put(API + "/people/" + person.path("id").asText()), Map.of("displayName", "第二版", "active", true, "approvalEligible", false, "expectedRevision", 1), 200);
        long before = changeCount();
        write(put(API + "/people/" + person.path("id").asText()), Map.of("displayName", "旧请求", "active", false, "approvalEligible", true, "expectedRevision", 1), 409);
        assertThat(changeCount()).isEqualTo(before);
        assertThat(read("/people").path("items").get(0)).isEqualTo(second);
        var other = new Actor("other-" + UUID.randomUUID(), "admin", Set.of("ADMIN")); service.initialize(other);
        assertThat(service.createPerson(other, "same-user", "同名不同租户", true, true).subject()).isEqualTo("same-user");
    }

    @Test
    void onlyTenantAdminCanReadOrWriteAndQueryDoesNotAcceptTenantOverride() throws Exception {
        mvc.perform(get(API)).andExpect(status().isUnauthorized());
        String restricted = UUID.randomUUID().toString();
        doReturn(new Actor(tenant, "process-admin", Set.of("PROCESS_ADMIN"))).when(auth).authenticate(restricted);
        mvc.perform(get(API).header("Authorization", "Bearer " + restricted)).andExpect(status().isForbidden());
        mvc.perform(post(API + "/initialize").header("Authorization", "Bearer " + restricted).header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isForbidden());
        for (String query : new String[]{"/people?tenantId=foreign", "/people?limit=101", "/people?afterId=x", "/units", "/units?kind=invalid", "/changes?beforeRevision=0"}) {
            mvc.perform(get(API + query).header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        }
        assertThat(repository.initialized(tenant)).isFalse();
    }

    @Test
    void auditFailureRollsBackThePersonAndTheDirectoryRevision() throws Exception {
        initialize(); var value = person("atomic");
        Long current = jdbc.queryForObject("SELECT revision FROM organization_directory WHERE tenant_id=?", Long.class, tenant);
        jdbc.update("INSERT INTO organization_change(tenant_id,revision,actor,kind,record_id,snapshot_json,occurred_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                tenant, current + 1, "fixture", "PERSON", UUID.randomUUID().toString(), "{}");
        assertThatThrownBy(() -> service.updatePerson(new Actor(tenant, "admin", Set.of("ADMIN", "APPROVER")), UUID.fromString(value.path("id").asText()), "不可半保存", false, false, 1))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(repository.person(tenant, UUID.fromString(value.path("id").asText())).orElseThrow().active()).isTrue();
        assertThat(jdbc.queryForObject("SELECT revision FROM organization_directory WHERE tenant_id=?", Long.class, tenant)).isEqualTo(current);
    }

    private void initialize() throws Exception { write(post(API + "/initialize"), null, 201); }
    private JsonNode person(String subject) throws Exception { return write(post(API + "/people"), Map.of("subject", subject, "displayName", "审批员", "active", true, "approvalEligible", true), 201); }
    private JsonNode unit(String kind, String name, JsonNode legal, JsonNode parent) throws Exception {
        var body = new java.util.HashMap<String, Object>(Map.of("kind", kind, "name", name, "active", true));
        if (legal != null) body.put("legalEntityId", legal.path("id").asText());
        if (parent != null) body.put("parentDepartmentId", parent.path("id").asText());
        return write(post(API + "/units"), body, 201);
    }
    private JsonNode appointment(JsonNode person, JsonNode department, JsonNode position, int expected) throws Exception {
        return write(post(API + "/appointments"), Map.of("personId", person.path("id").asText(), "departmentId", department.path("id").asText(), "positionId", position.path("id").asText(), "active", true), expected);
    }
    private long changeCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM organization_change WHERE tenant_id=?", Long.class, tenant); }
    private JsonNode read(String path) throws Exception {
        var result = mvc.perform(get(API + path).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        return json.read(result.getResponse().getContentAsString(), JsonNode.class);
    }
    private JsonNode write(MockHttpServletRequestBuilder request, Object body, int expected) throws Exception { return write(request, body, UUID.randomUUID().toString(), expected); }
    private JsonNode write(MockHttpServletRequestBuilder request, Object body, String key, int expected) throws Exception {
        request.header("Authorization", "Bearer " + token).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON);
        if (body != null) request.content(json.write(body));
        return json.read(mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
}
