package io.agentflow.notification;

import io.agentflow.procurement.SupplierPaymentExecutionRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 供应商通知固定原授权和原选择，检查异常与资金结果使用不同语义。
 * @author owlzhangfq@gmail.com
 */
class SupplierPaymentNoticeTest {
    @Test void sourceRequiresBothCanonicalIdentitiesAndKnownFact() {
        UUID payment = UUID.fromString("abcdefab-1234-1234-1234-123456789abc"), request = UUID.randomUUID();
        for (var notice : SupplierPaymentNotice.values()) {
            assertThat(SupplierPaymentNotice.source(notice.eventKey(payment, request))).contains(new SupplierPaymentNotice.Source(payment, request, notice));
            assertThat(notice.eventKey(payment, request)).isNotEqualTo(notice.eventKey(payment, UUID.randomUUID())).hasSizeLessThan(256);
        }
        for (String key : new String[]{"", "employee-payment:" + payment + ":SUCCEEDED", "supplier-payment:1-1-1-1-1:" + request + ":FAILED",
                "supplier-payment:" + payment.toString().toUpperCase() + ":" + request + ":FAILED",
                "supplier-payment:" + payment + ":1-1-1-1-1:FAILED", "supplier-payment:" + payment + ":" + request + ":PENDING",
                "supplier-payment:" + payment + ":" + request + ":SUCCEEDED:extra"}) assertThat(SupplierPaymentNotice.source(key)).isEmpty();
        assertThat(SupplierPaymentNotice.source(null)).isEmpty();
    }
    @Test void requestStatesNeverInventABankOutcome() {
        var at = Instant.parse("2026-10-03T12:00:00Z");
        var input = new SupplierPaymentExecutionRequest.Input(UUID.randomUUID(), "demo", UUID.randomUUID(), 1, "cashier", "debit", "v1");
        var queued = new SupplierPaymentExecutionRequest(input, 1, SupplierPaymentExecutionRequest.Status.QUEUED, 0, at, at, at, null, null);
        var running = queued.claim(at, Duration.ofSeconds(30));
        assertThat(SupplierPaymentNotice.from(queued)).isEmpty(); assertThat(SupplierPaymentNotice.from(running)).isEmpty();
        assertThat(SupplierPaymentNotice.from(running.unavailable(SupplierPaymentExecutionRequest.Failure.TIMEOUT, at))).contains(SupplierPaymentNotice.CHECK_UNAVAILABLE);
        assertThat(SupplierPaymentNotice.from(running.block(at))).contains(SupplierPaymentNotice.EVIDENCE_CHANGED);
        assertThat(SupplierPaymentNotice.from(running.voidSource(at))).contains(SupplierPaymentNotice.SOURCE_CHANGED);
        assertThat(SupplierPaymentNotice.from(running.expireAuthorization(at))).contains(SupplierPaymentNotice.EXPIRED);
    }
}
