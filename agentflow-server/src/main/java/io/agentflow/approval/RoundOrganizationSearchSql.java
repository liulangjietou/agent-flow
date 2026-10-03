package io.agentflow.approval;

import java.util.List;
import java.util.Locale;

/**
 * 待办与运营共用轮次组织名称谓词，固定使用已由调用方授权关联的 r 别名。
 * @author owlzhangfq@gmail.com
 */
public final class RoundOrganizationSearchSql {
    private RoundOrganizationSearchSql() { }

    /** 追加名称包含匹配；输入已在入口校验，值全部参数化，通配符按字面量处理。 */
    public static void append(StringBuilder sql, List<Object> parameters, String organization) {
        if (organization.isEmpty()) return;
        String pattern = "%" + organization.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        sql.append(" AND (LOWER(r.initiator_legal_entity_name) LIKE ? ESCAPE '!' OR LOWER(r.initiator_department_name) LIKE ? ESCAPE '!' OR LOWER(r.initiator_position_name) LIKE ? ESCAPE '!')");
        parameters.addAll(List.of(pattern, pattern, pattern));
    }
}
