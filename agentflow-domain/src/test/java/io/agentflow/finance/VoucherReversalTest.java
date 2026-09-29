package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import static org.assertj.core.api.Assertions.*;

/**
 * 冲销证明必须逐行反转真实原凭证，读取、人工登记和业务资源调整严格分开。
 * @author owlzhangfq@gmail.com
 */
class VoucherReversalTest {
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

    @ParameterizedTest @EnumSource(VoucherCommand.Kind.class)
    void eachVoucherKindRequiresASeparateFullReversePosting(VoucherCommand.Kind kind) {
        var request = request(kind); var original = request.command(); var receipt = verified(request, posting(request));
        assertThat(receipt.matches(request, NOW)).isTrue(); assertThat(receipt.matches(request, NOW.plusSeconds(300))).isFalse();
        assertThat(receipt.matchesCurrent(receipt.current())).isTrue(); assertThat(receipt.matchesCurrent(request.original())).isFalse();
        var reversed = new ArrayList<>(receipt.reversal().lines()); java.util.Collections.reverse(reversed);
        assertThat(verified(request, posting(request, reversed))).isEqualTo(receipt);
        var decision = new VoucherReversalRecord(UUID.randomUUID(), original.tenantId(), UUID.randomUUID(), 3, receipt, "finance", NOW, "proof", "核实原凭证及完整反向分录");
        assertThat(decision.operationId()).isEqualTo(original.id()); assertThat(request.command()).isEqualTo(original);
        assertThat(receipt.toString()).doesNotContain("alice", "1122", "100.00");
    }

    @Test void balancedButDifferentAccountsAmountsDirectionsAndDimensionsFail() {
        var request = request(VoucherCommand.Kind.EXPENSE_ACCRUAL); var good = posting(request); var first = good.lines().get(0);
        List<UnaryOperator<VoucherReversalPort.Line>> changes = List.of(
                line -> line(line, 2, line.accountCode(), line.side(), line.amount(), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId()),
                line -> line(line, line.originalLineNo(), "wrong-account", line.side(), line.amount(), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId()),
                line -> line(line, line.originalLineNo(), line.accountCode(), VoucherCommand.Side.DEBIT, line.amount(), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId()),
                line -> line(line, line.originalLineNo(), line.accountCode(), line.side(), money("93"), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId()),
                line -> line(line, line.originalLineNo(), line.accountCode(), line.side(), new Money(line.amount().value(), "USD"), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId()),
                line -> line(line, line.originalLineNo(), line.accountCode(), line.side(), line.amount(), 2, line.costCenter(), line.projectCode(), line.advanceId()),
                line -> line(line, line.originalLineNo(), line.accountCode(), line.side(), line.amount(), line.sourceLineNo(), "other-cost", line.projectCode(), line.advanceId()),
                line -> line(line, line.originalLineNo(), line.accountCode(), line.side(), line.amount(), line.sourceLineNo(), line.costCenter(), "other-project", line.advanceId()),
                line -> line(line, line.originalLineNo(), line.accountCode(), line.side(), line.amount(), line.sourceLineNo(), line.costCenter(), line.projectCode(), UUID.randomUUID()));
        for (var change : changes) {
            var lines = new ArrayList<>(good.lines()); lines.set(0, change.apply(first));
            assertThatThrownBy(() -> verified(request, posting(request, lines))).isInstanceOf(DomainException.class);
        }
        assertThatThrownBy(() -> verified(request, posting(request, good.lines().subList(0, 3)))).isInstanceOf(DomainException.class);
        var duplicated = new ArrayList<>(good.lines()); var second = duplicated.get(1);
        duplicated.set(1, new VoucherReversalPort.Line(first.entryReference(), second.originalLineNo(), second.accountCode(), second.side(), second.amount(), second.sourceLineNo(), second.costCenter(), second.projectCode(), second.advanceId()));
        assertThatThrownBy(() -> posting(request, duplicated)).isInstanceOf(DomainException.class);
        var balancedWrong = good.lines().stream().map(line -> line(line, line.originalLineNo(), line.accountCode(), line.side(),
                new Money(line.amount().value().multiply(new BigDecimal("0.5")), "CNY"), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId())).toList();
        assertThatThrownBy(() -> verified(request, posting(request, balancedWrong))).isInstanceOf(DomainException.class);
    }

    @Test void originalVoucherCannotReverseItselfAndFutureOrEarlierPostingIsInvalid() {
        var request = request(VoucherCommand.Kind.EMPLOYEE_ADVANCE); var good = posting(request); var original = request.original();
        List<VoucherReversalPort.Posting> invalid = List.of(
                new VoucherReversalPort.Posting(original.postingReference(), good.voucherReference(), good.periodReference(), good.accountingDate(), good.postedAt(), good.lines()),
                new VoucherReversalPort.Posting(good.postingReference(), original.voucherReference(), good.periodReference(), good.accountingDate(), good.postedAt(), good.lines()),
                new VoucherReversalPort.Posting(good.postingReference(), good.voucherReference(), good.periodReference(), request.command().accountingDate().minusDays(1), good.postedAt(), good.lines()),
                new VoucherReversalPort.Posting(good.postingReference(), good.voucherReference(), good.periodReference(), good.accountingDate(), original.postedAt().minusSeconds(1), good.lines()),
                new VoucherReversalPort.Posting(good.postingReference(), good.voucherReference(), good.periodReference(), good.accountingDate(), NOW.plusSeconds(1), good.lines()));
        invalid.forEach(posting -> assertThatThrownBy(() -> verified(request, posting)).isInstanceOf(DomainException.class));
        var current = observation(request.command(), VoucherObservation.Status.REVERSED, NOW);
        assertThatThrownBy(() -> new VoucherReversalPort.Receipt(request, VoucherReversalPort.Status.VERIFIED, 1, NOW, NOW.plusSeconds(301), current, good)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new VoucherReversalPort.Receipt(request, VoucherReversalPort.Status.VERIFIED, 1, NOW, NOW.plusSeconds(300), original, good)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new VoucherReversalPort.Receipt(request, VoucherReversalPort.Status.UNRESOLVED, 1, NOW, NOW.plusSeconds(300), current, good)).isInstanceOf(DomainException.class);
    }

    @Test void pendingQuerySingleUseAndEvidenceChangeDoNotCreateAnAdjustment() {
        var request = request(VoucherCommand.Kind.PAYMENT); var input = new VoucherReversalCheck.Input(UUID.randomUUID(), request.command().tenantId(), "a".repeat(64), 3, request, "finance", NOW);
        var queued = VoucherReversalCheck.queue(input); var claimed = queued.claim(NOW, Duration.ofSeconds(30));
        var receipt = verified(request, posting(request)); var checked = claimed.complete(new FinanceResult.Success<>(receipt), NOW.plusSeconds(1));
        assertThat(checked.status()).isEqualTo(VoucherReversalCheck.Status.CHECKED); assertThat(checked.recordId()).isNull();
        var decision = new VoucherReversalRecord(UUID.randomUUID(), input.tenantId(), input.id(), 3, receipt, "finance", NOW.plusSeconds(2), "proof", "确认独立凭证");
        var recorded = checked.record(decision, decision.recordedAt()); assertThat(recorded.status()).isEqualTo(VoucherReversalCheck.Status.RECORDED);
        assertThat(recorded.input()).isEqualTo(input); assertThatThrownBy(() -> recorded.record(decision, decision.recordedAt())).isInstanceOf(DomainException.class);
        var otherActor = new VoucherReversalRecord(UUID.randomUUID(), input.tenantId(), input.id(), 3, receipt, "other-finance", decision.recordedAt(), "proof", "独立但非查询人");
        assertThatThrownBy(() -> checked.record(otherActor, otherActor.recordedAt())).isInstanceOf(DomainException.class);
        for (String actor : List.of("alice", "cashier")) assertThatThrownBy(() -> new VoucherReversalRecord(UUID.randomUUID(), input.tenantId(), input.id(), 3, receipt, actor, NOW, "proof", "职责分离")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new VoucherReversalRecord(UUID.randomUUID(), input.tenantId(), input.id(), 3, receipt, "finance", NOW.plusSeconds(300), "proof", "旧证据")).isInstanceOf(DomainException.class);
        assertThat(receipt.preservesReversal(receipt)).isTrue();
        var different = new VoucherReversalPort.Posting("other-posting", "other-voucher", receipt.reversal().periodReference(), receipt.reversal().accountingDate(), receipt.reversal().postedAt(), receipt.reversal().lines());
        assertThat(verified(request, different).preservesReversal(receipt)).isFalse();
    }

    @Test void timeoutUnresolvedAndInvalidatedSourcesStayNonRecordable() {
        var request = request(VoucherCommand.Kind.EMPLOYEE_ADVANCE); var queued = VoucherReversalCheck.queue(new VoucherReversalCheck.Input(UUID.randomUUID(), request.command().tenantId(), "a".repeat(64), 3, request, "finance", NOW));
        var running = queued.claim(NOW, Duration.ofSeconds(30)); var proof = verified(request, posting(request));
        assertThat(running.complete(new FinanceResult.Success<>(proof), NOW.plusSeconds(30)).issue()).isEqualTo(VoucherReversalCheck.Issue.TIMEOUT);
        assertThat(queued.voidSource(NOW).status()).isEqualTo(VoucherReversalCheck.Status.VOIDED);
        var unresolved = new VoucherReversalPort.Receipt(request, VoucherReversalPort.Status.UNRESOLVED, 1, NOW, NOW.plusSeconds(300), null, null);
        assertThat(running.complete(new FinanceResult.Success<>(unresolved), NOW).usable(NOW)).isFalse();
        assertThat(running.complete(new FinanceResult.Unavailable<>(FinanceResult.Failure.NOT_CONFIGURED), NOW).issue()).isEqualTo(VoucherReversalCheck.Issue.NOT_CONFIGURED);
        assertThat(proof.matches(request, NOW.minusSeconds(1))).isFalse();
    }

    private static VoucherReversalPort.Line line(VoucherReversalPort.Line line, int originalNo, String account, VoucherCommand.Side side, Money amount, int source, String center, String project, UUID advance) {
        return new VoucherReversalPort.Line(line.entryReference(), originalNo, account, side, amount, source, center, project, advance);
    }
    private static VoucherReversalPort.Request request(VoucherCommand.Kind kind) {
        var original = command(kind); return new VoucherReversalPort.Request(original, observation(original, VoucherObservation.Status.POSTED, NOW.minusSeconds(30)));
    }
    private static VoucherReversalPort.Receipt verified(VoucherReversalPort.Request request, VoucherReversalPort.Posting posting) {
        return new VoucherReversalPort.Receipt(request, VoucherReversalPort.Status.VERIFIED, 1, NOW, NOW.plusSeconds(300), observation(request.command(), VoucherObservation.Status.REVERSED, NOW), posting);
    }
    private static VoucherReversalPort.Posting posting(VoucherReversalPort.Request request) {
        return posting(request, request.command().lines().stream().map(line -> new VoucherReversalPort.Line("reversal-entry-" + line.lineNo(), line.lineNo(), request.command().mapping().account(line.account()),
                line.side() == VoucherCommand.Side.DEBIT ? VoucherCommand.Side.CREDIT : VoucherCommand.Side.DEBIT, line.amount(), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId())).toList());
    }
    private static VoucherReversalPort.Posting posting(VoucherReversalPort.Request request, List<VoucherReversalPort.Line> lines) {
        return new VoucherReversalPort.Posting("reversal-posting", "reversal-voucher", "2026-09", request.command().accountingDate().plusDays(1), NOW.minusSeconds(10), lines);
    }
    private static VoucherObservation observation(VoucherCommand command, VoucherObservation.Status status, Instant observedAt) {
        return new VoucherObservation(command.id(), command.digest(), status, status == VoucherObservation.Status.POSTED ? 1L : 2L, observedAt,
                "original-posting", "original-voucher", command.period().periodReference(), command.accountingDate(), command.totals().gross(), command.totals().gross(), command.createdAt(), null);
    }
    private static VoucherCommand command(VoucherCommand.Kind kind) {
        var base = VoucherCommandTest.advanceCommand(); if (kind == VoucherCommand.Kind.EMPLOYEE_ADVANCE) return base;
        List<VoucherCommand.Line> lines; VoucherCommand.PaymentProof proof = null;
        if (kind == VoucherCommand.Kind.EXPENSE_ACCRUAL) {
            lines = List.of(new VoucherCommand.Line(1, new AccountMappingPort.Key(AccountMappingPort.Role.EXPENSE, "OFFICE"), VoucherCommand.Side.DEBIT, money("94"), 1, "IT", "PROJECT", null),
                    new VoucherCommand.Line(2, new AccountMappingPort.Key(AccountMappingPort.Role.DEDUCTIBLE_TAX, ""), VoucherCommand.Side.DEBIT, money("6"), 1, "IT", "PROJECT", null),
                    new VoucherCommand.Line(3, new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_RECEIVABLE, ""), VoucherCommand.Side.CREDIT, money("20"), 0, null, null, UUID.randomUUID()),
                    new VoucherCommand.Line(4, new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_PAYABLE, ""), VoucherCommand.Side.CREDIT, money("80"), 0, null, null, null));
        } else {
            var payment = new PaymentCommand(UUID.randomUUID(), base.tenantId(), PaymentCommand.Purpose.EMPLOYEE_ADVANCE,
                    new PaymentCommand.Binding(base.binding().businessId(), base.binding().applicationId(), base.binding().roundNo(), base.binding().applicationVersion(), base.binding().businessVersion()),
                    money("100"), "bank", new EmployeeAccountSnapshot(base.legalEntityId(), base.employeeId(), "account", "****1234", "a".repeat(64), "v1"), "original-accrual", new PaymentCommand.Authorization("maker", "cashier", base.createdAt().minusSeconds(30), NOW.plusSeconds(300)));
            proof = new VoucherCommand.PaymentProof(payment, new PaymentObservation(payment.id(), payment.digest(), PaymentObservation.Status.SUCCEEDED, 1L, base.createdAt(), "bank-payment", money("100"), payment.payee().accountDigest(), base.createdAt(), "bank-receipt", null));
            lines = List.of(new VoucherCommand.Line(1, new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_PAYABLE, ""), VoucherCommand.Side.DEBIT, money("100"), 0, null, null, null),
                    new VoucherCommand.Line(2, new AccountMappingPort.Key(AccountMappingPort.Role.BANK, "bank"), VoucherCommand.Side.CREDIT, money("100"), 0, null, null, null));
        }
        var mappingRequest = new AccountMappingPort.Request(base.legalEntityId(), "CNY", lines.stream().map(VoucherCommand.Line::account).toList());
        var mapping = new AccountMappingPort.Mapping(mappingRequest, "map-v1", base.mapping().observedAt(), base.mapping().validUntil(), mappingRequest.keys().stream().map(key -> new AccountMappingPort.Entry(key, "account-" + key.role())).toList());
        return new VoucherCommand(base.id(), base.tenantId(), kind, base.binding(), base.legalEntityId(), base.employeeId(), base.accountingDate(),
                new VoucherCommand.Totals(money("100"), money(kind == VoucherCommand.Kind.EXPENSE_ACCRUAL ? "6" : "0"), money(kind == VoucherCommand.Kind.EXPENSE_ACCRUAL ? "20" : "0")), base.period(), mapping, lines, proof, base.createdAt(), base.expiresAt());
    }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
}
