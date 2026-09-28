package io.agentflow.expense;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 租约右边界、发票版本绑定和不可回写终态是任务的核心风险边界。
 * @author owlzhangfq@gmail.com
 */
class InvoiceVerificationJobTest {
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    @Test
    void resultRequiresExactNextInvoiceVersionAndIsTerminal() {
        var running = queued().start(NOW, NOW.plusSeconds(30));
        assertThatThrownBy(() -> running.succeed(3, NOW)).isInstanceOf(DomainException.class);
        var completed = running.succeed(2, NOW.plusSeconds(1));
        assertThat(completed.version()).isEqualTo(3); assertThat(completed.input()).isEqualTo(running.input());
        assertThat(completed.active()).isFalse();
        assertThatThrownBy(() -> completed.unavailable(InvoiceVerificationJob.Failure.TIMEOUT, NOW.plusSeconds(60))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> completed.start(NOW, NOW.plusSeconds(30))).isInstanceOf(DomainException.class);
    }
    @Test
    void exactLeaseBoundaryCannotBeReportedAsSuccessOrBusinessRejection() {
        var running = queued().start(NOW, NOW.plusSeconds(30));
        assertThat(running.expired(NOW.plusSeconds(30))).isTrue();
        assertThatThrownBy(() -> running.succeed(2, NOW.plusSeconds(30))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> running.reject(InvoiceVerificationJob.Rejection.INVOICE_INVALID, 2L, NOW.plusSeconds(30))).isInstanceOf(DomainException.class);
        assertThat(running.unavailable(InvoiceVerificationJob.Failure.CONNECTION, NOW.plusSeconds(30)).failure()).isEqualTo(InvoiceVerificationJob.Failure.TIMEOUT);
    }
    @Test
    void legalEntityRejectionCannotFabricateAnInvalidInvoiceVersion() {
        var running = queued().start(NOW, NOW.plusSeconds(30));
        assertThat(running.reject(InvoiceVerificationJob.Rejection.LEGAL_ENTITY_UNAVAILABLE, null, NOW).resultingInvoiceVersion()).isNull();
        assertThatThrownBy(() -> running.reject(InvoiceVerificationJob.Rejection.INVOICE_INVALID, null, NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> running.reject(InvoiceVerificationJob.Rejection.LEGAL_ENTITY_UNAVAILABLE, 2L, NOW)).isInstanceOf(DomainException.class);
    }
    @Test
    void corruptRecoveredStateAndOutOfOrderTimesAreRejected() {
        var queued = queued();
        assertThatThrownBy(() -> queued.start(NOW.minusSeconds(1), NOW.plusSeconds(10))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new InvoiceVerificationJob(queued.input(), 1, InvoiceVerificationJob.Status.SUCCEEDED,
                NOW, null, null, NOW, 2L, null, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new InvoiceVerificationJob(queued.input(), 1, InvoiceVerificationJob.Status.QUEUED,
                NOW, null, null, null, null, null, InvoiceVerificationJob.Failure.TIMEOUT)).isInstanceOf(DomainException.class);
    }
    private InvoiceVerificationJob queued() {
        return InvoiceVerificationJob.queue(new InvoiceVerificationJob.Input(UUID.randomUUID(), "tenant", UUID.randomUUID(), "alice", 1,
                UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), "b".repeat(64)), NOW);
    }
}
