package io.agentflow.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentflow.common.JsonUtil;
import io.agentflow.common.DomainException;
import io.agentflow.expense.CostAllocation;
import io.agentflow.expense.FinanceJsonConfiguration;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 原协议没有柔性来源和人工凭据；通过真实 JSON 边界固定新增字段与历史摘要兼容。
 * @author owlzhangfq@gmail.com
 */
class BudgetExceptionProtocolTest {
    private static final UUID ORIGINAL = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID RETRY = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private final JsonUtil json = new JsonUtil(new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new FinanceJsonConfiguration().financeMoneyModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));

    @Test
    void historicalDocumentsKeepTheirShapeDigestAndRigidMeaning() {
        var command = original(); var encoded = json.write(command);
        assertThat(tree(command).size()).isEqualTo(5);
        assertThat(json.read(encoded, BudgetCommand.class)).isEqualTo(command);
        assertThat(command.digest()).isEqualTo("240aecd51a8aaa95bc378695ec620ceea69c4b7e8a7def36bd8d4b4a738fd82a");
        var assessment = new BudgetPrecheckPort.Assessment(command.position(), "precheck-1", NOW, NOW.plusSeconds(60));
        assertThat(tree(assessment).size()).isEqualTo(4);
        assertThat(json.read(json.write(assessment), BudgetPrecheckPort.Assessment.class)).isEqualTo(assessment);
        var observation = new BudgetObservation(command.id(), command.digest(), BudgetObservation.Status.REJECTED,
                null, null, null, BudgetObservation.Rejection.BUDGET_INSUFFICIENT);
        assertThat(tree(observation).size()).isEqualTo(7);
        assertThat(json.read(json.write(observation), BudgetObservation.class)).isEqualTo(observation);
    }

    @Test
    void explicitFlexiblePolicySurvivesPrecheckWithoutBecomingBudgetConfirmation() {
        var encoded = tree(new BudgetPrecheckPort.Assessment(original().position(), "precheck-1", NOW, NOW.plusSeconds(60)));
        encoded.putObject("exceptionPolicy").put("reference", "policy-flex-1");
        var decoded = json.read(encoded.toString(), BudgetPrecheckPort.Assessment.class);
        assertThat(tree(decoded)).isEqualTo(encoded);
        assertThat(decoded.request()).isEqualTo(original().position());
    }

    @Test
    void flexibleRefusalCarriesPolicyAndOfferButRemainsRejectedWithoutAnyLedger() {
        var encoded = tree(new BudgetObservation(ORIGINAL, original().digest(), BudgetObservation.Status.REJECTED,
                null, null, null, BudgetObservation.Rejection.BUDGET_INSUFFICIENT));
        encoded.put("rejection", "BUDGET_EXCEPTION_REQUIRED");
        encoded.putObject("exceptionOffer").put("policyReference", "policy-flex-1").put("reference", "offer-1");
        var decoded = json.read(encoded.toString(), BudgetObservation.class);
        assertThat(decoded.matches(original(), false, NOW)).isTrue();
        assertThat(decoded.status()).isEqualTo(BudgetObservation.Status.REJECTED);
        assertThat(decoded.ledgerRevision()).isNull();
        assertThat(tree(decoded)).isEqualTo(encoded);
    }

    @Test
    void approvalProofIsPreservedAndIncludedInNewCommandDigest() {
        var encoded = approvedCommand();
        var decoded = json.read(encoded.toString(), BudgetCommand.class);
        assertThat(tree(decoded)).isEqualTo(encoded);
        assertThat(decoded.position()).isEqualTo(original().position());
        // 独立 Python hashlib 按 v2 字段序与 UTF-8 长度复算，避免测试重复调用被测实现。
        assertThat(decoded.digest()).isEqualTo("1ab13a5e806f8635f3a5725b9c81f2c3eff6d17b199029780b76923f7b0980c9");
        assertThat(decoded.digest()).isNotEqualTo(new BudgetCommand(RETRY, "tenant-a", original().action(), original().position(), null).digest());
        var changedActor = encoded.deepCopy(); ((ObjectNode) changedActor.path("exceptionApproval")).put("actorId", "other-budget-owner");
        assertThat(json.read(changedActor.toString(), BudgetCommand.class).digest()).isNotEqualTo(decoded.digest());
    }

    @Test
    void incompleteProofOrChangedOriginalContextCannotSurviveJsonRecovery() {
        for (String field : List.of("originalOperationId", "originalCommandDigest", "targetDigest", "policyReference", "offerReference", "taskId", "actorId", "auditEventId", "approvedAt")) {
            var incomplete = approvedCommand(); ((ObjectNode) incomplete.path("exceptionApproval")).remove(field);
            assertThatThrownBy(() -> json.read(incomplete.toString(), BudgetCommand.class)).as(field).isInstanceOf(DomainException.class);
        }
        for (String field : List.of("roundNo", "financialVersion", "reportId", "employeeId", "legalEntityId", "accountingDate")) {
            var changed = approvedCommand(); var position = (ObjectNode) changed.path("position");
            switch (field) {
                case "roundNo", "financialVersion" -> position.put(field, 99);
                case "reportId", "legalEntityId" -> position.put(field, UUID.randomUUID().toString());
                case "accountingDate" -> position.put(field, "2026-10-05");
                default -> position.put(field, "bob");
            }
            assertThatThrownBy(() -> json.read(changed.toString(), BudgetCommand.class)).as(field).isInstanceOf(DomainException.class);
        }
        var changedCost = approvedCommand(); ((ObjectNode) changedCost.at("/position/allocations/0/cost")).put("costCenter", "OTHER");
        assertThatThrownBy(() -> json.read(changedCost.toString(), BudgetCommand.class)).isInstanceOf(DomainException.class);
        var changedAmount = approvedCommand(); ((ObjectNode) changedAmount.at("/position/allocations/0/cost/amount")).put("value", "101.00");
        assertThatThrownBy(() -> json.read(changedAmount.toString(), BudgetCommand.class)).isInstanceOf(DomainException.class);
    }

    private ObjectNode approvedCommand() {
        var encoded = tree(original()); encoded.put("id", RETRY.toString());
        encoded.putObject("exceptionApproval").put("originalOperationId", ORIGINAL.toString())
                .put("originalCommandDigest", original().digest()).put("targetDigest", "a".repeat(64))
                .put("policyReference", "policy-flex-1").put("offerReference", "offer-1")
                .put("taskId", "budget-task-1").put("actorId", "budget-owner")
                .put("auditEventId", "55555555-5555-4555-8555-555555555555").put("approvedAt", NOW.toString());
        return encoded;
    }

    private BudgetCommand original() {
        var position = new BudgetPrecheckPort.Request(UUID.fromString("22222222-2222-4222-8222-222222222222"), 1, 2, "alice",
                UUID.fromString("33333333-3333-4333-8333-333333333333"), "CNY", LocalDate.of(2026, 9, 28),
                List.of(new BudgetPrecheckPort.Allocation(1, 1, "TRAVEL", new CostAllocation("研发:中心", null, new Money(new BigDecimal("100"), "CNY")))));
        return new BudgetCommand(ORIGINAL, "tenant-a", BudgetCommand.Action.FREEZE, position, null);
    }

    private ObjectNode tree(Object value) { return (ObjectNode) json.read(json.write(value), JsonNode.class); }
}
