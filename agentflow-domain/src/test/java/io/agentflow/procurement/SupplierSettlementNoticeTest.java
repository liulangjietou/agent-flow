package io.agentflow.procurement;

import io.agentflow.notification.SupplierSettlementNotice;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.procurement.SupplierPayableSettlementTest.bank;
import static io.agentflow.procurement.SupplierPayableSettlementTest.command;
import static io.agentflow.procurement.SupplierPayableSettlementTest.evidence;
import static org.assertj.core.api.Assertions.*;

/**
 * 原结算事实不把正常等待、查询、ERP 成功或停止发送混同为本地完成。
 * @author owlzhangfq@gmail.com
 */
class SupplierSettlementNoticeTest {
    @Test void preparationRetryAndStoppedFactsRetainOneOriginalIdentity() {
        var bank = bank(); var now = bank.updatedAt().plusSeconds(4); var command = command(bank, now);
        var queue = SupplierSettlementPreparation.queue(UUID.randomUUID(), bank, "finance", command.period().request().accountingDate(), now);
        var claim = queue.claim(now, Duration.ofSeconds(30));
        assertThat(SupplierSettlementNotice.from(queue)).isEmpty(); assertThat(SupplierSettlementNotice.from(claim)).isEmpty();
        assertThat(SupplierSettlementNotice.from(claim.unavailable(SupplierSettlementPreparation.Issue.CONNECTION, now))).contains(SupplierSettlementNotice.PREPARATION_RETRY);
        assertThat(SupplierSettlementNotice.from(claim.expireLease(claim.leaseUntil()))).contains(SupplierSettlementNotice.PREPARATION_RETRY);
        assertThat(SupplierSettlementNotice.from(claim.block(SupplierSettlementPreparation.Issue.EVIDENCE_CHANGED, now))).contains(SupplierSettlementNotice.PREPARATION_BLOCKED);
        assertThat(SupplierSettlementNotice.from(queue.voidSource(now))).contains(SupplierSettlementNotice.PREPARATION_VOIDED);
    }
    @Test void originalSendFailuresAndExplicitQueriesHaveDifferentMeanings() {
        var bank = bank(); var now = bank.updatedAt().plusSeconds(4); var command = command(bank, now);
        var queue = SupplierPayableSettlementOperation.queue(command, now); var claim = queue.claim(now, Duration.ofSeconds(30));
        assertThat(SupplierSettlementNotice.from(queue)).isEmpty(); assertThat(SupplierSettlementNotice.from(claim)).isEmpty();
        assertThat(SupplierSettlementNotice.from(claim.unavailableBeforeSend(SupplierPayableSettlementOperation.Failure.TIMEOUT, now))).contains(SupplierSettlementNotice.EXECUTION_RETRY);
        assertThat(SupplierSettlementNotice.from(queue.voidBeforeSend(SupplierPayableSettlementOperation.Failure.SOURCE_CHANGED, now))).contains(SupplierSettlementNotice.VOIDED);
        var sending = claim.readyToSend(evidence(command, now), now);
        assertThat(SupplierSettlementNotice.from(sending)).isEmpty();
        var unknown = sending.unavailable(SupplierPayableSettlementOperation.Failure.CONNECTION, now);
        assertThat(SupplierSettlementNotice.from(unknown)).contains(SupplierSettlementNotice.UNKNOWN);
        assertThat(SupplierSettlementNotice.from(unknown.requestQuery(now))).isEmpty();
    }
    @Test void canonicalEventKeysDeduplicateOnlySameIntentAndFact() {
        var first = UUID.fromString("abcdefab-abcd-abcd-abcd-abcdefabcdef"); var second = UUID.randomUUID();
        for (var fact : SupplierSettlementNotice.values()) {
            assertThat(SupplierSettlementNotice.source(fact.eventKey(first))).contains(new SupplierSettlementNotice.Source(first, fact));
            assertThat(fact.eventKey(first)).isNotEqualTo(fact.eventKey(second));
        }
        for (var bad : new String[] { "", "supplier-settlement:1-1-1-1-1:COMPLETED", "supplier-settlement:" + first + ":PENDING", "supplier-settlement:" + first + ":COMPLETED:2", "supplier-settlement:" + first.toString().toUpperCase() + ":COMPLETED" })
            assertThat(SupplierSettlementNotice.source(bad)).isEmpty();
        assertThat(SupplierSettlementNotice.source(null)).isEmpty();
    }
}
