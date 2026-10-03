package io.agentflow.notification;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 历史通知的来源定位保持规范标识，不能混用其他事件或宽松 UUID 别名。
 * @author owlzhangfq@gmail.com
 */
class PaymentNoticeTest {
    @Test void stableKeyPreservesOriginalPaymentAndNoticeAcrossEverySupportedFact() {
        UUID id = UUID.randomUUID();
        for (var notice : PaymentNotice.values()) {
            assertThat(PaymentNotice.source(notice.eventKey(id))).contains(new PaymentNotice.Source(id, notice));
            assertThat(notice.eventKey(id)).hasSizeLessThan(128);
        }
    }

    @Test void malformedOrForeignKeysCannotBecomePaymentTargets() {
        UUID id = UUID.fromString("abcdefab-1234-1234-1234-123456789abc");
        for (String key : new String[]{"", "payment:" + id + ":SUCCEEDED", "employee-payment:1-1-1-1-1:SUCCEEDED",
                "employee-payment:" + id.toString().toUpperCase() + ":SUCCEEDED", "employee-payment:" + id + ":UNKNOWN:extra",
                "employee-payment:" + id + ":PENDING", "employee-payment:" + id + ":"}) {
            assertThat(PaymentNotice.source(key)).isEmpty();
        }
        assertThat(PaymentNotice.source(null)).isEmpty();
    }
}
