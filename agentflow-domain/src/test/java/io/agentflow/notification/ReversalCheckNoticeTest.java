package io.agentflow.notification;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 独立冲销消息键只接受规范身份与闭集事实，不能转成任意业务入口。
 * @author owlzhangfq@gmail.com
 */
class ReversalCheckNoticeTest {
    @Test void supportedFactsKeepTheOriginalCommandIdentity() {
        UUID id = UUID.randomUUID();
        for (var notice : ReversalCheckNotice.values()) {
            assertThat(ReversalCheckNotice.source(notice.eventKey(id))).contains(new ReversalCheckNotice.Source(id, notice));
            assertThat(notice.eventKey(id)).hasSizeLessThan(128);
        }
    }
    @Test void malformedOrForeignSourcesAreRejected() {
        UUID id = UUID.fromString("abcdefab-1234-1234-1234-123456789abc");
        for (String key : new String[]{"", "voucher:" + id + ":RECORDED", "reversal-check:1-1-1-1-1:RECORDED", "reversal-check:" + id.toString().toUpperCase() + ":RECORDED",
                "reversal-check:" + id + ":UNKNOWN:extra", "reversal-check:" + id + ":PENDING", "reversal-check:" + id + ":"}) assertThat(ReversalCheckNotice.source(key)).isEmpty();
        assertThat(ReversalCheckNotice.source(null)).isEmpty();
    }
}
