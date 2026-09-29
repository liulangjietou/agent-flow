package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 供应商财务授权保留不可变原件；同一批准申请不能并发创建第二个预留身份。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSupplierPaymentAuthorizationRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final ApprovedSupplierPaymentSources sources;

    /** 仓储核验真实批准引用，授权的人员权限由应用入口负责。 */
    public JdbcSupplierPaymentAuthorizationRepository(JdbcTemplate jdbc, JsonUtil json, ApprovedSupplierPaymentSources sources) {
        this.jdbc = jdbc; this.json = json; this.sources = sources;
    }

    /** 与原预留队列同事务建立，数据库唯一键仲裁重复请求而不覆盖原授权。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(SupplierPaymentAuthorization value) {
        sources.lock(value); sources.requireCurrent(value);
        var approved = value.source(); var reservation = approved.reservation(); var source = reservation.source();
        try {
            jdbc.update("""
                    INSERT INTO supplier_payment_authorization(tenant_id,id,request_id,application_id,employee_id,round_no,application_version,request_version,
                    reservation_id,legal_entity_id,authorized_by,authorized_at,expires_at,state_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, source.tenantId(), value.id().toString(), source.requestId().toString(), source.applicationId().toString(), source.employeeId(), source.round().roundNo(),
                    approved.approval().applicationVersion(), approved.approvedRequestVersion(), reservation.id().toString(), source.round().content().legalEntityId().toString(),
                    value.authorizedBy(), Timestamp.from(value.authorizedAt()), Timestamp.from(value.expiresAt()), json.write(value));
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException("SUPPLIER_PAYMENT_ALREADY_AUTHORIZED", "Approved procurement already has an original supplier payment authorization");
        }
    }

    /** 读取只按已知租户和原编号，不通过当前授权覆盖历史。 */
    public Optional<SupplierPaymentAuthorization> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM supplier_payment_authorization WHERE tenant_id=? AND id=?", this::restore, tenant, id.toString()).stream().findFirst();
    }

    /** 本地办理只返回该实际采购申请的原授权。 */
    public Optional<SupplierPaymentAuthorization> forRequest(String tenant, UUID requestId) {
        return jdbc.query("SELECT * FROM supplier_payment_authorization WHERE tenant_id=? AND request_id=?", this::restore, tenant, requestId.toString()).stream().findFirst();
    }

    private SupplierPaymentAuthorization restore(ResultSet row, int index) throws SQLException {
        var value = json.read(row.getString("state_json"), SupplierPaymentAuthorization.class); var approved = value.source(); var source = approved.reservation().source();
        if (!source.tenantId().equals(row.getString("tenant_id")) || !value.id().toString().equals(row.getString("id"))
                || !source.requestId().toString().equals(row.getString("request_id")) || !source.applicationId().toString().equals(row.getString("application_id"))
                || !source.employeeId().equals(row.getString("employee_id")) || source.round().roundNo() != row.getInt("round_no")
                || approved.approval().applicationVersion() != row.getLong("application_version") || approved.approvedRequestVersion() != row.getLong("request_version")
                || !approved.reservation().id().toString().equals(row.getString("reservation_id")) || !source.round().content().legalEntityId().toString().equals(row.getString("legal_entity_id"))
                || !value.authorizedBy().equals(row.getString("authorized_by")) || !value.authorizedAt().equals(row.getTimestamp("authorized_at").toInstant())
                || !value.expiresAt().equals(row.getTimestamp("expires_at").toInstant())) throw new IllegalStateException("Persisted supplier payment authorization identity is inconsistent");
        return value;
    }
}
