package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.util.ArrayList;
import java.util.Locale;

/**
 * 扩大原件格式白名单，保留已有原件、配额、归属外键和恢复清单。
 * @author owlzhangfq@gmail.com
 */
public class V57__Accept_xml_invoice_originals extends BaseJavaMigration {
    /** 固定校验和使已发布 Java 迁移也受到历史变更检查。 */
    @Override public Integer getChecksum() { return 2026092907; }

    /** 兼容两种数据库的内联约束名称，仅替换格式检查。 */
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        var names = new ArrayList<String>();
        try (var select = connection.prepareStatement("""
                SELECT t.constraint_name,c.check_clause FROM information_schema.table_constraints t
                JOIN information_schema.check_constraints c ON c.constraint_catalog=t.constraint_catalog
                    AND c.constraint_schema=t.constraint_schema AND c.constraint_name=t.constraint_name
                WHERE t.table_schema=CURRENT_SCHEMA AND LOWER(t.table_name)='invoice_original' AND t.constraint_type='CHECK'
                """)) {
            try (var rows = select.executeQuery()) {
                while (rows.next()) {
                    String clause = rows.getString("check_clause").toUpperCase(Locale.ROOT);
                    // PostgreSQL 信息模式中的非空检查必须保留。
                    if (clause.replaceAll("[\\s\"()]", "").equals("FORMATISNOTNULL")) continue;
                    if (clause.matches("(?s).*\\bFORMAT\\b.*")) names.add(rows.getString("constraint_name"));
                }
            }
        }
        if (names.size() != 1) throw new IllegalStateException("Unexpected historical invoice original format constraints");
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE invoice_original DROP CONSTRAINT \"" + names.get(0).replace("\"", "\"\"") + "\"");
            statement.execute("ALTER TABLE invoice_original ADD CONSTRAINT ck_invoice_original_format_v57 CHECK (format IN ('PDF','OFD','PNG','JPEG','XML'))");
        }
    }
}
