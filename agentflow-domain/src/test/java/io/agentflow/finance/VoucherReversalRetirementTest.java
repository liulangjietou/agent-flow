package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 原冲销结束只消耗可证明的未执行状态和之后复核的原过账，不把未知或查无当成安全失败。
 * @author owlzhangfq@gmail.com
 */
class VoucherReversalRetirementTest {
    private static final Instant NOW = VoucherReversalCommandTest.NOW;
    private final VoucherReversalCommand command = VoucherReversalCommandTest.command(VoucherCommand.Kind.PAYMENT);
    @Test void neverSentRetirementStopsTheQueueAndOnlyReleasesItsFreshOriginal() {
        var value = queued(); var original = original(NOW.plusSeconds(1)); var at = NOW.plusSeconds(2);
        var decision = VoucherReversalRetirement.create(value, original, "other-finance", "ERP-PROOF", "结束未发送原冲销", at);
        assertThat(decision.basis()).isEqualTo(VoucherReversalOperation.RetirementBasis.NEVER_DISPATCHED);
        assertThat(decision.reversalVersion()).isEqualTo(1); assertThat(decision.stoppedVersion()).isEqualTo(2);
        assertThat(decision.originalVersion()).isEqualTo(original.version()); assertThat(decision.releasedVersion()).isEqualTo(original.version() + 1);
        var stopped = value.stopForRetirement(at); assertThat(stopped.status()).isEqualTo(VoucherReversalOperation.Status.VOIDED);
        assertThat(stopped.failure()).isEqualTo(VoucherReversalOperation.Failure.FINANCE_RETIRED); assertThat(stopped.input()).isEqualTo(value.input());
        assertThat(stopped.nextAttemptAt()).isNull(); assertThat(stopped.attempts()).isZero();
        var released = original.releaseReversal(command.id(), at); assertThat(released.usablePosted()).isTrue();
        assertThat(released.input()).isEqualTo(original.input()); assertThat(released.observation()).isEqualTo(original.observation());
        assertThat(original.usablePosted()).isFalse(); assertThatThrownBy(() -> released.releaseReversal(command.id(), at)).isInstanceOf(DomainException.class);
    }
    @Test void onlyDefinitivePrePostingFailuresCanBeRetiredAndTheirReceiptsDoNotChange() {
        for (var reason : VoucherReversalObservation.Rejection.values()) {
            var failed = queued().claim(NOW, Duration.ofSeconds(30)).complete(new FinanceResult.Success<>(new VoucherReversalObservation(command.id(), command.digest(),
                    VoucherReversalObservation.Status.FAILED, 1, NOW.plusSeconds(1), "ERP-REJECTED", null, reason)), NOW.plusSeconds(1));
            boolean safe = switch (reason) {
                case ACCOUNTING_PERIOD_CLOSED, LEGAL_ENTITY_UNAVAILABLE, ACCOUNT_UNAVAILABLE, AUTHORIZATION_REJECTED -> true;
                default -> false;
            };
            if (safe) {
                var record = VoucherReversalRetirement.create(failed, original(NOW.plusSeconds(2)), "finance", "ERP-PROOF", "结束明确失败", NOW.plusSeconds(3));
                assertThat(record.basis()).isEqualTo(VoucherReversalOperation.RetirementBasis.CONFIRMED_FAILED); assertThat(record.stoppedVersion()).isEqualTo(record.reversalVersion());
                assertThat(failed.stopForRetirement(NOW.plusSeconds(3))).isSameAs(failed);
            } else {
                assertThat(failed.retirementBasis()).isNull();
                assertThatThrownBy(() -> VoucherReversalRetirement.create(failed, original(NOW.plusSeconds(2)), "finance", "ERP-PROOF", "原件不确定", NOW.plusSeconds(3))).isInstanceOf(DomainException.class);
            }
        }
    }
    @Test void expiredAndVoidedWithoutAttemptsRemainSafeButEveryPossibleExternalEffectStaysHeld() {
        assertThat(queued().claim(command.expiresAt(), Duration.ofSeconds(30)).retirementBasis()).isEqualTo(VoucherReversalOperation.RetirementBasis.NEVER_DISPATCHED);
        assertThat(queued().voidBeforeSend(NOW).retirementBasis()).isEqualTo(VoucherReversalOperation.RetirementBasis.NEVER_DISPATCHED);
        var sending = queued().claim(NOW, Duration.ofSeconds(30)); var unknown = sending.unavailable(VoucherReversalOperation.Failure.CONNECTION, NOW.plusSeconds(1));
        var querying = unknown.claim(NOW.plusSeconds(6), Duration.ofSeconds(30));
        var absent = querying.complete(new FinanceResult.Success<>(new VoucherReversalObservation(command.id(), command.digest(), VoucherReversalObservation.Status.NOT_FOUND, 0, NOW.plusSeconds(7), null, null, null)), NOW.plusSeconds(7));
        var posted = sending.complete(new FinanceResult.Success<>(VoucherReversalCommandTest.posted(command, 1, NOW.plusSeconds(1), "reversal")), NOW.plusSeconds(1));
        var pending = sending.complete(new FinanceResult.Success<>(new VoucherReversalObservation(command.id(), command.digest(), VoucherReversalObservation.Status.PENDING, 2, NOW.plusSeconds(1), "ERP-PENDING", null, null)), NOW.plusSeconds(1));
        for (var value : new VoucherReversalOperation[]{sending, unknown, querying, absent, absent.retryNotFound(NOW.plusSeconds(8)), posted, pending}) {
            assertThat(value.retirementBasis()).isNull(); assertThatThrownBy(() -> value.stopForRetirement(NOW.plusSeconds(9))).isInstanceOf(DomainException.class);
        }
    }
    @Test void retirementNeedsIndependentActorSameOriginalAndFreshEvidenceAfterExecutionState() {
        var value = queued(); var original = original(NOW.plusSeconds(1));
        for (String actor : new String[]{command.source().command().employeeId(), command.source().command().payment().command().authorization().executedBy()}) {
            assertThatThrownBy(() -> VoucherReversalRetirement.create(value, original, actor, "ERP-PROOF", "不能自行结束", NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
        }
        assertThatThrownBy(() -> VoucherReversalRetirement.create(value.voidBeforeSend(NOW.plusSeconds(2)), original, "finance", "ERP-PROOF", "原件尚未重新查询", NOW.plusSeconds(3))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> VoucherReversalRetirement.create(value, original, "finance", "ERP-PROOF", "旧依据", NOW.plusSeconds(301))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> original.releaseReversal(UUID.randomUUID(), NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
        var reading = original.requestQuery(NOW.plusSeconds(2));
        assertThatThrownBy(() -> VoucherReversalRetirement.create(value, reading, "finance", "ERP-PROOF", "仍在复查", NOW.plusSeconds(3))).isInstanceOf(DomainException.class);
    }
    private VoucherReversalOperation queued() { return VoucherReversalOperation.queue(new VoucherReversalOperation.Input(3, command, "a".repeat(64)), NOW); }
    private VoucherOperation original(Instant observedAt) {
        var source = command.source().command(); var proof = VoucherReversalCommandTest.original(source, VoucherObservation.Status.POSTED, 1, observedAt);
        return new VoucherOperation(new VoucherOperation.Input(source, "a".repeat(64)), 5, VoucherOperation.Status.POSTED, 2, source.createdAt(), observedAt,
                null, null, proof, null, 1, null, command.id());
    }
}
