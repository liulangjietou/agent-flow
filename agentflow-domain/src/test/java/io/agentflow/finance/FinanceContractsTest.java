package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseLine;
import io.agentflow.expense.InvoiceVerificationPort;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 财务外部事实的不可变边界和完整账户信息泄漏回归。
 * @author owlzhangfq@gmail.com
 */
class FinanceContractsTest {
    private static final UUID ENTITY = UUID.randomUUID();

    @Test
    void rejectsCompleteAccountNumbersDisguisedAsDisplayMasks() {
        for (String mask : List.of("6222021234567890123", "****6222021234567890123", "6222 0212 3456 7890 123", "card 1234")) {
            assertThatThrownBy(() -> new EmployeeAccountSnapshot(ENTITY, "alice", "account-1", mask, "a".repeat(64), "v1"))
                    .isInstanceOf(DomainException.class).hasMessage("A versioned employee account reference and digest are required");
        }
        for (String mask : List.of("****1234", "6222 **** **** 1234", "••••1234", "XXXX 1234")) {
            assertThat(new EmployeeAccountSnapshot(ENTITY, "alice", "account-1", mask, "a".repeat(64), "v1").maskedAccount()).isEqualTo(mask);
        }
    }

    @Test
    void catalogRejectsDuplicateAndOrphanCostObjectsAndCopiesInputCollections() {
        var entity = new FinanceCatalog.LegalEntity(ENTITY, "测试法人", "CNY", true, "v1", "Asia/Shanghai");
        var center = new FinanceCatalog.CostCenter(ENTITY, "IT", "研发中心");
        var catalog = catalog(List.of(entity), List.of(center));
        assertThat(catalog.legalEntity(ENTITY)).isEqualTo(entity);
        assertThatThrownBy(() -> catalog.legalEntities().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog(List.of(entity, entity), List.of(center))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> catalog(List.of(entity), List.of(center, center))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> catalog(List.of(), List.of(center))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> catalog.legalEntity(UUID.randomUUID())).isInstanceOf(DomainException.class);
    }

    @Test
    void originalBytesAreVerifiedAndCannotChangeAfterConstruction() throws Exception {
        byte[] bytes = "%PDF-synthetic-original".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var request = new InvoiceVerificationPort.Request("alice", ENTITY, UUID.randomUUID(), digest, "application/pdf", bytes);
        bytes[0] = 1; request.original()[0] = 2;
        assertThat(request.original()[0]).isEqualTo((byte) '%');
        assertThat(request.toString()).doesNotContain("alice", digest, "synthetic");
        assertThatThrownBy(() -> new InvoiceVerificationPort.Request("alice", ENTITY, UUID.randomUUID(), digest, "application/pdf", bytes))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new InvoiceVerificationPort.Request("alice", ENTITY, UUID.randomUUID(), digest, "text/html", request.original()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void failuresDoNotBecomeEmptySuccessfulResults() {
        assertThat(new FinanceResult.Success<>("real-data").requireValue()).isEqualTo("real-data");
        assertThatThrownBy(() -> new FinanceResult.Unavailable<>(FinanceResult.Failure.NOT_CONFIGURED).requireValue())
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("FINANCE_GATEWAY_UNAVAILABLE"));
        assertThatThrownBy(() -> new FinanceResult.Rejected<>(FinanceResult.Reason.INVOICE_INVALID).requireValue())
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("FINANCE_RULE_REJECTED"));
    }

    private FinanceCatalog catalog(List<FinanceCatalog.LegalEntity> entities, List<FinanceCatalog.CostCenter> centers) {
        return new FinanceCatalog("alice", "v1", Instant.now().plusSeconds(60), entities,
                List.of(new FinanceCatalog.Category("TRAVEL", "差旅", List.of(ExpenseLine.Unit.DAY))), centers, List.of(), List.of());
    }
}
