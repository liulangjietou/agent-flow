package io.agentflow.expense;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.agentflow.common.JsonUtil;
import io.agentflow.finance.AdvanceDisbursementReturnPort;
import io.agentflow.finance.ExpensePaymentReturnPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * 用固定纳秒原件验证数据库微秒投影，不依赖操作系统时钟精度。
 *
 * @author owlzhangfq@gmail.com
 */
class JdbcReturnHistoryPrecisionTest {
    private static final String TENANT = "precision-fixture";
    private static final String EXPENSE_JSON = "expense-frozen-fact";
    private static final String ADVANCE_JSON = "advance-frozen-fact";
    private final UUID businessId = UUID.randomUUID();
    private final JsonUtil json = mock(JsonUtil.class);
    private JdbcTemplate jdbc;

    @BeforeEach
    void database() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute(
                """
CREATE TABLE expense_payment_return_registration (
    tenant_id VARCHAR, id VARCHAR, report_id VARCHAR, return_version BIGINT,
    check_id VARCHAR, outcome VARCHAR, registered_by VARCHAR,
    observed_at TIMESTAMP(6) WITH TIME ZONE, registered_at TIMESTAMP(6) WITH TIME ZONE,
    state_json VARCHAR)
""");
        jdbc.execute(
                """
CREATE TABLE advance_disbursement_resolution (
    tenant_id VARCHAR, id VARCHAR, advance_id VARCHAR, advance_version BIGINT,
    check_id VARCHAR, outcome VARCHAR, resolved_by VARCHAR,
    observed_at TIMESTAMP(6) WITH TIME ZONE, resolved_at TIMESTAMP(6) WITH TIME ZONE,
    state_json VARCHAR)
""");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-03T04:00:00.123456123Z", "2026-10-03T04:00:00.123456789Z", "2026-10-03T04:00:00.999999789Z"})
    void expenseHistoryRetainsFrozenNanosecondsAcrossDatabaseRounding(String timestamp) {
        Instant observed = Instant.parse(timestamp);
        var value = expense(observed);
        assertThat(expenses().history(TENANT, businessId)).containsExactly(value);
        assertThat(value.receipt().observedAt()).isEqualTo(observed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-03T04:00:00.123456123Z", "2026-10-03T04:00:00.123456789Z", "2026-10-03T04:00:00.999999789Z"})
    void disbursementHistoryRetainsFrozenNanosecondsAcrossDatabaseRounding(String timestamp) {
        Instant observed = Instant.parse(timestamp);
        var value = disbursement(observed);
        assertThat(disbursements().latest(TENANT, businessId)).contains(value);
        assertThat(value.receipt().observedAt()).isEqualTo(observed);
    }

    @Test
    void expenseHistoryStillRejectsChangedTimestampMetadata() {
        var value = expense(Instant.parse("2026-10-03T04:00:00.123456Z"));
        jdbc.update("UPDATE expense_payment_return_registration SET registered_at=?",
                Timestamp.from(value.registeredAt().plusNanos(2_000)));
        assertThatThrownBy(() -> expenses().history(TENANT, businessId)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Persisted expense return registration identity is inconsistent");
    }

    @Test
    void disbursementHistoryStillRejectsChangedTimestampMetadata() {
        var value = disbursement(Instant.parse("2026-10-03T04:00:00.123456Z"));
        jdbc.update("UPDATE advance_disbursement_resolution SET observed_at=?",
                Timestamp.from(value.receipt().observedAt().plusNanos(2_000)));
        assertThatThrownBy(() -> disbursements().latest(TENANT, businessId)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Persisted disbursement resolution identity is inconsistent");
    }

    private ExpensePaymentReturn expense(Instant observed) {
        var value = mock(ExpensePaymentReturn.class, RETURNS_DEEP_STUBS);
        UUID id = UUID.randomUUID(), checkId = UUID.randomUUID();
        when(value.tenantId()).thenReturn(TENANT);
        when(value.id()).thenReturn(id);
        when(value.checkId()).thenReturn(checkId);
        when(value.receipt().request().command().binding().businessId()).thenReturn(businessId);
        when(value.receipt().status()).thenReturn(ExpensePaymentReturnPort.Status.CONFIRMED);
        when(value.receipt().observedAt()).thenReturn(observed);
        when(value.registeredAt()).thenReturn(observed.plusSeconds(1));
        when(value.registeredBy()).thenReturn("finance-reviewer");
        when(json.read(EXPENSE_JSON, ExpensePaymentReturn.class)).thenReturn(value);
        jdbc.update("INSERT INTO expense_payment_return_registration VALUES(?,?,?,?,?,?,?,?,?,?)",
                TENANT, id.toString(), businessId.toString(), 1, checkId.toString(), "CONFIRMED", "finance-reviewer",
                Timestamp.from(observed), Timestamp.from(value.registeredAt()), EXPENSE_JSON);
        return value;
    }

    private AdvanceDisbursementReturn disbursement(Instant observed) {
        var value = mock(AdvanceDisbursementReturn.class, RETURNS_DEEP_STUBS);
        UUID id = UUID.randomUUID(), checkId = UUID.randomUUID();
        when(value.tenantId()).thenReturn(TENANT);
        when(value.id()).thenReturn(id);
        when(value.checkId()).thenReturn(checkId);
        when(value.receipt().request().command().binding().businessId()).thenReturn(businessId);
        when(value.receipt().status()).thenReturn(AdvanceDisbursementReturnPort.Status.CONFIRMED);
        when(value.receipt().observedAt()).thenReturn(observed);
        when(value.resolvedAt()).thenReturn(observed.plusSeconds(1));
        when(value.resolvedBy()).thenReturn("finance-reviewer");
        when(json.read(ADVANCE_JSON, AdvanceDisbursementReturn.class)).thenReturn(value);
        jdbc.update("INSERT INTO advance_disbursement_resolution VALUES(?,?,?,?,?,?,?,?,?,?)",
                TENANT, id.toString(), businessId.toString(), 1, checkId.toString(), "CONFIRMED", "finance-reviewer",
                Timestamp.from(observed), Timestamp.from(value.resolvedAt()), ADVANCE_JSON);
        return value;
    }

    private JdbcExpensePaymentReturnRepository expenses() {
        return new JdbcExpensePaymentReturnRepository(
                io.agentflow.mybatis.MyBatisTestSupport.mapper(
                        (jdbc).getDataSource(),
                        io.agentflow.expense.mapper.ExpensePaymentReturnRepositoryMapper.class), json, mock(JdbcExpensePaymentReturnCheckRepository.class),
                mock(JdbcExpensePaymentReturnsRepository.class), mock(JdbcExpenseSettlementRepository.class),
                mock(io.agentflow.finance.JdbcFinanceReceiptCreditRepository.class));
    }

    private JdbcDisbursementResolutionRepository disbursements() {
        return new JdbcDisbursementResolutionRepository(
                io.agentflow.mybatis.MyBatisTestSupport.mapper(
                        (jdbc).getDataSource(),
                        io.agentflow.expense.mapper.DisbursementResolutionRepositoryMapper.class), json, mock(JdbcDisbursementReturnCheckRepository.class),
                mock(JdbcAdvanceReceiptCreditRepository.class));
    }
}
