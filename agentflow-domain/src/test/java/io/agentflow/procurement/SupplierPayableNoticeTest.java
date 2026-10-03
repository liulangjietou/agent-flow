package io.agentflow.procurement;

import io.agentflow.notification.SupplierPayableNotice;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.procurement.SupplierPaymentTestData.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 通知区分未授权读取、未知预留与实际结束，闭集键不能跨来源复用。
 * @author owlzhangfq@gmail.com
 */
class SupplierPayableNoticeTest {
    @Test void waitingAndRequestedQueryAreQuietAndRetirementRequiresSeparateActualDecision() {
        var review = SupplierPayableReview.queue(UUID.randomUUID(), approved(), "finance", AUTHORIZED_AT);
        assertThat(SupplierPayableNotice.from(review)).isEmpty();
        var running = review.claim(AUTHORIZED_AT, Duration.ofSeconds(30));
        assertThat(SupplierPayableNotice.from(running.fail(SupplierPayableReview.Issue.TIMEOUT, AUTHORIZED_AT))).contains(SupplierPayableNotice.REVIEW_UNAVAILABLE);
        assertThat(SupplierPayableNotice.from(running.expireLease(AUTHORIZED_AT.plusSeconds(30)))).contains(SupplierPayableNotice.REVIEW_INTERRUPTED);
        var queued = SupplierPayableHoldOperation.queue(new SupplierPayableHoldCommand(authorization()), AUTHORIZED_AT);
        assertThat(SupplierPayableNotice.from(queued)).isEmpty(); assertThat(SupplierPayableNotice.from(queued.stopForRetirement(AUTHORIZED_AT))).isEmpty();
        var unknown = queued.claim(AUTHORIZED_AT, Duration.ofSeconds(30)).unavailable(SupplierPayableHoldOperation.Failure.TIMEOUT, AUTHORIZED_AT);
        assertThat(SupplierPayableNotice.from(unknown)).contains(SupplierPayableNotice.UNKNOWN);
        assertThat(SupplierPayableNotice.from(unknown.requestQuery(AUTHORIZED_AT))).isEmpty();
    }
    @Test void keysRequireCanonicalIdentityAndMatchingOriginalSource() {
        UUID id = UUID.randomUUID();
        for (var notice : SupplierPayableNotice.values()) {
            assertThat(SupplierPayableNotice.source(notice.eventKey(id))).contains(new SupplierPayableNotice.Source(notice.sourceType(), id, notice));
            var wrong = notice.sourceType() == SupplierPayableNotice.SourceType.REVIEW ? "OPERATION" : "REVIEW";
            assertThat(SupplierPayableNotice.source("supplier-payable:" + wrong + ":" + id + ":" + notice.name())).isEmpty();
            assertThat(notice.content()).doesNotContain("70.00", "private-");
        }
        for (String invalid : java.util.List.of("supplier-payable:OPERATION:1-1-1-1-1:HELD", "supplier-payable:OPERATION:" + id + ":UNKNOWN:extra", "supplier-payment:OPERATION:" + id + ":HELD")) {
            assertThat(SupplierPayableNotice.source(invalid)).isEmpty();
        }
    }
}
