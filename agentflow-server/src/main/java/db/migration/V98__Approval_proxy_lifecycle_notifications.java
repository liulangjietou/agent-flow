package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.util.ArrayList;
import java.util.Locale;

/**
 * 为重复发生的代理状态提醒保存原申请版本，旧待办及超时来源保持版本零和原唯一语义。
 * @author owlzhangfq@gmail.com
 */
public class V98__Approval_proxy_lifecycle_notifications extends BaseJavaMigration {
    /** 固定校验和保护已经发布的 Java 迁移。 */
    @Override public Integer getChecksum() { return 2026100101; }

    /** 只替换来源主键和类别限制，兼容两个数据库的自动约束名称并保留原外键。 */
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        var kinds = new ArrayList<String>(); var primary = new ArrayList<String>(); var foreign = new ArrayList<String>();
        try (var select = connection.prepareStatement("""
                SELECT t.constraint_name,t.constraint_type,c.check_clause FROM information_schema.table_constraints t
                LEFT JOIN information_schema.check_constraints c ON c.constraint_catalog=t.constraint_catalog
                    AND c.constraint_schema=t.constraint_schema AND c.constraint_name=t.constraint_name
                WHERE t.table_schema=CURRENT_SCHEMA AND LOWER(t.table_name)='approval_proxy_notification'
                """)) {
            try (var rows = select.executeQuery()) {
                while (rows.next()) {
                    String type = rows.getString("constraint_type"), name = rows.getString("constraint_name");
                    if ("PRIMARY KEY".equals(type)) primary.add(name);
                    if ("FOREIGN KEY".equals(type)) foreign.add(name);
                    String clause = rows.getString("check_clause");
                    if (clause == null) continue;
                    clause = clause.toUpperCase(Locale.ROOT);
                    if (!clause.replaceAll("[\\s\"()]", "").equals("KINDISNOTNULL") && clause.matches("(?s).*\\bKIND\\b.*")) kinds.add(name);
                }
            }
        }
        if (primary.size() != 1 || kinds.size() != 1 || foreign.size() != 2) throw new IllegalStateException("Unexpected historical proxy notification constraints");
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE approval_proxy_notification ADD COLUMN event_version BIGINT NOT NULL DEFAULT 0");
            // H2 的外键可能复用旧主键索引，先解除后按原语义恢复；消息唯一约束保持。
            var replacing = new ArrayList<>(foreign); replacing.addAll(primary); replacing.addAll(kinds);
            for (String name : replacing) statement.execute("ALTER TABLE approval_proxy_notification DROP CONSTRAINT \"" + name.replace("\"", "\"\"") + "\"");
            statement.execute("ALTER TABLE approval_proxy_notification ADD CONSTRAINT pk_proxy_notification_v98 PRIMARY KEY (tenant_id,proxy_id,task_id,kind,event_version)");
            statement.execute("ALTER TABLE approval_proxy_notification ADD CONSTRAINT fk_proxy_notification_proxy_v98 FOREIGN KEY (tenant_id,proxy_id) REFERENCES organization_approval_proxy(tenant_id,id)");
            statement.execute("ALTER TABLE approval_proxy_notification ADD CONSTRAINT fk_proxy_notification_inbox_v98 FOREIGN KEY (tenant_id,inbox_id) REFERENCES notification_inbox(tenant_id,id)");
            statement.execute("""
                    ALTER TABLE approval_proxy_notification ADD CONSTRAINT ck_proxy_notification_kind_v98 CHECK (
                        (event_version=0 AND kind IN ('TASK_PENDING','TASK_OVERDUE')) OR
                        (event_version>0 AND kind IN ('APPLICATION_WITHDRAWN','APPLICATION_CANCELLED','APPLICATION_RETURNED',
                            'APPLICATION_REJECTED','APPLICATION_PAUSED','APPLICATION_RESUMED','TASK_COUNTERSIGN_REMOVED','TASK_COUNTERSIGN_COMPLETED')))
                    """);
        }
    }
}
