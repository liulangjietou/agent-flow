package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.expense.InvoiceOriginal;
import io.agentflow.expense.InvoiceVerificationPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import static io.agentflow.agent.InvoiceExtractionSuggestion.Field.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 验证票据建议的完整来源、精确候选值、本人确认及不可重发的运行边界。
 * @author owlzhangfq@gmail.com
 */
class InvoiceExtractionRunTest {
    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");
    private static final InvoiceExtractionInput INPUT = new InvoiceExtractionInput(UUID.randomUUID(), UUID.randomUUID(),
            "a".repeat(64), InvoiceOriginal.Format.PDF, 1024, 2);

    @Test
    void ownerCanConfirmAnEditedSubsetWithoutReplacingModelValues() {
        var evidence = new ArrayList<>(List.of(evidence(1)));
        var proposals = new ArrayList<>(List.of(new InvoiceExtractionSuggestion.Proposal(INVOICE_NUMBER, "00001234", InvoiceExtractionSuggestion.Confidence.HIGH, evidence),
                proposal(GROSS_AMOUNT, "9007199254740993.02")));
        var suggestion = suggestion(proposals);
        proposals.clear(); evidence.clear();
        var run = started(); run.complete(2, suggestion, NOW.plusSeconds(2));
        var selected = new ArrayList<>(List.of(new InvoiceExtractionSuggestion.Selection(INVOICE_NUMBER, "00001235")));
        run.confirm(3, "alice", INPUT, selected, "按原件更正末位", NOW.plusSeconds(3)); selected.clear();

        assertThat(run.state().status()).isEqualTo(InvoiceExtractionRun.Status.CONFIRMED);
        assertThat(run.state().suggestion().proposals()).extracting(InvoiceExtractionSuggestion.Proposal::value)
                .containsExactly("00001234", "9007199254740993.02");
        assertThat(run.state().review().selected()).containsExactly(new InvoiceExtractionSuggestion.Selection(INVOICE_NUMBER, "00001235"));
        assertThat(run.state().suggestion().proposals().get(0).confidence()).isEqualTo(InvoiceExtractionSuggestion.Confidence.HIGH);
        assertThat(run.state().suggestion().proposals().get(0).evidence()).containsExactly(evidence(1));
        assertThatThrownBy(() -> run.state().review().selected().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertRestores(run);
    }

    @Test
    void unknownReviewerDuplicateSelectionAndUnproposedFieldsCannotConfirm() {
        var run = completed();
        var selected = List.of(new InvoiceExtractionSuggestion.Selection(INVOICE_NUMBER, "00001234"));
        assertCode(() -> run.confirm(3, "admin", INPUT, selected, null, NOW.plusSeconds(3)), "INVALID_AGENT_REVIEW");
        assertCode(() -> run.confirm(3, "alice", INPUT, List.of(selected.get(0), selected.get(0)), null, NOW.plusSeconds(3)), "INVALID_AGENT_REVIEW");
        assertCode(() -> run.confirm(3, "alice", INPUT, List.of(new InvoiceExtractionSuggestion.Selection(TAX_AMOUNT, "1.00")), null, NOW.plusSeconds(3)), "INVALID_AGENT_REVIEW");
        assertCode(() -> run.confirm(3, "alice", INPUT, List.of(), null, NOW.plusSeconds(3)), "INVALID_AGENT_REVIEW");
        assertThat(run.state().version()).isEqualTo(3);
        assertThat(run.state().review()).isNull();
    }

    @Test
    void changedOriginalCannotBeConfirmedButOwnerMayDismiss() {
        var run = completed();
        var changed = new InvoiceExtractionInput(INPUT.invoiceId(), INPUT.originalId(), "b".repeat(64), INPUT.format(), INPUT.originalBytes(), INPUT.pageCount());
        assertCode(() -> run.confirm(3, "alice", changed, List.of(new InvoiceExtractionSuggestion.Selection(INVOICE_NUMBER, "0123")), null, NOW.plusSeconds(3)), "AGENT_INPUT_CHANGED");
        assertCode(() -> run.dismiss(3, "admin", null, NOW.plusSeconds(3)), "INVALID_AGENT_REVIEW");
        run.dismiss(3, "alice", "原件依据不再匹配", NOW.plusSeconds(3));
        assertThat(run.state().status()).isEqualTo(InvoiceExtractionRun.Status.DISMISSED);
        assertThat(run.state().review().selected()).isNull(); assertRestores(run);
    }

    @Test
    void foreignDigestOriginalOrNonexistentPageCannotComplete() {
        var references = List.of(new InvoiceExtractionSuggestion.Evidence(UUID.randomUUID(), INPUT.originalDigest(), 1, "票面"),
                new InvoiceExtractionSuggestion.Evidence(INPUT.originalId(), "b".repeat(64), 1, "票面"), evidence(3));
        for (var reference : references) {
            var run = started();
            assertCode(() -> run.complete(2, suggestion(List.of(new InvoiceExtractionSuggestion.Proposal(INVOICE_NUMBER, "0123", InvoiceExtractionSuggestion.Confidence.HIGH, List.of(reference)))), NOW.plusSeconds(2)), "INVALID_AGENT_OUTPUT");
            assertThat(run.state().status()).isEqualTo(InvoiceExtractionRun.Status.RUNNING);
            assertThat(run.state().suggestion()).isNull();
        }
        var run = started();
        assertCode(() -> run.complete(2, new InvoiceExtractionSuggestion(InvoiceExtractionSuggestion.Method.MODEL, "fixture", "v1", "other-prompt", List.of(proposal(INVOICE_NUMBER, "0123"))), NOW.plusSeconds(2)), "INVALID_AGENT_OUTPUT");
    }

    @Test
    void failureAndReviewAreTerminalEvenWhenTheOriginalCallReturnsLate() {
        var failed = started(); failed.fail(2, InvoiceExtractionRun.Failure.EXECUTION_TIMEOUT, NOW.plusSeconds(2)); assertRestores(failed);
        assertCode(() -> failed.start(3, NOW.plusSeconds(3)), "AGENT_RUN_STATE_CONFLICT");
        assertCode(() -> failed.complete(3, suggestion(List.of(proposal(INVOICE_NUMBER, "0123"))), NOW.plusSeconds(3)), "AGENT_RUN_STATE_CONFLICT");
        var confirmed = completed(); confirmed.confirm(3, "alice", INPUT, List.of(new InvoiceExtractionSuggestion.Selection(INVOICE_NUMBER, "0123")), null, NOW.plusSeconds(3));
        assertCode(() -> confirmed.dismiss(4, "alice", null, NOW.plusSeconds(4)), "AGENT_RUN_STATE_CONFLICT");
    }

    @Test
    void versionAndEventTimeMustMoveAlongTheOriginalRun() {
        var run = queued();
        assertCode(() -> run.start(2, NOW), "CONCURRENCY_CONFLICT");
        assertCode(() -> run.start(1, NOW.minusSeconds(1)), "INVALID_AGENT_TIME");
        run.start(1, NOW.plusSeconds(1));
        var suggestion = suggestion(List.of(proposal(INVOICE_NUMBER, "0123")));
        assertCode(() -> run.complete(2, suggestion, NOW), "INVALID_AGENT_TIME");
        run.complete(2, suggestion, NOW.plusSeconds(2));
        assertCode(() -> run.dismiss(3, "alice", null, NOW.plusSeconds(1)), "INVALID_AGENT_TIME");
        assertCode(() -> run.dismiss(3, "alice", "x".repeat(AssistRun.MAX_REVIEW_COMMENT_LENGTH + 1), NOW.plusSeconds(3)), "INVALID_AGENT_REVIEW");
        assertThat(run.state().status()).isEqualTo(InvoiceExtractionRun.Status.COMPLETED);
    }

    @Test
    void restoreRejectsForgedStatusVersionAndReviewIdentity() {
        var run = completed(); var s = run.state();
        assertThatThrownBy(() -> InvoiceExtractionRun.restore(run.context(), new InvoiceExtractionRun.State(
                InvoiceExtractionRun.Status.CONFIRMED, s.version(), s.startedAt(), s.completedAt(), s.suggestion(), null, null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InvoiceExtractionRun.restore(run.context(), new InvoiceExtractionRun.State(
                s.status(), 100, s.startedAt(), s.completedAt(), s.suggestion(), null, null)))
                .isInstanceOf(IllegalStateException.class);
        assertCode(() -> InvoiceExtractionRun.restore(run.context(), new InvoiceExtractionRun.State(
                InvoiceExtractionRun.Status.DISMISSED, 4, s.startedAt(), s.completedAt(), s.suggestion(), null,
                new InvoiceExtractionRun.Review("admin", NOW.plusSeconds(3), null, null))), "INVALID_AGENT_REVIEW");
        assertRestores(queued()); assertRestores(started()); assertRestores(run);
    }

    @Test
    void sourceKeepsAllSupportedOriginalFormatsAndBoundsActualPagesAndBytes() {
        for (var format : InvoiceOriginal.Format.values()) {
            var source = new InvoiceExtractionInput(INPUT.invoiceId(), INPUT.originalId(), INPUT.originalDigest(), format, 1, 1);
            assertThat(source.format()).isEqualTo(format);
        }
        assertCode(() -> new InvoiceExtractionInput(INPUT.invoiceId(), INPUT.originalId(), INPUT.originalDigest(), InvoiceOriginal.Format.PDF, 0, 1), "INVALID_AGENT_INPUT");
        assertCode(() -> new InvoiceExtractionInput(INPUT.invoiceId(), INPUT.originalId(), INPUT.originalDigest(), InvoiceOriginal.Format.PDF,
                InvoiceVerificationPort.Request.MAX_ORIGINAL_BYTES + 1L, 1), "INVALID_AGENT_INPUT");
        assertCode(() -> new InvoiceExtractionInput(INPUT.invoiceId(), INPUT.originalId(), INPUT.originalDigest(), InvoiceOriginal.Format.PDF, 1, InvoiceExtractionInput.MAX_PAGES + 1), "INVALID_AGENT_INPUT");
        assertCode(() -> new InvoiceExtractionInput(INPUT.invoiceId(), INPUT.originalId(), INPUT.originalDigest(), InvoiceOriginal.Format.XML, 1, 2), "INVALID_AGENT_INPUT");
    }

    @Test
    void candidateNumbersPreserveLeadingZerosPrecisionAndRedInvoiceSigns() {
        var result = suggestion(List.of(proposal(INVOICE_NUMBER, "00001234"), proposal(GROSS_AMOUNT, "-123.45"),
                proposal(NET_AMOUNT, "100.00"), proposal(TAX_AMOUNT, "10.00"), proposal(CURRENCY, "CNY"), proposal(ISSUE_DATE, "2024-02-29")));
        result.requireMatches(INPUT);
        assertThat(result.proposals()).extracting(InvoiceExtractionSuggestion.Proposal::value)
                .contains("00001234", "-123.45", "100.00", "10.00");
        // 候选值之间不作财务算术纠正，原件可能是红字票、异常票或模型误读，仍须核对。
        assertThat(new InvoiceExtractionSuggestion.Selection(GROSS_AMOUNT, "9007199254740993.02").value()).isEqualTo("9007199254740993.02");
    }

    @Test
    void invalidDatesCurrenciesAndNonDecimalAmountsCannotBecomeCandidatesOrReviews() {
        for (String value : List.of("NaN", "1e3", "01.00", "1.001", "10000000000000000", "1,000.00", " 1.00")) {
            assertCode(() -> proposal(GROSS_AMOUNT, value), "INVALID_AGENT_OUTPUT");
            assertCode(() -> new InvoiceExtractionSuggestion.Selection(GROSS_AMOUNT, value), "INVALID_AGENT_OUTPUT");
        }
        assertCode(() -> proposal(ISSUE_DATE, "2025-02-29"), "INVALID_AGENT_OUTPUT");
        assertCode(() -> proposal(CURRENCY, "XYZ"), "INVALID_AGENT_OUTPUT");
        assertCode(() -> proposal(INVOICE_NUMBER, "0123\n"), "INVALID_AGENT_OUTPUT");
    }

    @Test
    void duplicateAndUnboundedEvidenceAreRejected() {
        var p = proposal(INVOICE_NUMBER, "0123");
        assertCode(() -> suggestion(List.of(p, p)), "INVALID_AGENT_OUTPUT");
        assertCode(() -> new InvoiceExtractionSuggestion.Proposal(INVOICE_NUMBER, "0123", null, List.of(evidence(1))), "INVALID_AGENT_OUTPUT");
        assertCode(() -> new InvoiceExtractionSuggestion.Proposal(INVOICE_NUMBER, "0123", InvoiceExtractionSuggestion.Confidence.HIGH, List.of()), "INVALID_AGENT_OUTPUT");
        assertCode(() -> new InvoiceExtractionSuggestion.Proposal(INVOICE_NUMBER, "0123", InvoiceExtractionSuggestion.Confidence.HIGH, List.of(evidence(1), evidence(1))), "INVALID_AGENT_OUTPUT");
        assertCode(() -> new InvoiceExtractionSuggestion.Evidence(INPUT.originalId(), INPUT.originalDigest(), 1, "x".repeat(InvoiceExtractionSuggestion.MAX_QUOTE_LENGTH + 1)), "INVALID_AGENT_OUTPUT");
    }

    @Test
    void noRecognizableFieldsCompletesWithoutInventingAValueOrAllowingConfirmation() {
        var run = started(); run.complete(2, suggestion(List.of()), NOW.plusSeconds(2));
        assertThat(run.state().suggestion().proposals()).isEmpty();
        assertCode(() -> run.confirm(3, "alice", INPUT, List.of(new InvoiceExtractionSuggestion.Selection(INVOICE_NUMBER, "0123")), null, NOW.plusSeconds(3)), "INVALID_AGENT_REVIEW");
        run.dismiss(3, "alice", "没有识别到可用信息", NOW.plusSeconds(3)); assertRestores(run);
    }

    @Test
    void localXmlCanBeReviewedAndRestoredWithoutAModelDestination() {
        var input = new InvoiceExtractionInput(INPUT.invoiceId(), INPUT.originalId(), INPUT.originalDigest(), InvoiceOriginal.Format.XML, 200, 1);
        var context = new InvoiceExtractionRun.Context(UUID.randomUUID(), "demo", "alice", NOW, input,
                InvoiceExtractionSuggestion.Method.STRUCTURED_XML, null);
        var run = new InvoiceExtractionRun(context); run.start(1, NOW.plusSeconds(1));
        var evidence = new InvoiceExtractionSuggestion.Evidence(input.originalId(), input.originalDigest(), 1, "00001234",
                "/EInvoice[1]/TaxSupervisionInfo[1]/InvoiceNumber[1]");
        var result = new InvoiceExtractionSuggestion(InvoiceExtractionSuggestion.Method.STRUCTURED_XML, "local-xml", "einvoice-0.31-v1",
                InvoiceExtractionRun.CONTRACT_VERSION, List.of(new InvoiceExtractionSuggestion.Proposal(INVOICE_NUMBER, "00001234",
                InvoiceExtractionSuggestion.Confidence.HIGH, List.of(evidence))));
        run.complete(2, result, NOW.plusSeconds(2));
        run.confirm(3, "alice", input, List.of(new InvoiceExtractionSuggestion.Selection(INVOICE_NUMBER, "00001235")), "本人核对", NOW.plusSeconds(3));
        assertThat(run.state().suggestion().method()).isEqualTo(InvoiceExtractionSuggestion.Method.STRUCTURED_XML);
        assertThat(run.state().suggestion().proposals().get(0).value()).isEqualTo("00001234");
        assertThat(run.context().targetDigest()).isNull();
        assertRestores(run);
    }

    @Test
    void localAndModelMethodsCannotSwapDuringExecutionOrBorrowEachOthersEvidence() {
        var xml = new InvoiceExtractionInput(INPUT.invoiceId(), INPUT.originalId(), INPUT.originalDigest(), InvoiceOriginal.Format.XML, 200, 1);
        var local = new InvoiceExtractionRun(new InvoiceExtractionRun.Context(UUID.randomUUID(), "demo", "alice", NOW, xml,
                InvoiceExtractionSuggestion.Method.STRUCTURED_XML, null));
        local.start(1, NOW.plusSeconds(1));
        assertCode(() -> local.complete(2, suggestion(List.of(proposal(INVOICE_NUMBER, "0123"))), NOW.plusSeconds(2)), "INVALID_AGENT_OUTPUT");
        var emptyLocal = new InvoiceExtractionSuggestion(InvoiceExtractionSuggestion.Method.STRUCTURED_XML, "local-xml", "v1",
                InvoiceExtractionRun.CONTRACT_VERSION, List.of());
        assertCode(() -> started().complete(2, emptyLocal, NOW.plusSeconds(2)), "INVALID_AGENT_OUTPUT");
        assertCode(() -> emptyLocal.requireMatches(INPUT), "INVALID_AGENT_OUTPUT");
        assertCode(() -> new InvoiceExtractionRun.Context(UUID.randomUUID(), "demo", "alice", NOW, xml,
                InvoiceExtractionSuggestion.Method.STRUCTURED_XML, "b".repeat(64)), "INVALID_AGENT_INPUT");
        assertCode(() -> new InvoiceExtractionRun.Context(UUID.randomUUID(), "demo", "alice", NOW, xml,
                InvoiceExtractionSuggestion.Method.MODEL, null), "INVALID_AGENT_INPUT");
        assertCode(() -> new InvoiceExtractionRun.Context(UUID.randomUUID(), "demo", "alice", NOW, INPUT,
                InvoiceExtractionSuggestion.Method.STRUCTURED_XML, null), "INVALID_AGENT_INPUT");
        assertCode(() -> new InvoiceExtractionSuggestion(InvoiceExtractionSuggestion.Method.STRUCTURED_XML, "local-xml", "v1",
                InvoiceExtractionRun.CONTRACT_VERSION, List.of(proposal(INVOICE_NUMBER, "0123"))), "INVALID_AGENT_OUTPUT");
        var path = new InvoiceExtractionSuggestion.Evidence(INPUT.originalId(), INPUT.originalDigest(), 1, "0123", "/EInvoice[1]/InvoiceNumber[1]");
        assertCode(() -> suggestion(List.of(new InvoiceExtractionSuggestion.Proposal(INVOICE_NUMBER, "0123",
                InvoiceExtractionSuggestion.Confidence.HIGH, List.of(path)))), "INVALID_AGENT_OUTPUT");
        for (var invalidPath : List.of("//InvoiceNumber", "/EInvoice[2]/InvoiceNumber[1]", "/EInvoice[1]/../InvoiceNumber[1]", "file:///a")) {
            assertCode(() -> new InvoiceExtractionSuggestion.Evidence(INPUT.originalId(), INPUT.originalDigest(), 1, "0123", invalidPath), "INVALID_AGENT_OUTPUT");
        }
    }

    private static InvoiceExtractionRun queued() {
        return new InvoiceExtractionRun(new InvoiceExtractionRun.Context(UUID.randomUUID(), "demo", "alice", NOW, INPUT, InvoiceExtractionSuggestion.Method.MODEL, "b".repeat(64)));
    }
    private static InvoiceExtractionRun started() { var run = queued(); run.start(1, NOW.plusSeconds(1)); return run; }
    private static InvoiceExtractionRun completed() { var run = started(); run.complete(2, suggestion(List.of(proposal(INVOICE_NUMBER, "0123"))), NOW.plusSeconds(2)); return run; }
    private static InvoiceExtractionSuggestion.Evidence evidence(int page) { return new InvoiceExtractionSuggestion.Evidence(INPUT.originalId(), INPUT.originalDigest(), page, "票面原文"); }
    private static InvoiceExtractionSuggestion.Proposal proposal(InvoiceExtractionSuggestion.Field field, String value) { return new InvoiceExtractionSuggestion.Proposal(field, value, InvoiceExtractionSuggestion.Confidence.HIGH, List.of(evidence(1))); }
    private static InvoiceExtractionSuggestion suggestion(List<InvoiceExtractionSuggestion.Proposal> proposals) { return new InvoiceExtractionSuggestion(InvoiceExtractionSuggestion.Method.MODEL, "fixture", "v1", InvoiceExtractionRun.CONTRACT_VERSION, proposals); }
    private static void assertRestores(InvoiceExtractionRun run) { assertThat(InvoiceExtractionRun.restore(run.context(), run.state()).state()).isEqualTo(run.state()); }
    private static void assertCode(ThrowingCallable operation, String code) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(DomainException.class, failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
