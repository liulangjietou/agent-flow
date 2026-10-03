package io.agentflow.procurement;

import io.agentflow.notification.SupplierAdjustmentNotice;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.procurement.SupplierPayableAdjustmentTest.command;
import static io.agentflow.procurement.SupplierPayableAdjustmentTest.evidence;
import static org.assertj.core.api.Assertions.*;

/**
 * 原应付调整事实不把正常等待、查询、ERP 成功或停止发送混同为本地完成。
 * @author owlzhangfq@gmail.com
 */
class SupplierAdjustmentNoticeTest {
    @Test void preparationRetryAndStoppedFactsRetainOneOriginalIdentity() {
        var command = command(false, "20"); var now = command.registeredAt();
        var queue = SupplierAdjustmentPreparation.queue(UUID.randomUUID(), command.source(), "finance", command.period().request().accountingDate(), now);
        var claim = queue.claim(now, Duration.ofSeconds(30));
        assertThat(SupplierAdjustmentNotice.from(queue)).isEmpty(); assertThat(SupplierAdjustmentNotice.from(claim)).isEmpty();
        assertThat(SupplierAdjustmentNotice.from(claim.unavailable(SupplierAdjustmentPreparation.Issue.CONNECTION, now))).contains(SupplierAdjustmentNotice.PREPARATION_RETRY);
        assertThat(SupplierAdjustmentNotice.from(claim.expireLease(claim.leaseUntil()))).contains(SupplierAdjustmentNotice.PREPARATION_RETRY);
        assertThat(SupplierAdjustmentNotice.from(claim.block(SupplierAdjustmentPreparation.Issue.EVIDENCE_CHANGED, now))).contains(SupplierAdjustmentNotice.PREPARATION_BLOCKED);
        assertThat(SupplierAdjustmentNotice.from(queue.voidSource(now))).contains(SupplierAdjustmentNotice.PREPARATION_VOIDED);
    }
    @Test void originalSendFailuresAndExplicitQueriesHaveDifferentMeanings() {
        var command = command(false, "20"); var now = command.registeredAt();
        var queue = SupplierPayableAdjustmentOperation.queue(command, now); var claim = queue.claim(now, Duration.ofSeconds(30));
        assertThat(SupplierAdjustmentNotice.from(queue)).isEmpty(); assertThat(SupplierAdjustmentNotice.from(claim)).isEmpty();
        assertThat(SupplierAdjustmentNotice.from(claim.unavailableBeforeSend(SupplierPayableAdjustmentOperation.Failure.TIMEOUT, now))).contains(SupplierAdjustmentNotice.EXECUTION_RETRY);
        assertThat(SupplierAdjustmentNotice.from(queue.voidBeforeSend(SupplierPayableAdjustmentOperation.Failure.SOURCE_CHANGED, now))).contains(SupplierAdjustmentNotice.VOIDED);
        var sending = claim.readyToSend(evidence(command, now), now);
        assertThat(SupplierAdjustmentNotice.from(sending)).isEmpty();
        var unknown = sending.unavailable(SupplierPayableAdjustmentOperation.Failure.CONNECTION, now);
        assertThat(SupplierAdjustmentNotice.from(unknown)).contains(SupplierAdjustmentNotice.UNKNOWN);
        assertThat(SupplierAdjustmentNotice.from(unknown.requestQuery(now))).isEmpty();
    }
    @Test void canonicalEventKeysDeduplicateOnlySameIntentAndFact() {
        var first = UUID.fromString("abcdefab-abcd-abcd-abcd-abcdefabcdef"); var second = UUID.randomUUID();
        for (var fact : SupplierAdjustmentNotice.values()) {
            assertThat(SupplierAdjustmentNotice.source(fact.eventKey(first))).contains(new SupplierAdjustmentNotice.Source(first, fact));
            assertThat(fact.eventKey(first)).isNotEqualTo(fact.eventKey(second));
        }
        for (var bad : new String[] { "", "supplier-adjustment:1-1-1-1-1:COMPLETED", "supplier-adjustment:" + first + ":PENDING", "supplier-adjustment:" + first + ":COMPLETED:2", "supplier-adjustment:" + first.toString().toUpperCase() + ":COMPLETED" })
            assertThat(SupplierAdjustmentNotice.source(bad)).isEmpty();
        assertThat(SupplierAdjustmentNotice.source(null)).isEmpty();
    }
}
