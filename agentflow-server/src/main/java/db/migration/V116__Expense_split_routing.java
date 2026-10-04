package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * 从当前冻结轮次建立跨单查询投影，旧正文不改写；未记录的历史路由不补造已检查结论。
 * @author owlzhangfq@gmail.com
 */
public class V116__Expense_split_routing extends BaseJavaMigration {
    private static final int FETCH_SIZE = 128;

    /** 固定旧字段格式和迁移版本，不依赖后续领域类型变化。 */
    @Override public Integer getChecksum() { return 2026100401; }

    /** 损坏历史保持空投影，运行查询遇到有效但无投影的来源必须阻断，不能将其忽略。 */
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE expense_report ADD COLUMN current_round_no INTEGER");
            statement.execute("ALTER TABLE expense_report ADD COLUMN current_submitted_at TIMESTAMP WITH TIME ZONE");
            statement.execute("ALTER TABLE expense_report ADD COLUMN current_legal_entity_id VARCHAR(36)");
            statement.execute("ALTER TABLE expense_report ADD COLUMN current_base_currency VARCHAR(3)");
        }
        var json = new JsonUtil(new ObjectMapper());
        try (var select = connection.prepareStatement("SELECT tenant_id,id,application_id,employee_id,version,state_json FROM expense_report");
             var update = connection.prepareStatement("UPDATE expense_report SET current_round_no=?,current_submitted_at=?,current_legal_entity_id=?,current_base_currency=? WHERE tenant_id=? AND id=?")) {
            select.setFetchSize(FETCH_SIZE);
            try (var rows = select.executeQuery()) {
                while (rows.next()) {
                    Map<String, Object> state;
                    try { state = json.map(rows.getString("state_json")); }
                    catch (DomainException malformed) { continue; }
                    if (state == null || !rows.getString("tenant_id").equals(state.get("tenantId")) || !rows.getString("id").equals(state.get("id"))
                            || !rows.getString("application_id").equals(state.get("applicationId")) || !rows.getString("employee_id").equals(state.get("employeeId"))
                            || !String.valueOf(rows.getLong("version")).equals(String.valueOf(state.get("version")))
                            || !(state.get("rounds") instanceof List<?> rounds) || rounds.isEmpty()
                            || !(rounds.get(rounds.size() - 1) instanceof Map<?, ?> round)
                            || !String.valueOf(rounds.size()).equals(String.valueOf(round.get("roundNo")))
                            || !(round.get("submittedAt") instanceof String submitted)
                            || !(round.get("content") instanceof Map<?, ?> content) || !(content.get("legalEntityId") instanceof String legal)
                            || !canonicalUuid(legal) || !(round.get("baseCurrency") instanceof String currency) || !currency.matches("[A-Z]{3}")) continue;
                    Instant at;
                    try { at = Instant.parse(submitted).truncatedTo(ChronoUnit.MICROS); }
                    catch (java.time.DateTimeException invalid) { continue; }
                    update.setInt(1, rounds.size()); update.setTimestamp(2, Timestamp.from(at)); update.setString(3, legal); update.setString(4, currency);
                    update.setString(5, rows.getString("tenant_id")); update.setString(6, rows.getString("id")); update.executeUpdate();
                }
            }
        }
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    ALTER TABLE expense_report ADD CONSTRAINT ck_expense_current_round CHECK (
                        (current_round_no IS NULL AND current_submitted_at IS NULL AND current_legal_entity_id IS NULL AND current_base_currency IS NULL)
                        OR (current_round_no IS NOT NULL AND current_round_no>0 AND current_submitted_at IS NOT NULL
                            AND current_legal_entity_id IS NOT NULL AND current_base_currency IS NOT NULL))
                    """);
            statement.execute("CREATE INDEX idx_expense_split_scope ON expense_report(tenant_id,employee_id,current_legal_entity_id,current_base_currency,current_submitted_at,id)");
            statement.execute("""
                    CREATE TABLE expense_split_routing (
                        tenant_id VARCHAR(64) NOT NULL, report_id VARCHAR(36) NOT NULL, round_no INTEGER NOT NULL CHECK(round_no>0),
                        application_id VARCHAR(36) NOT NULL, application_version BIGINT NOT NULL CHECK(application_version>0),
                        financial_version BIGINT NOT NULL CHECK(financial_version>0), definition_id VARCHAR(36) NOT NULL,
                        process_key VARCHAR(128) NOT NULL, definition_version BIGINT NOT NULL CHECK(definition_version>0),
                        rule_version INTEGER NOT NULL CHECK(rule_version=1), mode VARCHAR(32) NOT NULL CHECK(mode IN ('UNCONFIGURED','DISABLED','ENABLED')),
                        submitted_at TIMESTAMP WITH TIME ZONE NOT NULL, snapshot_json TEXT NOT NULL,
                        PRIMARY KEY(tenant_id,report_id,round_no), CONSTRAINT uq_expense_split_application UNIQUE(tenant_id,application_id,round_no),
                        CONSTRAINT fk_expense_split_report FOREIGN KEY(tenant_id,report_id,application_id) REFERENCES expense_report(tenant_id,id,application_id),
                        CONSTRAINT fk_expense_split_revision FOREIGN KEY(tenant_id,report_id,financial_version) REFERENCES expense_report_revision(tenant_id,report_id,financial_version),
                        CONSTRAINT fk_expense_split_definition FOREIGN KEY(tenant_id,definition_id,process_key,definition_version) REFERENCES approval_definition(tenant_id,id,process_key,version)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE expense_split_routing_source (
                        tenant_id VARCHAR(64) NOT NULL, report_id VARCHAR(36) NOT NULL, round_no INTEGER NOT NULL,
                        ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 1 AND 999), source_report_id VARCHAR(36) NOT NULL,
                        source_application_id VARCHAR(36) NOT NULL, source_application_version BIGINT NOT NULL CHECK(source_application_version>0),
                        source_financial_version BIGINT NOT NULL CHECK(source_financial_version>0), source_round_no INTEGER NOT NULL CHECK(source_round_no>0),
                        document_json TEXT NOT NULL, PRIMARY KEY(tenant_id,report_id,round_no,ordinal),
                        CONSTRAINT uq_expense_split_source UNIQUE(tenant_id,report_id,round_no,source_report_id),
                        CONSTRAINT ck_expense_split_other_report CHECK(report_id<>source_report_id),
                        CONSTRAINT fk_expense_split_source_parent FOREIGN KEY(tenant_id,report_id,round_no) REFERENCES expense_split_routing(tenant_id,report_id,round_no),
                        CONSTRAINT fk_expense_split_source_report FOREIGN KEY(tenant_id,source_report_id,source_application_id) REFERENCES expense_report(tenant_id,id,application_id),
                        CONSTRAINT fk_expense_split_source_revision FOREIGN KEY(tenant_id,source_report_id,source_financial_version) REFERENCES expense_report_revision(tenant_id,report_id,financial_version),
                        CONSTRAINT fk_expense_split_source_round FOREIGN KEY(tenant_id,source_application_id,source_round_no) REFERENCES approval_submission_round(tenant_id,application_id,round_no)
                    )
                    """);
        }
    }

    private static boolean canonicalUuid(String value) {
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }
}
