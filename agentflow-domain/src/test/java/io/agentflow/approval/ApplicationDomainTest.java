package io.agentflow.approval;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author owlzhangfq@gmail.com
 */
class ApplicationDomainTest {
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
}
