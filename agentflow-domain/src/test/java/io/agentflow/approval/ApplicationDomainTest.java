package io.agentflow.approval;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author owlzhangfq@gmail.com
 */
class ApplicationDomainTest {
    @Test
    void returnedApplicationCannotBeWithdrawnAgain() {
        Application application = Application.draft(UUID.randomUUID(), "tenant-a", "EXP-WITHDRAW", "expense",
                1, "alice", "申请", Map.of("amount", 6000));
        application.submit(1);
        application.returnToApplicant(2);

        assertThatThrownBy(() -> application.withdraw(3))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("DOMAIN_RULE_VIOLATION"));
        assertThat(application.status()).isEqualTo(ApplicationStatus.RETURNED);
        assertThat(application.version()).isEqualTo(3);
    }

    @Test
    void returnedApplicationGetsANewRoundWhenResubmitted() {
        Application application = Application.draft(UUID.randomUUID(), "tenant-a", "EXP-1", "expense-reimbursement",
                1, "alice", "差旅费", Map.of("amount", 1200));

        application.submit(1);
        application.returnToApplicant(2);
        application.submit(3);

        assertThat(application.status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
        assertThat(application.roundNo()).isEqualTo(2);
        assertThat(application.version()).isEqualTo(4);
    }

    @Test
    void staleVersionFailsFast() {
        Application application = Application.draft(UUID.randomUUID(), "tenant-a", "EXP-2", "expense-reimbursement",
                1, "alice", "差旅费", Map.of());

        assertThatThrownBy(() -> application.submit(99))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("version");
    }

    @ParameterizedTest
    @EnumSource(value = ApplicationStatus.class, names = {"DRAFT", "RETURNED", "WITHDRAWN"})
    void revisionChangesOnlyContentAndVersionUntilSubmission(ApplicationStatus status) {
        Application application = Application.restore(UUID.randomUUID(), "tenant-a", "EXP-REVISE", "expense",
                3, "alice", "原申请", Map.of("amount", 6000), status, 1, 7);

        application.revise(7, "已补正", Map.of("amount", 900));

        assertThat(application.title()).isEqualTo("已补正");
        assertThat(application.payload()).containsEntry("amount", 900);
        assertThat(application.status()).isEqualTo(status);
        assertThat(application.version()).isEqualTo(8);
        assertThat(application.roundNo()).isEqualTo(1);
        assertThat(application.businessNo()).isEqualTo("EXP-REVISE");
        assertThat(application.processKey()).isEqualTo("expense");
        assertThat(application.definitionVersion()).isEqualTo(3);

        application.submit(8);
        assertThat(application.roundNo()).isEqualTo(status == ApplicationStatus.DRAFT ? 1 : 2);
        assertThat(application.definitionVersion()).isEqualTo(3);
    }

    @ParameterizedTest
    @EnumSource(value = ApplicationStatus.class, names = {"IN_APPROVAL", "REJECTED", "APPROVED", "REVOKED", "CANCELLED"})
    void revisionRejectsActiveAndTerminalApplications(ApplicationStatus status) {
        Application application = Application.restore(UUID.randomUUID(), "tenant-a", "EXP-LOCKED", "expense",
                1, "alice", "原申请", Map.of("amount", 6000), status, 1, 2);

        assertThatThrownBy(() -> application.revise(2, "不能修改", Map.of("amount", 900)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("DOMAIN_RULE_VIOLATION"));
        assertThat(application.title()).isEqualTo("原申请");
        assertThat(application.version()).isEqualTo(2);
    }

    @Test
    void staleRevisionDoesNotChangeContentOrVersion() {
        Application application = Application.draft(UUID.randomUUID(), "tenant-a", "EXP-STALE", "expense",
                1, "alice", "原申请", Map.of("amount", 6000));

        assertThatThrownBy(() -> application.revise(99, "过期修改", Map.of("amount", 900)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("CONCURRENCY_CONFLICT"));
        assertThat(application.title()).isEqualTo("原申请");
        assertThat(application.version()).isEqualTo(1);
    }

    @Test
    void businessRoutingAdjustmentRequiresMatchingBindingAndPreservesTheOriginalRound() {
        var reference = new io.agentflow.approval.model.BusinessReference(io.agentflow.approval.model.BusinessReference.Type.EXPENSE, UUID.randomUUID());
        var application = Application.draftBusiness(UUID.randomUUID(), "demo", "synthetic", "expense", 1, "alice", "费用",
                Map.of("amount", "100.00"), null, null, io.agentflow.notification.NotificationTexts.EMPTY, reference);
        assertThatThrownBy(() -> application.adjustBusinessPayload(1, reference, Map.of("amount", "50.00"))).isInstanceOf(DomainException.class);
        application.submit(1); var submitted = SubmissionRound.submitted(application, "instance", "alice", Instant.now());
        var wrong = new io.agentflow.approval.model.BusinessReference(reference.type(), UUID.randomUUID());
        assertThatThrownBy(() -> application.adjustBusinessPayload(2, wrong, Map.of("amount", "50.00"))).isInstanceOf(DomainException.class);
        assertThat(application.version()).isEqualTo(2);
        application.adjustBusinessPayload(2, reference, Map.of("amount", "50.00"));
        assertThat(application.version()).isEqualTo(3); assertThat(application.roundNo()).isEqualTo(1);
        assertThat(application.status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
        assertThat(submitted.payload()).containsEntry("amount", "100.00");
        application.approve(3);
        assertThatThrownBy(() -> application.adjustBusinessPayload(4, reference, Map.of("amount", "0.00"))).isInstanceOf(DomainException.class);
        assertThat(application.payload()).containsEntry("amount", "50.00");
    }

    @Test
    void submissionSnapshotFreezesNestedMapsAndLists() {
        Map<String, Object> line = new HashMap<>(Map.of("amount", 6000));
        List<Object> lines = new ArrayList<>(List.of(line));
        Application application = Application.draft(UUID.randomUUID(), "tenant-a", "EXP-SNAPSHOT", "expense",
                1, "alice", "原申请", Map.of("lines", lines));
        application.submit(1);
        SubmissionRound snapshot = SubmissionRound.submitted(application, "first-instance", "alice", Instant.now());

        line.put("amount", 900);
        lines.add(Map.of("amount", 100));

        assertThat(snapshot.payload()).isEqualTo(Map.of("lines", List.of(Map.of("amount", 6000))));
        assertThatThrownBy(() -> snapshot.payload().clear()).isInstanceOf(UnsupportedOperationException.class);
        List<?> frozenLines = (List<?>) snapshot.payload().get("lines");
        assertThatThrownBy(frozenLines::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((Map<?, ?>) frozenLines.get(0)).clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
