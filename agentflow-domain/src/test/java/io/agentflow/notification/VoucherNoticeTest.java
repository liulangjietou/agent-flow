package io.agentflow.notification;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 凭证消息来源必须保持原编号及闭集事实，不解析任意操作路径。
 * @author owlzhangfq@gmail.com
 */
class VoucherNoticeTest {
    @Test void everySupportedFactRetainsItsOriginalPreparationOrOperationIdentity() {
        UUID id = UUID.randomUUID();
        for (var notice : VoucherNotice.values()) {
            assertThat(VoucherNotice.source(notice.eventKey(id))).contains(new VoucherNotice.Source(id, notice));
            assertThat(notice.eventKey(id)).hasSizeLessThan(128);
        }
    }

    @Test void malformedOrForeignKeysCannotBecomeVoucherTargets() {
        UUID id = UUID.fromString("abcdefab-1234-1234-1234-123456789abc");
        for (String key : new String[]{"", "payment:" + id + ":POSTED", "voucher:1-1-1-1-1:POSTED", "voucher:" + id.toString().toUpperCase() + ":POSTED",
                "voucher:" + id + ":UNKNOWN:extra", "voucher:" + id + ":PENDING", "voucher:" + id + ":"}) assertThat(VoucherNotice.source(key)).isEmpty();
        assertThat(VoucherNotice.source(null)).isEmpty();
    }
}
