package io.agentflow.event;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/**
 * 契约正文、可用性及来源边界独立于引擎，发布与停用都不能改变既有版本身份。
 * @author owlzhangfq@gmail.com
 */
class EventContractTest {
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00.123456789Z");

    @Test
    void publicationKeepsTheExplicitSourceTypeAndHumanReason() {
        var value = publish("accepted", "erp", "GoodsAccepted", "  核对来源后发布  ");
        assertThat(value.name()).isEqualTo("验收完成");
        assertThat(value.sourceKey()).isEqualTo("erp"); assertThat(value.eventType()).isEqualTo("GoodsAccepted");
        assertThat(value.envelopeVersion()).isEqualTo(1); assertThat(value.version()).isEqualTo(1);
        assertThat(value.publicationReason()).isEqualTo("核对来源后发布");
        assertThat(value.publishedAt()).isEqualTo(Instant.parse("2026-10-01T12:00:00.123456Z"));
    }

    @Test
    void sourceAndContractKeysCannotBeUrlsExpressionsOrAmbiguousNames() {
        for (String invalid : List.of("", " ", "ERP", "../erp", "https://erp.example", "${source}", "erp\n", "e".repeat(65))) {
            assertInvalid(() -> publish(invalid, "erp", "GoodsAccepted", "发布"));
            assertInvalid(() -> publish("accepted", invalid, "GoodsAccepted", "发布"));
        }
        for (String invalid : List.of("", "${evil}", "Event Type", "type\n", "E".repeat(129))) {
            assertInvalid(() -> publish("accepted", "erp", invalid, "发布"));
        }
        assertInvalid(() -> publish("accepted", null, "GoodsAccepted", "发布"));
    }

    @Test
    void blankOrUnboundedMetadataCannotCreateAPublication() {
        for (String reason : List.of("", " \n", "x".repeat(2001))) assertInvalid(() -> publish("accepted", "erp", "GoodsAccepted", reason));
        for (String name : List.of("", " \n", "a\nb", "a".repeat(201))) {
            assertInvalid(() -> EventContract.publish("demo", "accepted", 1, name, "erp", "GoodsAccepted", "admin", "发布", NOW));
        }
        assertInvalid(() -> EventContract.publish("demo", "accepted", 0, "验收完成", "erp", "GoodsAccepted", "admin", "发布", NOW));
    }

    @Test
    void disablingAndRestoringOneVersionNeverRewritesItsPublication() {
        var contract = publish("accepted", "erp", "GoodsAccepted", "首次发布");
        var first = EventContractAvailability.published(contract);
        var disabled = first.change(1, false, "operator", "暂时停用", NOW.plusSeconds(10));
        var restored = disabled.change(2, true, "admin", "核对后恢复", NOW.plusSeconds(20));
        assertThat(first.enabled()).isTrue(); assertThat(first.revision()).isEqualTo(1);
        assertThat(disabled.enabled()).isFalse(); assertThat(disabled.revision()).isEqualTo(2);
        assertThat(restored.enabled()).isTrue(); assertThat(restored.revision()).isEqualTo(3);
        assertThat(restored.key()).isEqualTo(contract.key()); assertThat(restored.contractVersion()).isEqualTo(1);
        assertThat(contract.publicationReason()).isEqualTo("首次发布"); assertThat(contract.publishedBy()).isEqualTo("admin");
    }

    @Test
    void staleOrRepeatedAvailabilityActionsCannotProduceExtraHistory() {
        var value = EventContractAvailability.published(publish("accepted", "erp", "GoodsAccepted", "发布"));
        assertThatThrownBy(() -> value.change(0, false, "admin", "旧修订", NOW)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("CONCURRENCY_CONFLICT");
        assertThatThrownBy(() -> value.change(1, true, "admin", "重复启用", NOW)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("EVENT_CONTRACT_AVAILABILITY_UNCHANGED");
        assertInvalid(() -> value.change(1, false, "admin", " ", NOW));
    }

    private static EventContract publish(String key, String source, String type, String reason) {
        return EventContract.publish("demo", key, 1, " 验收完成 ", source, type, "admin", reason, NOW);
    }
    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(DomainException.class).extracting("code").isEqualTo("INVALID_EVENT_CONTRACT");
    }
}
