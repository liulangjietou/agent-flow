package io.agentflow.procurement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Arrays;
import java.util.Set;
import javax.sql.DataSource;

/**
 * 旧版本迁移夹具保留当时的队列 SQL；仅去掉 V126 新增的诊断字段，不能提前修改待升级表。 当前版本业务测试使用普通 JdbcTemplate，V126 升级另由固定包旧库验收。
 *
 * @author owlzhangfq@gmail.com
 */
final class SupplierMigrationJdbcTemplate extends JdbcTemplate {
    private static final Set<String> TABLES = Set.of(
            "supplier_adjustment_preparation", "supplier_payable_adjustment_operation",
            "supplier_payable_hold_operation", "supplier_payable_review", "supplier_payment_execution_request",
            "supplier_payment_return_check", "supplier_payment_operation", "supplier_settlement_preparation",
            "supplier_payable_settlement_operation");

    SupplierMigrationJdbcTemplate(DataSource source) { super(source); }

    @Override public int update(String sql, Object... args) {
        for (String table : TABLES) {
            String insert = "INSERT INTO " + table + "(trace_id,";
            if (sql.contains(insert)) {
                return super.update(sql.replace(insert, "INSERT INTO " + table + "(")
                        .replace("VALUES(?,", "VALUES("), Arrays.copyOfRange(args, 1, args.length));
            }
        }
        return super.update(sql, args);
    }

    @Override public <T> java.util.List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        for (String table : TABLES) {
            sql = sql.replace("SELECT tenant_id,id,trace_id FROM " + table,
                    "SELECT tenant_id,id,NULL AS trace_id FROM " + table);
            sql = sql.replace("SELECT o.tenant_id,o.id,o.trace_id FROM " + table,
                    "SELECT o.tenant_id,o.id,NULL AS trace_id FROM " + table);
            if (sql.contains("FROM " + table + " q") || sql.contains("FROM " + table + " o")) {
                sql = sql.replace("q.trace_id,", "NULL AS trace_id,").replace("o.trace_id,", "NULL AS trace_id,");
            }
        }
        return super.query(sql, mapper, args);
    }

    /** 旧表通过 XML 适配保留 V126 之前的列，不能修改待升级表。 */
    static <T> T mapper(JdbcTemplate jdbc, Class<T> type) {
        return io.agentflow.mybatis.MyBatisTestSupport.mapper(
                jdbc.getDataSource(),
                type,
                jdbc instanceof SupplierMigrationJdbcTemplate
                        ? SupplierMigrationJdbcTemplate::legacyXml
                        : java.util.function.UnaryOperator.identity());
    }

    private static String legacyXml(String xml) {
        var inserts =
                java.util.regex.Pattern.compile(
                                "<insert\\b[^>]*>.*?</insert>", java.util.regex.Pattern.DOTALL)
                        .matcher(xml);
        var rewritten = new StringBuffer();
        while (inserts.find()) {
            String statement = inserts.group();
            for (String table : TABLES) {
                String prefix = "INSERT INTO " + table + "(trace_id,";
                if (statement.contains(prefix)) {
                    statement =
                            statement
                                    .replace(prefix, "INSERT INTO " + table + "(")
                                    .replaceFirst("VALUES\\(\\s*#\\{[^}]+},", "VALUES(");
                    break;
                }
            }
            inserts.appendReplacement(
                    rewritten, java.util.regex.Matcher.quoteReplacement(statement));
        }
        inserts.appendTail(rewritten);
        xml = rewritten.toString();
        for (String table : TABLES) {
            xml =
                    xml.replace(
                                    "SELECT tenant_id,id,trace_id FROM " + table,
                                    "SELECT tenant_id,id,NULL AS trace_id FROM " + table)
                            .replace(
                                    "SELECT o.tenant_id,o.id,o.trace_id FROM " + table,
                                    "SELECT o.tenant_id,o.id,NULL AS trace_id FROM " + table);
            if (xml.contains("FROM " + table + " q") || xml.contains("FROM " + table + " o")) {
                xml =
                        xml.replace("q.trace_id,", "NULL AS trace_id,")
                                .replace("o.trace_id,", "NULL AS trace_id,");
            }
        }
        return xml;
    }
}
