package io.agentflow.organization;

import io.agentflow.approval.service.TaskRecipientDirectory;
import io.agentflow.auth.AuthService;
import io.agentflow.definition.DefinitionAssigneeDirectory;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 将本地组织适配到既有选人和收件人端口；显式初始化后空目录不会回退为演示人员。
 * @author owlzhangfq@gmail.com
 */
@Component
@Primary
public class LocalOrganizationDirectory implements TaskRecipientDirectory, DefinitionAssigneeDirectory {
    public static final String PERSON_ROLE = "ORG_PERSON_";
    public static final String UNIT_ROLE = "ORG_UNIT_";
    public static final String SUPERVISOR_RULE = "role:ORG_SUPERVISOR_";
    public static final String DEPARTMENT_HEAD_RULE = "role:ORG_DEPARTMENT_HEAD";
    public static final int MAX_SUPERVISOR_LEVEL = 10;
    private static final String ACTIVE_APPOINTMENTS = """
            FROM organization_appointment a
            JOIN organization_person p ON p.tenant_id=a.tenant_id AND p.id=a.person_id AND p.active=TRUE
            JOIN organization_unit d ON d.tenant_id=a.tenant_id AND d.id=a.department_id AND d.active=TRUE
            JOIN organization_unit j ON j.tenant_id=a.tenant_id AND j.id=a.position_id AND j.active=TRUE
            JOIN organization_unit l ON l.tenant_id=d.tenant_id AND l.id=d.legal_entity_id AND l.active=TRUE
            WHERE a.tenant_id=? AND a.active=TRUE
            """;
    private final OrganizationRepository repository;
    private final JdbcTemplate jdbc;
    private final AuthService demo;

    /** 演示源作为明确的兼容分支，企业身份与组织来源彼此独立。 */
    public LocalOrganizationDirectory(OrganizationRepository repository, JdbcTemplate jdbc, AuthService demo) {
        this.repository = repository; this.jdbc = jdbc; this.demo = demo;
    }

    /** 本地规则只引用不透明实体标识，因此 OIDC 主体中的冒号等字符不会进入表达式。 */
    public static boolean isLocalRule(String rule) {
        return rule != null && (rule.startsWith("role:" + PERSON_ROLE) || rule.startsWith("role:" + UNIT_ROLE) || isContextualRule(rule));
    }

    /** 动态规则必须从本轮任职解析，不属于身份源静态角色。 */
    public static boolean isContextualRule(String rule) {
        return rule != null && (rule.startsWith(SUPERVISOR_RULE) || DEPARTMENT_HEAD_RULE.equals(rule));
    }

    /** 本地资格只约束审批办理；不改写认证角色，也不影响原幂等请求的身份指纹。 */
    @Override
    public boolean eligible(String tenantId, String userId) {
        return !repository.initialized(tenantId) || repository.personBySubject(tenantId, userId)
                .filter(OrganizationPerson::canApprove).isPresent();
    }

    @Override
    public List<String> approvers(String tenantId) {
        if (!repository.initialized(tenantId)) return demo.approvers(tenantId);
        return jdbc.queryForList("SELECT subject FROM organization_person WHERE tenant_id=? AND active=TRUE AND approval_eligible=TRUE ORDER BY subject", String.class, tenantId);
    }

    @Override
    public List<String> members(String tenantId, Set<String> users, Set<String> roles) {
        if (!repository.initialized(tenantId)) return demo.members(tenantId, users, roles);
        var selected = new HashSet<String>();
        for (String role : roles) selected.addAll(roleMembers(tenantId, role));
        // 指派和已冻结候选人仍按当前人员启停及审批资格过滤，不用新任职覆盖旧责任。
        selected.addAll(users);
        return approvers(tenantId).stream().filter(selected::contains).toList();
    }

    /** 只解析本地命名空间；身份源系统角色不会被本地人员编辑变成组织组。 */
    public List<String> roleMembers(String tenantId, String role) {
        return roleMembers(tenantId, role, true);
    }

    private List<String> roleMembers(String tenantId, String role, boolean approvalRequired) {
        if (role.startsWith(PERSON_ROLE)) {
            var id = localId(role, PERSON_ROLE);
            return repository.person(tenantId, id).filter(person -> person.active() && (!approvalRequired || person.approvalEligible()))
                    .map(person -> List.of(person.subject())).orElseGet(List::of);
        }
        if (role.startsWith(UNIT_ROLE)) {
            String id = localId(role, UNIT_ROLE).toString();
            return jdbc.queryForList("SELECT DISTINCT p.subject " + appointments(approvalRequired) + " AND (a.department_id=? OR a.position_id=?) ORDER BY p.subject", String.class, tenantId, id, id);
        }
        return List.of();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Option> options(String tenantId) {
        return options(tenantId, true);
    }

    /** 抄送只要求同租户人员有效，不复用审批资格作为读取许可。 */
    @Override
    @Transactional(readOnly = true)
    public List<Option> copyOptions(String tenantId) { return options(tenantId, false); }

    /** 表单目录只返回本租户在用对象的标识、名称及当前可选关系，不返回认证主体。 */
    @Override
    @Transactional(readOnly = true)
    public List<FormOption> formOptions(String tenantId) {
        if (!repository.initialized(tenantId)) return List.of();
        var result = new ArrayList<FormOption>();
        jdbc.query("SELECT id,display_name FROM organization_person WHERE tenant_id=? AND active=TRUE AND approval_eligible=TRUE ORDER BY display_name,id",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> result.add(new FormOption(UUID.fromString(row.getString("id")),
                        row.getString("display_name"), io.agentflow.definition.FormAssigneePolicy.SourceKind.PERSON, 1, false)), tenantId);
        var counts = memberCounts(tenantId, true);
        jdbc.query("""
                SELECT u.id,u.name,u.kind,
                       CASE WHEN a.active=TRUE AND a.department_id=u.id AND p.active=TRUE AND p.approval_eligible=TRUE
                                      AND j.active=TRUE THEN TRUE ELSE FALSE END AS head_available
                FROM organization_unit u
                JOIN organization_unit l ON l.tenant_id=u.tenant_id AND l.id=u.legal_entity_id AND l.active=TRUE
                LEFT JOIN organization_appointment a ON a.tenant_id=u.tenant_id AND a.id=u.head_appointment_id
                LEFT JOIN organization_person p ON p.tenant_id=a.tenant_id AND p.id=a.person_id
                LEFT JOIN organization_unit j ON j.tenant_id=a.tenant_id AND j.id=a.position_id
                WHERE u.tenant_id=? AND u.active=TRUE AND u.kind IN ('DEPARTMENT','POSITION') ORDER BY u.kind,u.name,u.id
                """, (org.springframework.jdbc.core.RowCallbackHandler) row -> result.add(new FormOption(UUID.fromString(row.getString("id")),
                        row.getString("name"), io.agentflow.definition.FormAssigneePolicy.SourceKind.valueOf(row.getString("kind")),
                        counts.getOrDefault(row.getString("id"), 0), row.getBoolean("head_available"))), tenantId);
        return List.copyOf(result);
    }

    /** 每次读取抄送快照重新核对当前人员启停状态，已冻结记录不受组织改组影响。 */
    public boolean activeRecipient(String tenantId, String subject) {
        return repository.initialized(tenantId) ? repository.personBySubject(tenantId, subject).filter(OrganizationPerson::active).isPresent()
                : demo.approvers(tenantId).contains(subject);
    }

    /** 静态抄送规则只在当前租户目录内展开，动态任职规则由关系解析服务处理。 */
    public List<String> copyMembers(String tenantId, String rule) {
        if (!repository.initialized(tenantId)) {
            return rule.startsWith("user:") ? demo.members(tenantId, Set.of(rule.substring(5)), Set.of())
                    : demo.members(tenantId, Set.of(), Set.of(rule.substring(5)));
        }
        return rule.startsWith("role:") ? roleMembers(tenantId, rule.substring(5), false) : List.of();
    }

    private List<Option> options(String tenantId, boolean approvalRequired) {
        if (!repository.initialized(tenantId)) return demo.options(tenantId);
        var result = new ArrayList<Option>();
        result.add(new Option(DEPARTMENT_HEAD_RULE, "本次任职部门负责人", 0, true));
        for (int level = 1; level <= MAX_SUPERVISOR_LEVEL; level++) {
            result.add(new Option(SUPERVISOR_RULE + level, "本次任职 · 第 " + level + " 级主管", 0, true));
        }
        jdbc.query("SELECT id,display_name FROM organization_person WHERE tenant_id=? AND active=TRUE" + (approvalRequired ? " AND approval_eligible=TRUE" : "") + " ORDER BY display_name,id",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> result.add(new Option("role:" + PERSON_ROLE + row.getString("id"), "人员 · " + row.getString("display_name"), 1)), tenantId);
        var counts = memberCounts(tenantId, approvalRequired);
        jdbc.query("SELECT id,name,kind FROM organization_unit WHERE tenant_id=? AND kind IN ('DEPARTMENT','POSITION') AND active=TRUE ORDER BY kind,name,id",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                    int count = counts.getOrDefault(row.getString("id"), 0);
                    if (count > 0) result.add(new Option("role:" + UNIT_ROLE + row.getString("id"),
                            ("DEPARTMENT".equals(row.getString("kind")) ? "部门 · " : "岗位 · ") + row.getString("name"), count));
                }, tenantId);
        return List.copyOf(result);
    }

    private Map<String, Integer> memberCounts(String tenantId, boolean approvalRequired) {
        var counts = new HashMap<String, Integer>();
        for (String column : List.of("department_id", "position_id")) {
            // 列名是服务端固定白名单，不来自 URL 或客户端输入。
            jdbc.query("SELECT a." + column + " AS id,COUNT(DISTINCT p.subject) AS members " + appointments(approvalRequired) + " GROUP BY a." + column,
                    (org.springframework.jdbc.core.RowCallbackHandler) row -> counts.put(row.getString("id"), row.getInt("members")), tenantId);
        }
        return counts;
    }

    private static String appointments(boolean approvalRequired) {
        return ACTIVE_APPOINTMENTS + (approvalRequired ? " AND p.approval_eligible=TRUE" : "");
    }

    private static UUID localId(String role, String prefix) {
        try { return UUID.fromString(role.substring(prefix.length())); }
        catch (IllegalArgumentException exception) { throw new io.agentflow.common.DomainException("INVALID_ORGANIZATION_RULE", "Local organization rule identifier is invalid"); }
    }
}
