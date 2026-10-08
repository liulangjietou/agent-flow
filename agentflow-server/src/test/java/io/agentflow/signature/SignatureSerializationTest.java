package io.agentflow.signature;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 服务端统一 JSON 边界恢复原授权和状态，不给领域模块引入时间序列化依赖。
 *
 * @author owlzhangfq@gmail.com
 */
class SignatureSerializationTest {
    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(15);
    private final JsonUtil json = new JsonUtil(new ObjectMapper().registerModule(new JavaTimeModule()));

    @Test
    void everyPhaseRetainsAuthorizationDigestReceiptAndReservedResultFiles() {
        var queued = queued(); var sent = queued.claim(NOW, LEASE);
        var unknown = sent.unavailable(SignatureOperation.Failure.TIMEOUT, NOW.plusSeconds(1));
        var query = unknown.claim(unknown.nextAttemptAt(), LEASE);
        var pending = query.complete(receipt(queued, SignatureReceipt.Status.PENDING, 1, query.updatedAt()), query.updatedAt().plusSeconds(1));
        var collecting = pending.receiveCallback(receipt(queued, SignatureReceipt.Status.SIGNED, 2, pending.updatedAt()), pending.updatedAt());
        var downloading = collecting.claim(collecting.nextAttemptAt(), LEASE);
        var done = downloading.completeFiles(downloading.artifacts(), downloading.updatedAt().plusSeconds(1));
        var declined = sent.complete(receipt(queued, SignatureReceipt.Status.DECLINED, 1, NOW), NOW.plusSeconds(1));
        var cancelled = queued.cancelUnsent(NOW.plusSeconds(1));
        var expired = queued.claim(queued.input().request().authorization().validUntil(), LEASE);
        for (var stage : List.of(queued, sent, unknown, query, pending, collecting, downloading, done, declined, cancelled, expired)) {
            var restored = json.readStrict(json.write(stage), SignatureOperation.class);
            assertThat(restored).isEqualTo(stage);
            assertThat(restored.input().request().digest()).isEqualTo(queued.input().request().digest());
        }
        assertThat(json.readStrict(json.write(unknown), SignatureOperation.class).claim(unknown.nextAttemptAt(), LEASE).status())
                .isEqualTo(SignatureOperation.Status.QUERYING);
        assertThat(json.readStrict(json.write(collecting), SignatureOperation.class).claim(collecting.nextAttemptAt(), LEASE).artifacts())
                .isEqualTo(collecting.artifacts());
    }

    @Test
    void malformedStoredStateCannotBecomeFreshSendOrCompleteWithoutItsResultFiles() {
        var queued = queued(); var sent = queued.claim(NOW, LEASE);
        var unknown = sent.unavailable(SignatureOperation.Failure.TIMEOUT, NOW.plusSeconds(1));
        var stored = json.write(unknown);
        for (String changed : List.of(stored.replace("\"status\":\"UNKNOWN\"", "\"status\":\"QUEUED\""),
                stored.replace("\"failure\":\"TIMEOUT\"", "\"failure\":null"))) {
            assertThat(changed).isNotEqualTo(stored);
            assertThatThrownBy(() -> json.readStrict(changed, SignatureOperation.class)).isInstanceOf(DomainException.class);
        }
        var collecting = sent.complete(receipt(queued, SignatureReceipt.Status.SIGNED, 1, NOW), NOW.plusSeconds(1));
        var map = json.map(json.write(collecting)); map.put("artifacts", List.of());
        assertThatThrownBy(() -> json.readStrict(json.write(map), SignatureOperation.class)).isInstanceOf(DomainException.class);
    }

    @Test
    void externalReceiptCannotContainDuplicateStatusOrTrailingJson() {
        var serialized = json.write(receipt(queued(), SignatureReceipt.Status.SIGNED, 1, NOW));
        for (String changed : List.of(serialized.replace("\"status\":\"SIGNED\"", "\"status\":\"SIGNED\",\"status\":\"PENDING\""), serialized + " {}")) {
            assertThat(changed).isNotEqualTo(serialized);
            assertThatThrownBy(() -> json.readStrict(changed, SignatureReceipt.class)).isInstanceOf(DomainException.class);
        }
    }

    private SignatureOperation queued() {
        var request = new SignatureRequest(UUID.randomUUID(), "tenant-a", new SignatureRequest.Source(UUID.randomUUID(), 2, 7, "contract", 3),
                new SignatureRequest.Authorization("alice", "company-seal", 2, "c".repeat(64), "合同签署授权", NOW, NOW.plusSeconds(3600)),
                List.of(new SignatureRequest.Document(UUID.randomUUID(), UUID.randomUUID(), "contract", "合同.pdf", 1001, "a".repeat(64))),
                List.of(new SignatureRequest.Signer("company", "provider-company-1")));
        return SignatureOperation.queue(new SignatureOperation.Input(request, "d".repeat(64)), NOW);
    }

    private SignatureReceipt receipt(SignatureOperation operation, SignatureReceipt.Status status, long revision, Instant time) {
        var request = operation.input().request();
        boolean terminal = status == SignatureReceipt.Status.SIGNED || status == SignatureReceipt.Status.DECLINED;
        var artifacts = status == SignatureReceipt.Status.SIGNED ? request.documents().stream().map(document -> new SignatureReceipt.Artifact(
                document.attachmentId(), document.size() + 400, "e".repeat(64), "application/pdf",
                List.of(new SignatureReceipt.Proof("company", "f".repeat(64), time, null)))).toList() : List.<SignatureReceipt.Artifact>of();
        return new SignatureReceipt(request.id(), request.digest(), revision, status, time, "provider-signature-1", terminal ? time : null, artifacts);
    }
}
