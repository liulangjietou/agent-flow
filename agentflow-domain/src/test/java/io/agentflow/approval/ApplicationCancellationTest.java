package io.agentflow.approval;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 作废只关闭可编辑的申请，不能覆盖审批结论、变更旧轮次或重新提交。
 * @author owlzhangfq@gmail.com
 */
class ApplicationCancellationTest {
    @ParameterizedTest
    @EnumSource(value = ApplicationStatus.class, names = {"DRAFT", "RETURNED", "WITHDRAWN"})
    void cancellationKeepsContentAndRoundAndPreventsAnyFurtherSubmission(ApplicationStatus status) {
        var value = application(status);
        value.cancel(7);
        assertThat(value.status()).isEqualTo(ApplicationStatus.CANCELLED);
        assertThat(value.version()).isEqualTo(8);
        assertThat(value.roundNo()).isEqualTo(2);
        assertThat(value.payload()).isEqualTo(Map.of("amount", "6000.01"));
        assertThat(value.title()).isEqualTo("原申请");
        assertThat(value.definitionVersion()).isEqualTo(3);
        assertThatThrownBy(() -> value.submit(8)).isInstanceOf(DomainException.class).extracting("code").isEqualTo("DOMAIN_RULE_VIOLATION");
        assertThatThrownBy(() -> value.revise(8, "覆盖", Map.of())).isInstanceOf(DomainException.class);
        assertThat(value.version()).isEqualTo(8);
    }

    @ParameterizedTest
    @EnumSource(value = ApplicationStatus.class, names = {"IN_APPROVAL", "APPROVED", "REJECTED", "REVOKED", "CANCELLED"})
    void cancellationRejectsActiveOrTerminalApplications(ApplicationStatus status) {
        var value = application(status);
        assertThatThrownBy(() -> value.cancel(7)).isInstanceOf(DomainException.class).extracting("code").isEqualTo("DOMAIN_RULE_VIOLATION");
        assertThat(value.status()).isEqualTo(status);
        assertThat(value.version()).isEqualTo(7);
    }

    @ParameterizedTest
    @EnumSource(ApplicationStatus.class)
    void revocationOnlyClosesApprovedApplicationAndKeepsTheOriginalRound(ApplicationStatus status) {
        var value = application(status);
        if (status != ApplicationStatus.APPROVED) {
            assertThatThrownBy(() -> value.revoke(7)).isInstanceOf(DomainException.class).extracting("code").isEqualTo("DOMAIN_RULE_VIOLATION");
            assertThat(value.status()).isEqualTo(status);
            assertThat(value.version()).isEqualTo(7);
            return;
        }
        assertThatThrownBy(() -> value.revoke(6)).isInstanceOf(DomainException.class).extracting("code").isEqualTo("CONCURRENCY_CONFLICT");
        value.revoke(7);
        assertThat(value.status()).isEqualTo(ApplicationStatus.REVOKED);
        assertThat(value.version()).isEqualTo(8);
        assertThat(value.roundNo()).isEqualTo(2);
        assertThat(value.definitionVersion()).isEqualTo(3);
        assertThat(value.payload()).isEqualTo(Map.of("amount", "6000.01"));
        assertThatThrownBy(() -> value.submit(8)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> value.revise(8, "不能覆盖", Map.of())).isInstanceOf(DomainException.class);
    }

    @Test
    void staleCancellationCannotOverwriteAChangedApplication() {
        var value = application(ApplicationStatus.DRAFT);
        assertThatThrownBy(() -> value.cancel(6)).isInstanceOf(DomainException.class).extracting("code").isEqualTo("CONCURRENCY_CONFLICT");
        assertThat(value.status()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(value.version()).isEqualTo(7);
    }

    @Test
    void parentCancellationEndsOnlyTheCurrentApprovalAndKeepsTheSubmittedContent() {
        var value = application(ApplicationStatus.IN_APPROVAL);
        value.cancelWithParent(7);
        assertThat(value.status()).isEqualTo(ApplicationStatus.CANCELLED);
        assertThat(value.version()).isEqualTo(8);
        assertThat(value.roundNo()).isEqualTo(2);
        assertThat(value.definitionVersion()).isEqualTo(3);
        assertThat(value.payload()).isEqualTo(Map.of("amount", "6000.01"));
        assertThat(value.title()).isEqualTo("原申请");
        assertThatThrownBy(() -> value.submit(8)).isInstanceOf(DomainException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ApplicationStatus.class, names = "IN_APPROVAL", mode = EnumSource.Mode.EXCLUDE)
    void parentCancellationCannotOverwriteAnExistingConclusionOrEditableDraft(ApplicationStatus status) {
        var value = application(status);
        assertThatThrownBy(() -> value.cancelWithParent(7)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("DOMAIN_RULE_VIOLATION");
        assertThat(value.status()).isEqualTo(status);
        assertThat(value.version()).isEqualTo(7);
    }

    @Test
    void staleParentCancellationCannotOverwriteAChangedApproval() {
        var value = application(ApplicationStatus.IN_APPROVAL);
        assertThatThrownBy(() -> value.cancelWithParent(6)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("CONCURRENCY_CONFLICT");
        assertThat(value.status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
        assertThat(value.version()).isEqualTo(7);
    }

    private Application application(ApplicationStatus status) {
        return Application.restore(UUID.randomUUID(), "demo", "CANCEL-1", "leave", 3, "alice", "原申请", Map.of("amount", "6000.01"), status, 2, 7);
    }
}
