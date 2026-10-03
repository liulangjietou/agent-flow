package io.agentflow.organization;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 校验稳定身份、停用资格和组织结构边界，不用界面字段约束代替领域不变量。
 * @author owlzhangfq@gmail.com
 */
class OrganizationModelsTest {
    @Test
    void identityIsExactAndDisablingDoesNotRewriteTheOriginalRecord() {
        var person = new OrganizationPerson(UUID.randomUUID(), "idp:subject/中文", " 王小明 ", true, true, 1);
        var stopped = person.revise("王小明", false, true, 1);
        assertThat(stopped.subject()).isEqualTo("idp:subject/中文");
        assertThat(stopped.canApprove()).isFalse();
        assertThat(person.canApprove()).isTrue();
        assertThat(stopped.revision()).isEqualTo(2);
        assertThatThrownBy(() -> stopped.revise("王小明", true, true, 1)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("CONCURRENCY_CONFLICT");
    }

    @Test
    void typedUnitsRejectSelfParentAndFieldsBelongingToOtherKinds() {
        UUID id = UUID.randomUUID(), company = UUID.randomUUID();
        assertThatThrownBy(() -> new OrganizationUnit(id, OrganizationUnit.Kind.DEPARTMENT, "部门", company, id, true, 1))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new OrganizationUnit(id, OrganizationUnit.Kind.LEGAL_ENTITY, "法人", company, null, true, 1))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new OrganizationUnit(id, OrganizationUnit.Kind.POSITION, "岗位", company, UUID.randomUUID(), true, 1))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new OrganizationUnit(id, OrganizationUnit.Kind.DEPARTMENT, "部门", null, null, true, 1))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void endingAnAppointmentPreservesItsOwnershipAndRejectsStaleRevision() {
        var job = new OrganizationAppointment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true, 1);
        var stopped = job.revise(false, 1);
        assertThat(stopped.personId()).isEqualTo(job.personId());
        assertThat(stopped.departmentId()).isEqualTo(job.departmentId());
        assertThat(stopped.positionId()).isEqualTo(job.positionId());
        assertThat(stopped.active()).isFalse();
        assertThatThrownBy(() -> stopped.revise(true, 1)).isInstanceOf(DomainException.class);
    }
}
