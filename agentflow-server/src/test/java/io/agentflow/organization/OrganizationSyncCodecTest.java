package io.agentflow.organization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 实际来源 JSON 的精确字段、租户游标、类型及有界文档校验。
 * @author owlzhangfq@gmail.com
 */
class OrganizationSyncCodecTest {
    private final JsonUtil json = new JsonUtil(new ObjectMapper());
    private final OrganizationSyncCodec codec = new OrganizationSyncCodec(json);

    @Test void decodesOnlyOrganizationFactsAndPreservesExplicitDeactivation() {
        var delta = codec.read(json.write(wire()), "tenant", "hr", 0);
        assertThat(delta.size()).isEqualTo(5); assertThat(delta.people().get(0).subject()).isEqualTo("OIDC:Subject/原值");
        assertThat(delta.people().get(0).active()).isFalse(); assertThat(delta.people().get(0).approvalEligible()).isTrue();
        assertThat(delta.units().get(1).headAppointment()).isEqualTo(new OrganizationSyncKey(OrganizationSyncKey.Kind.APPOINTMENT, "job"));
        assertThat(delta.appointments().get(0).supervisorAppointment()).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"tenant", "source", "cursor", "protocol", "missing", "role", "credential", "unit-extra", "appointment-extra", "string-boolean", "fractional-revision", "overflow-revision", "string-cursor", "null-list", "null-reference", "unknown-kind", "oversize-name"})
    void rejectsSubstitutedScopeAndNonContractFields(String variant) {
        ObjectNode body = wire(); ObjectNode person = (ObjectNode) body.at("/people/0"), unit = (ObjectNode) body.at("/units/0"), job = (ObjectNode) body.at("/appointments/0");
        switch (variant) {
            case "tenant" -> body.put("tenantId", "foreign");
            case "source" -> body.put("sourceKey", "other");
            case "cursor" -> body.put("afterRevision", 1);
            case "protocol" -> body.put("contractVersion", 2);
            case "missing" -> person.remove("approvalEligible");
            case "role" -> person.putArray("roles").add("ADMIN");
            case "credential" -> body.put("password", "not-allowed");
            case "unit-extra" -> unit.put("ownerSubject", "admin");
            case "appointment-extra" -> job.put("systemRole", "APPROVER");
            case "string-boolean" -> person.put("active", "false");
            case "fractional-revision" -> body.put("revision", 1.5);
            case "overflow-revision" -> body.put("revision", new java.math.BigInteger("9223372036854775808"));
            case "string-cursor" -> body.put("afterRevision", "0");
            case "null-list" -> body.putNull("appointments");
            case "null-reference" -> job.putNull("personId");
            case "unknown-kind" -> unit.put("kind", "ROLE");
            case "oversize-name" -> person.put("displayName", "名".repeat(129));
            default -> throw new AssertionError(variant);
        }
        invalid(json.write(body));
    }

    @ParameterizedTest @ValueSource(strings = {"duplicate", "trailing", "truncated", "null-root", "array-root"})
    void rejectsAmbiguousOrOversizedWholeDocument(String variant) {
        String body = json.write(wire());
        invalid(switch (variant) {
            case "duplicate" -> body.replace("\"revision\":1", "\"revision\":1,\"revision\":2");
            case "trailing" -> body + " {}";
            case "truncated" -> body.substring(0, body.length() - 1);
            case "null-root" -> "null";
            case "array-root" -> "[]";
            default -> throw new AssertionError(variant);
        });
    }

    @Test void rejectsOtherwiseValidUtf8FactsWhoseByteCountExceedsTheTransportLimit() {
        var root = wire(); root.withArray("units").removeAll(); root.withArray("appointments").removeAll();
        var people = root.withArray("people"); people.removeAll();
        var facts = new java.util.ArrayList<OrganizationSyncDelta.Person>();
        for (int i = 0; i < OrganizationSyncDelta.MAX_RECORDS; i++) {
            String suffix = String.format("%05d", i), id = "键".repeat(123) + suffix, subject = "号".repeat(123) + suffix, name = "人".repeat(128);
            people.addObject().put("externalId", id).put("subject", subject).put("displayName", name).put("active", false).put("approvalEligible", false);
            facts.add(new OrganizationSyncDelta.Person(new OrganizationSyncKey(OrganizationSyncKey.Kind.PERSON, id), subject, name, false, false));
        }
        assertThat(new OrganizationSyncDelta("hr", 0, 1, java.util.List.of(), facts, java.util.List.of()).size()).isEqualTo(OrganizationSyncDelta.MAX_RECORDS);
        String body = json.write(root); assertThat(body.length()).isLessThan(OrganizationSyncCodec.MAX_BYTES);
        assertThat(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(OrganizationSyncCodec.MAX_BYTES);
        invalid(body);
    }

    private ObjectNode wire() {
        return json.read("""
                {"contractVersion":1,"tenantId":"tenant","sourceKey":"hr","afterRevision":0,"revision":1,
                 "units":[
                   {"externalId":"legal","kind":"LEGAL_ENTITY","name":"法人","legalEntityId":null,"parentDepartmentId":null,"active":true,"headAppointmentId":null},
                   {"externalId":"department","kind":"DEPARTMENT","name":"部门","legalEntityId":"legal","parentDepartmentId":null,"active":true,"headAppointmentId":"job"},
                   {"externalId":"position","kind":"POSITION","name":"岗位","legalEntityId":"legal","parentDepartmentId":null,"active":true,"headAppointmentId":null}],
                 "people":[{"externalId":"person","subject":"OIDC:Subject/原值","displayName":"人员","active":false,"approvalEligible":true}],
                 "appointments":[{"externalId":"job","personId":"person","departmentId":"department","positionId":"position","active":false,"supervisorAppointmentId":null}]}
                """, ObjectNode.class);
    }
    private void invalid(String body) { assertThatThrownBy(() -> codec.read(body, "tenant", "hr", 0)).isInstanceOf(DomainException.class).extracting("code").isEqualTo("INVALID_ORGANIZATION_SYNC_DATA"); }
}
