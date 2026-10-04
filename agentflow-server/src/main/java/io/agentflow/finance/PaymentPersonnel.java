package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.UUID;
import java.util.List;

/**
 * 财务执行只面向当前法人内仍在用的人员；系统角色仍由认证入口校验，本地任职不授予角色。
 * @author owlzhangfq@gmail.com
 */
@Service
public class PaymentPersonnel {
    static final String ELIGIBLE_ENTITIES = """
            SELECT l.id FROM organization_directory o
            JOIN organization_person p ON p.tenant_id=o.tenant_id AND p.active=TRUE
            JOIN organization_appointment a ON a.tenant_id=p.tenant_id AND a.person_id=p.id AND a.active=TRUE
            JOIN organization_unit d ON d.tenant_id=a.tenant_id AND d.id=a.department_id AND d.active=TRUE AND d.kind='DEPARTMENT'
            JOIN organization_unit j ON j.tenant_id=a.tenant_id AND j.id=a.position_id AND j.active=TRUE AND j.kind='POSITION' AND j.legal_entity_id=d.legal_entity_id
            JOIN organization_unit l ON l.tenant_id=d.tenant_id AND l.id=d.legal_entity_id AND l.active=TRUE AND l.kind='LEGAL_ENTITY'
            WHERE o.tenant_id=? AND p.subject=?
            """;
    private final JdbcTemplate jdbc;
    /** 本地组织没有显式初始化时不把演示账号当作法人出纳。 */
    public PaymentPersonnel(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 人员、法人、部门、岗位及任职必须同时有效，其他法人的相同角色不能取得付款范围。 */
    public boolean eligible(String tenant, String user, UUID legalEntityId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(" + ELIGIBLE_ENTITIES + " AND l.id=?)", Boolean.class, tenant, user, legalEntityId.toString()));
    }
    /** 供应商出纳目录在数据库分页前使用真实任职范围，不接受客户端传入的法人列表。 */
    public List<UUID> legalEntities(String tenant, String user) {
        return jdbc.query("SELECT DISTINCT id FROM (" + ELIGIBLE_ENTITIES + ") eligible_entities", (row, index) -> UUID.fromString(row.getString("id")), tenant, user);
    }
    /** 筛选只展示当前有效任职法人名称，不向出纳公开完整组织目录。 */
    public List<LegalEntity> legalEntityOptions(String tenant, String user) {
        return jdbc.query("SELECT id,name FROM organization_unit WHERE tenant_id=? AND id IN (" + ELIGIBLE_ENTITIES + ") ORDER BY name,id",
                (row, index) -> new LegalEntity(UUID.fromString(row.getString("id")), row.getString("name")), tenant, tenant, user);
    }
    /** 后台在领取和发送登记时重读当前组织，停用或结束任职后阻止新发送。 */
    public void requireEligible(String tenant, String user, UUID legalEntityId) {
        if (!eligible(tenant, user, legalEntityId)) throw new DomainException("PAYMENT_ACTOR_UNAVAILABLE", "Payment actor must have an active appointment in the original legal entity");
    }
    /**
     * 当前出纳可用的法人筛选项。
     * @author owlzhangfq@gmail.com
     */
    public record LegalEntity(UUID id, String name) { }
}
