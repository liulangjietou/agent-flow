package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import static org.assertj.core.api.Assertions.*;

/**
 * 只读准备只生成原财务可采纳的短期证据，不能自动转为实际发送。
 * @author owlzhangfq@gmail.com
 */
class VoucherReversalPreparationTest {
    private static final Instant NOW = VoucherReversalCommandTest.NOW;
    private final VoucherReversalCommand command = VoucherReversalCommandTest.command(VoucherCommand.Kind.EMPLOYEE_ADVANCE);
    @Test void prepareAndAuthorizePreserveChosenDateMaterialAndSourceWithoutRenewingEvidence() {
        var ready = running().ready(command.verifiedOriginal(), command.period(), NOW);
        assertThat(ready.status()).isEqualTo(VoucherReversalPreparation.Status.READY); assertThat(ready.command()).isEqualTo(command);
        var accepted = ready.authorize(NOW.plusSeconds(1)); assertThat(accepted.command()).isEqualTo(command);
        assertThat(accepted.status()).isEqualTo(VoucherReversalPreparation.Status.AUTHORIZED);
        assertThatThrownBy(() -> accepted.authorize(NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> ready.authorize(command.expiresAt())).isInstanceOf(DomainException.class);
    }
    @Test void timedOutOrInvalidatedReadCannotBecomeReadyAndClosedPeriodHasNoFallback() {
        var running = running(); var expired = running.ready(command.verifiedOriginal(), command.period(), NOW.plusSeconds(30));
        assertThat(expired.status()).isEqualTo(VoucherReversalPreparation.Status.UNAVAILABLE); assertThat(expired.command()).isNull();
        assertThat(running.fail("ACCOUNTING_PERIOD_CLOSED", NOW).command()).isNull();
        var voided = running.voidSource(NOW); assertThat(voided.status()).isEqualTo(VoucherReversalPreparation.Status.VOIDED);
        assertThatThrownBy(() -> voided.ready(command.verifiedOriginal(), command.period(), NOW)).isInstanceOf(DomainException.class);
    }
    @Test void mismatchedChosenDateCannotBeRestoredAsReadyEvidence() {
        var input = input(); var other = new VoucherReversalPreparation.Input(input.id(), input.originalVersion(), input.operationVersion(), input.applicationVersion(), input.businessVersion(),
                input.source(), input.targetDigest(), input.accountingDate().plusDays(1), input.requestedBy(), input.evidenceReference(), input.reason(), input.requestedAt());
        assertThatThrownBy(() -> new VoucherReversalPreparation(other, 3, VoucherReversalPreparation.Status.READY, NOW, null, command, null)).isInstanceOf(DomainException.class);
    }
    @Test void derivedDeadlineStaysOnTheMicrosecondClockAndNeverOutlivesNanosecondEvidence() {
        // Linux 时钟与 ERP 回执都可能带纳秒，而各服务把当前时刻截到微秒后再与截止时刻比较。
        var precise = VoucherReversalCommandTest.original(command.source().command(), VoucherObservation.Status.POSTED, 1, NOW.minusSeconds(1).plusNanos(789));
        Instant evidenceLimit = precise.observedAt().plus(VoucherReversalPort.MAX_EVIDENCE_AGE);
        Instant clockAtLimit = evidenceLimit.truncatedTo(ChronoUnit.MICROS);
        var ready = running().ready(precise, command.period(), NOW);
        assertThat(ready.command().verifiedOriginal().observedAt()).isEqualTo(precise.observedAt());
        assertThat(ready.command().expiresAt()).isEqualTo(clockAtLimit);
        // 证据到期的那一刻，截断后的时钟不能仍然允许授权或首次发送。
        assertThatThrownBy(() -> ready.authorize(clockAtLimit)).isInstanceOf(DomainException.class);
        var queued = VoucherReversalOperation.queue(new VoucherReversalOperation.Input(3, ready.command(), "a".repeat(64)), NOW);
        assertThat(queued.claim(clockAtLimit, Duration.ofSeconds(30)).status()).isEqualTo(VoucherReversalOperation.Status.EXPIRED);
    }
    private VoucherReversalPreparation running() { return VoucherReversalPreparation.queue(input()).claim(NOW, Duration.ofSeconds(30)); }
    private VoucherReversalPreparation.Input input() {
        return new VoucherReversalPreparation.Input(command.id(), 3, 3, 5, 3, command.source(), "a".repeat(64), command.period().request().accountingDate(),
                command.authorizedBy(), command.evidenceReference(), command.reason(), NOW);
    }
}
