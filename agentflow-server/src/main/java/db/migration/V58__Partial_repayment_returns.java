package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.util.ArrayList;
import java.util.Locale;

/**
 * 原还款可以有多笔独立退回，历史单笔记录保留且资金、分录唯一约束继续生效。
 * @author owlzhangfq@gmail.com
 */
public class V58__Partial_repayment_returns extends BaseJavaMigration {
    /** 已发布 Java 迁移使用固定校验和。 */
    @Override public Integer getChecksum() { return 2026092908; }

    /** 只放开每笔还款及每次决定的一条记录限制，不重写历史原文。 */
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        var outcomes = new ArrayList<String>(); var primaryKeys = new ArrayList<String>();
        try (var select = connection.prepareStatement("""
                SELECT t.table_name,t.constraint_name,t.constraint_type,c.check_clause FROM information_schema.table_constraints t
                LEFT JOIN information_schema.check_constraints c ON c.constraint_catalog=t.constraint_catalog
                    AND c.constraint_schema=t.constraint_schema AND c.constraint_name=t.constraint_name
                WHERE t.table_schema=CURRENT_SCHEMA AND LOWER(t.table_name) IN ('advance_repayment_resolution','advance_repayment_return')
                """)) {
            try (var rows = select.executeQuery()) {
                while (rows.next()) {
                    if ("advance_repayment_return".equalsIgnoreCase(rows.getString("table_name")) && "PRIMARY KEY".equals(rows.getString("constraint_type"))) primaryKeys.add(rows.getString("constraint_name"));
                    String clause = rows.getString("check_clause");
                    if (clause == null || !"advance_repayment_resolution".equalsIgnoreCase(rows.getString("table_name"))) continue;
                    clause = clause.toUpperCase(Locale.ROOT);
                    if (!clause.replaceAll("[\\s\"()]", "").equals("OUTCOMEISNOTNULL") && clause.matches("(?s).*\\bOUTCOME\\b.*")) outcomes.add(rows.getString("constraint_name"));
                }
            }
        }
        if (outcomes.size() != 1 || primaryKeys.size() != 1) throw new IllegalStateException("Unexpected historical repayment return constraints");
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE advance_repayment_resolution DROP CONSTRAINT \"" + outcomes.get(0).replace("\"", "\"\"") + "\"");
            statement.execute("ALTER TABLE advance_repayment_resolution ALTER COLUMN outcome SET DATA TYPE VARCHAR(24)");
            statement.execute("ALTER TABLE advance_repayment_resolution ADD CONSTRAINT ck_repayment_resolution_outcome_v58 CHECK (outcome IN ('CONFIRMED','PARTIALLY_RETURNED','RETURNED'))");
            // H2 的外键复用原主键/唯一索引，先解除复用再按相同定义恢复，避免残留旧主键索引。
            statement.execute("ALTER TABLE advance_repayment_return DROP CONSTRAINT fk_repayment_return_original");
            statement.execute("ALTER TABLE advance_repayment_return DROP CONSTRAINT fk_repayment_return_decision");
            statement.execute("ALTER TABLE advance_repayment_return DROP CONSTRAINT \"" + primaryKeys.get(0).replace("\"", "\"\"") + "\"");
            statement.execute("ALTER TABLE advance_repayment_return DROP CONSTRAINT uq_repayment_return_decision");
            statement.execute("ALTER TABLE advance_repayment_return ADD CONSTRAINT pk_repayment_return_v58 PRIMARY KEY (tenant_id,repayment_id,channel,transaction_reference)");
            statement.execute("CREATE INDEX idx_repayment_return_decision ON advance_repayment_return(tenant_id,resolution_id)");
            statement.execute("ALTER TABLE advance_repayment_return ADD CONSTRAINT fk_repayment_return_original FOREIGN KEY (tenant_id,repayment_id) REFERENCES advance_repayment(tenant_id,id)");
            statement.execute("ALTER TABLE advance_repayment_return ADD CONSTRAINT fk_repayment_return_decision FOREIGN KEY (tenant_id,resolution_id) REFERENCES advance_repayment_resolution(tenant_id,id)");
        }
    }
}
