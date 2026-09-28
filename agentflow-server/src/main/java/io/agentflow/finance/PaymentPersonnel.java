package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.UUID;

/**
 * 财务执行只面向当前法人内仍在用的人员；系统角色仍由认证入口校验，本地任职不授予角色。
 * @author owlzhangfq@gmail.com
 */
@Service
public class PaymentPersonnel {
    private final JdbcTemplate jdbc;
    /** 本地组织没有显式初始化时不把演示账号当作法人出纳。 */
    public PaymentPersonnel(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 人员、法人、部门、岗位及任职必须同时有效，其他法人的相同角色不能取得付款范围。 */
    public boolean eligible(String tenant, String user, UUID legalEntityId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM organization_directory o
                JOIN organization_person p ON p.tenant_id=o.tenant_id AND p.active=TRUE
                JOIN organization_appointment a ON a.tenant_id=p.tenant_id AND a.person_id=p.id AND a.active=TRUE
                JOIN organization_unit d ON d.tenant_id=a.tenant_id AND d.id=a.department_id AND d.active=TRUE AND d.kind='DEPARTMENT'
                JOIN organization_unit j ON j.tenant_id=a.tenant_id AND j.id=a.position_id AND j.active=TRUE AND j.kind='POSITION' AND j.legal_entity_id=d.legal_entity_id
                JOIN organization_unit l ON l.tenant_id=d.tenant_id AND l.id=d.legal_entity_id AND l.active=TRUE AND l.kind='LEGAL_ENTITY'
                WHERE o.tenant_id=? AND p.subject=? AND l.id=?)
                """, Boolean.class, tenant, user, legalEntityId.toString()));
    }
    /** 后台在领取和发送登记时重读当前组织，停用或结束任职后阻止新发送。 */
    public void requireEligible(String tenant, String user, UUID legalEntityId) {
        if (!eligible(tenant, user, legalEntityId)) throw new DomainException("PAYMENT_ACTOR_UNAVAILABLE", "Payment actor must have an active appointment in the original legal entity");
    }
}
