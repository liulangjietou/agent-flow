package io.agentflow.organization;

import io.agentflow.common.DomainException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static io.agentflow.organization.OrganizationSyncKey.Kind.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 来源变更必须有连续版本和精确类型；不把遗漏、姓名或身份归一化当成同步事实。
 * @author owlzhangfq@gmail.com
 */
class OrganizationSyncDeltaTest {
    private static final OrganizationSyncKey LEGAL = key(LEGAL_ENTITY, "legal");

    @Test void keepsOpaqueSubjectsAndExternalIdsAndCopiesFacts() {
        var people = new ArrayList<>(List.of(new OrganizationSyncDelta.Person(key(PERSON, " HR:001 "), " Issuer/Sub:大小写 ", " 姓名 ", false, true)));
        var delta = new OrganizationSyncDelta("hr.primary", 7, 8, List.of(), people, List.of()); people.clear();
        assertThat(delta.size()).isEqualTo(1); assertThat(delta.people().get(0).subject()).isEqualTo(" Issuer/Sub:大小写 ");
        assertThat(delta.people().get(0).key().externalId()).isEqualTo(" HR:001 ");
        assertThat(delta.people().get(0).displayName()).isEqualTo("姓名"); assertThat(delta.people().get(0).active()).isFalse();
        assertThatThrownBy(() -> delta.people().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void keepsReferencesToEarlierBatchesForLaterDirectoryPreflight() {
        var value = new OrganizationSyncDelta.Appointment(key(APPOINTMENT, "job"), key(PERSON, "old-person"), key(DEPARTMENT, "old-department"),
                key(POSITION, "old-position"), false, key(APPOINTMENT, "old-supervisor"));
        var delta = new OrganizationSyncDelta("hr", 10, 20, List.of(), List.of(), List.of(value));
        assertThat(delta.units()).isEmpty(); assertThat(delta.people()).isEmpty(); assertThat(delta.appointments()).containsExactly(value);
    }

    @Test void allowsEmptyPollingAndUnchangedOrganizationAtANewerSourceCursor() {
        assertThat(new OrganizationSyncDelta("hr", 0, 0, List.of(), List.of(), List.of()).size()).isZero();
        assertThat(new OrganizationSyncDelta("hr", 4, 8, List.of(), List.of(), List.of()).revision()).isEqualTo(8);
    }

    @ParameterizedTest @ValueSource(strings = {"backward", "negative", "same-version-with-data", "duplicate-unit", "duplicate-person", "duplicate-appointment", "null-entry"})
    void rejectsInvalidVersionOrAmbiguousRecordKeys(String variant) {
        var unit = new OrganizationSyncDelta.Unit(LEGAL, "法人", null, null, true, null);
        var person = new OrganizationSyncDelta.Person(key(PERSON, "person"), "sub", "人员", true, true);
        var appointment = new OrganizationSyncDelta.Appointment(key(APPOINTMENT, "job"), person.key(), key(DEPARTMENT, "dept"), key(POSITION, "position"), true, null);
        assertInvalid(() -> new OrganizationSyncDelta("hr", variant.equals("negative") ? -1 : 2,
                variant.equals("backward") ? 1 : variant.equals("same-version-with-data") ? 2 : 3,
                variant.equals("duplicate-unit") ? List.of(unit, unit) : variant.equals("null-entry") ? java.util.Arrays.asList(unit, null) : List.of(unit),
                variant.equals("duplicate-person") ? List.of(person, person) : List.of(person),
                variant.equals("duplicate-appointment") ? List.of(appointment, appointment) : List.of(appointment)));
    }

    @Test void sameExternalIdCanBelongToDifferentTypedNamespaces() {
        var delta = new OrganizationSyncDelta("hr", 0, 1, List.of(new OrganizationSyncDelta.Unit(key(LEGAL_ENTITY, "1"), "法人", null, null, true, null),
                new OrganizationSyncDelta.Unit(key(DEPARTMENT, "1"), "部门", key(LEGAL_ENTITY, "1"), null, true, null)),
                List.of(new OrganizationSyncDelta.Person(key(PERSON, "1"), "sub", "人员", true, false)), List.of());
        assertThat(delta.size()).isEqualTo(3);
    }

    @ParameterizedTest @ValueSource(strings = {"legal-with-parent", "legal-with-owner", "position-with-head", "department-without-legal", "self-parent", "wrong-owner-kind", "person-as-unit", "wrong-person-key", "wrong-job-reference", "self-supervisor"})
    void rejectsStructurallyInvalidReferences(String variant) {
        assertInvalid(() -> {
            switch (variant) {
                case "legal-with-parent" -> new OrganizationSyncDelta.Unit(LEGAL, "法人", null, key(DEPARTMENT, "p"), true, null);
                case "legal-with-owner" -> new OrganizationSyncDelta.Unit(LEGAL, "法人", key(LEGAL_ENTITY, "other"), null, true, null);
                case "position-with-head" -> new OrganizationSyncDelta.Unit(key(POSITION, "p"), "岗位", LEGAL, null, true, key(APPOINTMENT, "a"));
                case "department-without-legal" -> new OrganizationSyncDelta.Unit(key(DEPARTMENT, "d"), "部门", null, null, true, null);
                case "self-parent" -> new OrganizationSyncDelta.Unit(key(DEPARTMENT, "d"), "部门", LEGAL, key(DEPARTMENT, "d"), true, null);
                case "wrong-owner-kind" -> new OrganizationSyncDelta.Unit(key(POSITION, "p"), "岗位", key(DEPARTMENT, "d"), null, true, null);
                case "person-as-unit" -> new OrganizationSyncDelta.Unit(key(PERSON, "p"), "人员", LEGAL, null, true, null);
                case "wrong-person-key" -> new OrganizationSyncDelta.Person(LEGAL, "sub", "人员", true, true);
                case "wrong-job-reference" -> new OrganizationSyncDelta.Appointment(key(APPOINTMENT, "a"), key(DEPARTMENT, "d"), key(DEPARTMENT, "d"), key(POSITION, "p"), true, null);
                case "self-supervisor" -> new OrganizationSyncDelta.Appointment(key(APPOINTMENT, "a"), key(PERSON, "p"), key(DEPARTMENT, "d"), key(POSITION, "p"), true, key(APPOINTMENT, "a"));
                default -> throw new AssertionError(variant);
            }
        });
    }

    @Test void enforcesCombinedBatchBoundAcrossRecordTypes() {
        var units = IntStream.range(0, OrganizationSyncDelta.MAX_RECORDS).mapToObj(i -> new OrganizationSyncDelta.Unit(key(LEGAL_ENTITY, "l" + i), "法人", null, null, true, null)).toList();
        assertThat(new OrganizationSyncDelta("hr", 0, 1, units, List.of(), List.of()).size()).isEqualTo(OrganizationSyncDelta.MAX_RECORDS);
        assertInvalid(() -> new OrganizationSyncDelta("hr", 0, 1, units, List.of(new OrganizationSyncDelta.Person(key(PERSON, "p"), "sub", "姓名", true, true)), List.of()));
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "id\n", "id\u0000"})
    void rejectsBlankOrControlIdentifiers(String externalId) { assertInvalid(() -> key(PERSON, externalId)); }

    private static OrganizationSyncKey key(OrganizationSyncKey.Kind kind, String id) { return new OrganizationSyncKey(kind, id); }
    private static void assertInvalid(Runnable operation) { assertThatThrownBy(operation::run).isInstanceOf(DomainException.class).extracting("code").isEqualTo("INVALID_ORGANIZATION_SYNC_DATA"); }
}
