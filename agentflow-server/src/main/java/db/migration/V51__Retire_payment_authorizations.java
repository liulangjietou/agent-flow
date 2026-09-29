package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Locale;

/**
 * 原授权第三版安全结束并释放业务占用，结束事实引用保留的授权及执行修订。
 * @author owlzhangfq@gmail.com
 */
public class V51__Retire_payment_authorizations extends BaseJavaMigration {
    /** 同时适配 H2 与 PostgreSQL 为旧内联检查约束生成的不同名称。 */
    @Override public Integer getChecksum() { return 2026092901; }

    /** 只替换授权状态及版本检查，不改写历史快照、业务外键或资金状态。 */
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        replaceChecks(connection, "payment_authorization", 4);
        replaceChecks(connection, "payment_authorization_revision", 1);
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE payment_authorization ADD CONSTRAINT ck_payment_authorization_state_v51 CHECK (status IN ('AUTHORIZED','EXECUTION_REGISTERED','VOIDED','EXPIRED','RETIRED'))");
            statement.execute("""
                    ALTER TABLE payment_authorization ADD CONSTRAINT ck_payment_authorization_version_v51 CHECK (
                    (status='AUTHORIZED' AND version=1) OR (status IN ('EXECUTION_REGISTERED','VOIDED','EXPIRED') AND version=2) OR (status='RETIRED' AND version=3))
                    """);
            statement.execute("""
                    ALTER TABLE payment_authorization ADD CONSTRAINT ck_payment_authorization_active_v51 CHECK (
                    (status IN ('AUTHORIZED','EXECUTION_REGISTERED') AND active_business_id IS NOT NULL AND active_business_id=business_id)
                    OR (status IN ('VOIDED','EXPIRED','RETIRED') AND active_business_id IS NULL))
                    """);
            statement.execute("ALTER TABLE payment_authorization_revision ADD CONSTRAINT ck_payment_authorization_revision_v51 CHECK (version IN (1,2,3))");
            statement.execute("""
                    CREATE TABLE payment_retirement (
                        tenant_id VARCHAR(64) NOT NULL,
                        authorization_id VARCHAR(36) NOT NULL,
                        authorization_version BIGINT NOT NULL CHECK (authorization_version=3),
                        operation_version BIGINT NOT NULL CHECK (operation_version>0),
                        basis VARCHAR(24) NOT NULL CHECK (basis IN ('NEVER_DISPATCHED','CONFIRMED_FAILED')),
                        retired_by VARCHAR(128) NOT NULL,
                        retired_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        retirement_json TEXT NOT NULL,
                        PRIMARY KEY (tenant_id,authorization_id),
                        CONSTRAINT fk_payment_retirement_authorization FOREIGN KEY (tenant_id,authorization_id,authorization_version)
                            REFERENCES payment_authorization_revision(tenant_id,authorization_id,version),
                        CONSTRAINT fk_payment_retirement_operation FOREIGN KEY (tenant_id,authorization_id,operation_version)
                            REFERENCES payment_operation_revision(tenant_id,operation_id,version))
                    """);
        }
    }

    private static void replaceChecks(Connection connection, String table, int expected) throws Exception {
        var names = new ArrayList<String>();
        try (var select = connection.prepareStatement("""
                SELECT t.constraint_name,c.check_clause FROM information_schema.table_constraints t
                JOIN information_schema.check_constraints c ON c.constraint_catalog=t.constraint_catalog
                    AND c.constraint_schema=t.constraint_schema AND c.constraint_name=t.constraint_name
                WHERE t.table_schema=CURRENT_SCHEMA AND LOWER(t.table_name)=? AND t.constraint_type='CHECK'
                """)) {
            select.setString(1, table);
            try (var rows = select.executeQuery()) {
                while (rows.next()) {
                    String clause = rows.getString("check_clause").toUpperCase(Locale.ROOT);
                    // PostgreSQL 的信息模式也列出非空检查，不能将其当成需替换的取值范围。
                    String compact = clause.replaceAll("[\\s\"()]", "");
                    if (compact.equals("VERSIONISNOTNULL") || compact.equals("STATUSISNOTNULL")) continue;
                    if (clause.matches("(?s).*\\bVERSION\\b.*") || clause.contains("STATUS") || clause.contains("ACTIVE_BUSINESS_ID")) names.add(rows.getString("constraint_name"));
                }
            }
        }
        if (names.size() != expected) throw new IllegalStateException("Unexpected historical payment authorization constraints: " + table);
        try (var statement = connection.createStatement()) {
            for (String name : names) statement.execute("ALTER TABLE " + table + " DROP CONSTRAINT \"" + name.replace("\"", "\"\"") + "\"");
        }
    }
}
