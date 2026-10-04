package io.agentflow.servicetask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 固定命令和领取状态经统一 JSON 边界往返后不能改变类型、摘要或恢复动作。
 * @author owlzhangfq@gmail.com
 */
class ServiceTaskSerializationTest {
    private static final Instant NOW = Instant.parse("2026-10-03T09:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(15);
    private final JsonUtil json = new JsonUtil(new ObjectMapper().registerModule(new JavaTimeModule()));

    @Test
    void everyPersistableExecutionStateRetainsOriginalInputsDigestAndRecoveryDirection() {
        var queued = queue(); var sent = queued.claim(NOW, LEASE);
        var unknown = sent.unavailable(ServiceTaskOperation.Failure.TIMEOUT, NOW.plusSeconds(1));
        var query = unknown.claim(unknown.nextAttemptAt(), LEASE);
        var missing = query.complete(observed(query, ServiceTaskObservation.Status.NOT_FOUND), query.updatedAt().plusSeconds(1));
        var resent = missing.claim(missing.nextAttemptAt(), LEASE);
        var applied = resent.complete(observed(resent, ServiceTaskObservation.Status.APPLIED), resent.updatedAt().plusSeconds(1));
        var rejected = sent.complete(observed(sent, ServiceTaskObservation.Status.REJECTED), NOW.plusSeconds(1));
        for (var value : List.of(queued, sent, unknown, query, missing, resent, applied, rejected)) {
            var restored = json.readStrict(json.write(value), ServiceTaskOperation.class);
            assertThat(restored).isEqualTo(value);
            assertThat(restored.input().command().digest()).isEqualTo(queued.input().command().digest());
            assertThat(restored.input().command().inputs()).containsEntry("amount", "12.50").containsEntry("confirmed", true);
        }
        var restored = json.readStrict(json.write(unknown), ServiceTaskOperation.class);
        assertThat(restored.claim(restored.nextAttemptAt(), LEASE).status()).isEqualTo(ServiceTaskOperation.Status.QUERYING);
    }

    @Test
    void malformedStoredStatesCannotBecomeFreshExecutionOrSilentlyCoerceInputs() {
        var sent = queue().claim(NOW, LEASE);
        var unknown = sent.unavailable(ServiceTaskOperation.Failure.TIMEOUT, NOW.plusSeconds(1));
        var stored = json.write(unknown);
        for (String changed : List.of(stored.replace("\"status\":\"UNKNOWN\"", "\"status\":\"QUEUED\""),
                stored.replace("\"failure\":\"TIMEOUT\"", "\"failure\":null"),
                stored.replace("\"amount\":\"12.50\"", "\"amount\":12.50"))) {
            assertThat(changed).isNotEqualTo(stored);
            assertThatThrownBy(() -> json.readStrict(changed, ServiceTaskOperation.class))
                    .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("JSON_DESERIALIZATION_FAILED"));
        }
    }

    @Test
    void externalReceiptParsingRejectsDuplicateStatusAndTrailingDocuments() {
        var sent = queue().claim(NOW, LEASE);
        var stored = json.write(observed(sent, ServiceTaskObservation.Status.APPLIED));
        for (String changed : List.of(stored.replace("\"status\":\"APPLIED\"", "\"status\":\"APPLIED\",\"status\":\"PENDING\""), stored + " {}")) {
            assertThatThrownBy(() -> json.readStrict(changed, ServiceTaskObservation.class)).isInstanceOf(DomainException.class);
        }
    }

    private ServiceTaskOperation queue() {
        var contract = new ServiceTaskContract("receipt.register", 1, "登记", List.of(
                new ServiceTaskContract.Parameter("amount", ServiceTaskContract.Type.NUMBER, true, false),
                new ServiceTaskContract.Parameter("confirmed", ServiceTaskContract.Type.BOOLEAN, true, false)));
        var binding = new ServiceTaskCommand.Binding(UUID.randomUUID(), 1, "request", 1, "a".repeat(64), "process", "execution", "service");
        var command = new ServiceTaskCommand(UUID.randomUUID(), "tenant", binding, contract, Map.of("amount", "12.50", "confirmed", true));
        return ServiceTaskOperation.queue(new ServiceTaskOperation.Input(command, "b".repeat(64)), NOW);
    }
    private ServiceTaskObservation observed(ServiceTaskOperation operation, ServiceTaskObservation.Status status) {
        boolean terminal = status == ServiceTaskObservation.Status.APPLIED || status == ServiceTaskObservation.Status.REJECTED;
        return new ServiceTaskObservation(operation.input().command().id(), operation.input().command().digest(), status,
                terminal ? "receipt-1" : null, terminal ? operation.updatedAt() : null);
    }
}
