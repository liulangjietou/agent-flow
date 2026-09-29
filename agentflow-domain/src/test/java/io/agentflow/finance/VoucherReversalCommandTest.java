package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 原分录、独立财务、原件时效和明确会计期间共同约束真实冲销命令。
 * @author owlzhangfq@gmail.com
 */
class VoucherReversalCommandTest {
    static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    @ParameterizedTest @EnumSource(VoucherCommand.Kind.class)
    void reverseEveryOriginalLineWithoutRepricingOrRemapping(VoucherCommand.Kind kind) {
        var value = command(kind); var original = value.source().command();
        assertThat(value.lines()).hasSize(original.lines().size());
        for (int index = 0; index < value.lines().size(); index++) {
            var line = value.lines().get(index); var source = original.lines().get(index);
            assertThat(line.accountCode()).isEqualTo(original.mapping().account(source.account()));
            assertThat(line.side()).isNotEqualTo(source.side()); assertThat(line.amount()).isEqualTo(source.amount());
            assertThat(line.sourceLineNo()).isEqualTo(source.sourceLineNo()); assertThat(line.costCenter()).isEqualTo(source.costCenter());
            assertThat(line.projectCode()).isEqualTo(source.projectCode()); assertThat(line.advanceId()).isEqualTo(source.advanceId());
        }
        assertThat(value.digest()).matches("[a-f0-9]{64}").isEqualTo(value.digest());
        assertThat(value.toString()).doesNotContain("alice", "finance", "100.00", "proof-1", value.reason());
        assertThatThrownBy(() -> value.requireSendAt(value.expiresAt())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> value.requireSendAt(NOW.minusSeconds(1))).isInstanceOf(DomainException.class);
        value.requireSendAt(NOW);
    }
    @Test void independentFinanceFreshOriginalAndSameEntityCurrencyAndChosenPeriodAreMandatory() {
        var value = command(VoucherCommand.Kind.PAYMENT); var original = value.source().command();
        for (String actor : new String[] {original.employeeId(), original.payment().command().authorization().executedBy(), " "}) {
            assertThatThrownBy(() -> copy(value, actor, value.verifiedOriginal(), value.period(), value.expiresAt())).isInstanceOf(DomainException.class);
        }
        assertThatThrownBy(() -> copy(value, "finance", value.verifiedOriginal(), value.period(), NOW.plusSeconds(301))).isInstanceOf(DomainException.class);
        var stale = original(value.source().command(), VoucherObservation.Status.POSTED, 1, NOW.minusSeconds(301));
        assertThatThrownBy(() -> copy(value, "finance", stale, value.period(), value.expiresAt())).isInstanceOf(DomainException.class);
        var reversed = original(original, VoucherObservation.Status.REVERSED, 2, NOW);
        assertThatThrownBy(() -> copy(value, "finance", reversed, value.period(), value.expiresAt())).isInstanceOf(DomainException.class);
        for (var request : new AccountingPeriodPort.Request[] {
                new AccountingPeriodPort.Request(UUID.randomUUID(), "CNY", value.period().request().accountingDate()),
                new AccountingPeriodPort.Request(original.legalEntityId(), "USD", value.period().request().accountingDate()),
                new AccountingPeriodPort.Request(original.legalEntityId(), "CNY", original.accountingDate().minusDays(1))}) {
            var period = new AccountingPeriodPort.OpenPeriod(request, "new-period", "v2", request.accountingDate(), request.accountingDate(), NOW, NOW.plusSeconds(300));
            assertThatThrownBy(() -> copy(value, "finance", value.verifiedOriginal(), period, value.expiresAt())).isInstanceOf(DomainException.class);
        }
    }
    @Test void digestBindsAuthorizationOriginalRevisionDateAndTextWithoutDelimiterCollisions() {
        var value = command(VoucherCommand.Kind.EMPLOYEE_ADVANCE);
        assertThat(copy(value, "other-finance", value.verifiedOriginal(), value.period(), value.expiresAt()).digest()).isNotEqualTo(value.digest());
        var newer = original(value.source().command(), VoucherObservation.Status.POSTED, 2, NOW);
        assertThat(copy(value, "finance", newer, value.period(), value.expiresAt()).digest()).isNotEqualTo(value.digest());
        var first = new VoucherReversalCommand(value.id(), value.source(), value.verifiedOriginal(), value.period(), "finance", "a|b", "c", NOW, value.expiresAt());
        var second = new VoucherReversalCommand(value.id(), value.source(), value.verifiedOriginal(), value.period(), "finance", "a", "b|c", NOW, value.expiresAt());
        assertThat(first.digest()).isNotEqualTo(second.digest());
    }
    @Test void postedResultMustPreserveDatePeriodAndCompleteOriginalReverseProof() {
        var value = command(VoucherCommand.Kind.EMPLOYEE_ADVANCE); var posted = posted(value, 2, NOW.plusSeconds(1), "reverse");
        assertThat(posted.matches(value, false, NOW.plusSeconds(1))).isTrue();
        var wrongDate = value.period().request().accountingDate().plusDays(1); var proof = posted.posting(); var reverse = proof.reversal();
        var changed = new VoucherReversalPort.Posting(reverse.postingReference(), reverse.voucherReference(), reverse.periodReference(), wrongDate, reverse.postedAt(), reverse.lines());
        var receipt = new VoucherReversalPort.Receipt(proof.request(), proof.status(), proof.revision(), proof.observedAt(), proof.validUntil(), proof.current(), changed);
        assertThat(new VoucherReversalObservation(value.id(), value.digest(), VoucherReversalObservation.Status.POSTED, 2, posted.observedAt(), "accepted", receipt, null).matches(value, false, posted.observedAt())).isFalse();
        assertThat(posted.matches(value, false, NOW)).isFalse();
        assertThatThrownBy(() -> new VoucherReversalObservation(value.id(), value.digest(), VoucherReversalObservation.Status.POSTED, 2, NOW, "accepted", null, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new VoucherReversalObservation(value.id(), value.digest(), VoucherReversalObservation.Status.FAILED, 2, NOW, "accepted", null, null)).isInstanceOf(DomainException.class);
    }
    static VoucherReversalCommand command(VoucherCommand.Kind kind) {
        var original = VoucherReversalTest.command(kind); var accepted = original(original, VoucherObservation.Status.POSTED, 1, NOW.minusSeconds(30));
        var request = new AccountingPeriodPort.Request(original.legalEntityId(), "CNY", original.accountingDate().plusDays(1));
        var period = new AccountingPeriodPort.OpenPeriod(request, "reverse-period", "v2", request.accountingDate(), request.accountingDate().plusDays(1), NOW.minusSeconds(1), NOW.plusSeconds(300));
        return new VoucherReversalCommand(UUID.randomUUID(), new VoucherReversalPort.Request(original, accepted), original(original, VoucherObservation.Status.POSTED, 1, NOW),
                period, "finance", "proof-1", "核对原凭证后完整冲销", NOW, NOW.plusSeconds(300));
    }
    static VoucherObservation original(VoucherCommand value, VoucherObservation.Status status, long revision, Instant at) {
        return new VoucherObservation(value.id(), value.digest(), status, revision, at, "original-posting", "original-voucher", value.period().periodReference(),
                value.accountingDate(), value.totals().gross(), value.totals().gross(), value.createdAt(), null);
    }
    static VoucherReversalObservation posted(VoucherReversalCommand value, long revision, Instant at, String identity) {
        var lines = value.lines().stream().map(line -> new VoucherReversalPort.Line("entry-" + line.originalLineNo(), line.originalLineNo(), line.accountCode(), line.side(),
                line.amount(), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId())).toList();
        var posting = new VoucherReversalPort.Posting(identity + "-posting", identity + "-voucher", value.period().periodReference(), value.period().request().accountingDate(), NOW.plusSeconds(1), lines);
        var proof = new VoucherReversalPort.Receipt(value.source(), VoucherReversalPort.Status.VERIFIED, revision, at, at.plusSeconds(300), original(value.source().command(), VoucherObservation.Status.REVERSED, 2, at), posting);
        return new VoucherReversalObservation(value.id(), value.digest(), VoucherReversalObservation.Status.POSTED, revision, at, "accepted", proof, null);
    }
    private static VoucherReversalCommand copy(VoucherReversalCommand value, String actor, VoucherObservation original, AccountingPeriodPort.OpenPeriod period, Instant expiry) {
        return new VoucherReversalCommand(value.id(), value.source(), original, period, actor, value.evidenceReference(), value.reason(), NOW, expiry);
    }
}
