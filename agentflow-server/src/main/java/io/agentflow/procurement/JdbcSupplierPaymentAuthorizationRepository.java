package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
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
                    reservation_id,legal_entity_id,authorized_by,authorized_at,expires_at,state_json,active_request_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, source.tenantId(), value.id().toString(), source.requestId().toString(), source.applicationId().toString(), source.employeeId(), source.round().roundNo(),
                    approved.approval().applicationVersion(), approved.approvedRequestVersion(), reservation.id().toString(), source.round().content().legalEntityId().toString(),
                    value.authorizedBy(), Timestamp.from(value.authorizedAt()), Timestamp.from(value.expiresAt()), json.write(value), source.requestId().toString());
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException("SUPPLIER_PAYMENT_ALREADY_AUTHORIZED", "Approved procurement already has an original supplier payment authorization");
        }
    }

    /** 读取只按已知租户和原编号，不通过当前授权覆盖历史。 */
    public Optional<SupplierPaymentAuthorization> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM supplier_payment_authorization WHERE tenant_id=? AND id=?", this::restore, tenant, id.toString()).stream().findFirst();
    }

    /** 历史查询保留最新决定，旧授权仍可按原编号读取。 */
    public Optional<SupplierPaymentAuthorization> forRequest(String tenant, UUID requestId) {
        return jdbc.query("SELECT * FROM supplier_payment_authorization WHERE tenant_id=? AND request_id=? ORDER BY authorized_at DESC,id DESC LIMIT 1", this::restore, tenant, requestId.toString()).stream().findFirst();
    }

    /** 新授权独占实际批准申请，结束后的原授权不会重新占用。 */
    public Optional<SupplierPaymentAuthorization> activeForRequest(String tenant, UUID requestId) {
        return jdbc.query("SELECT * FROM supplier_payment_authorization WHERE tenant_id=? AND active_request_id=?", this::restore, tenant, requestId.toString()).stream().findFirst();
    }

    /** 先保存绑定当前安全修订的结束证据，再解除独占；任一步失败回滚原操作停止与结束决定。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retire(String tenant, SupplierAuthorizationRetirement decision) {
        var authorization = find(tenant, decision.authorizationId()).orElseThrow(JdbcSupplierPaymentAuthorizationRepository::conflict);
        if (retirement(tenant, decision.authorizationId()).isPresent()) throw conflict();
        var proof = retirementProof(tenant, decision);
        var command = new SupplierPayableHoldCommand(authorization);
        var current = jdbc.query("SELECT state_json FROM supplier_payable_hold_operation WHERE tenant_id=? AND id=? AND version=? AND command_digest=? AND command_json=?",
                (row, index) -> json.read(row.getString("state_json"), SupplierPayableHoldOperation.class), tenant, decision.authorizationId().toString(), decision.operationVersion(), command.digest(), json.write(command));
        if (!proof.command().equals(command) || current.size() != 1 || !proof.equals(current.get(0))) throw conflict();
        jdbc.update("INSERT INTO supplier_authorization_retirement(tenant_id,authorization_id,operation_version,basis,retired_by,retired_at,state_json) VALUES(?,?,?,?,?,?,?)",
                tenant, decision.authorizationId().toString(), decision.operationVersion(), decision.basis().name(), decision.retiredBy(), Timestamp.from(decision.retiredAt()), json.write(decision));
        int changed = jdbc.update("UPDATE supplier_payment_authorization SET active_request_id=NULL,retired_hold_version=? WHERE tenant_id=? AND id=? AND active_request_id=request_id AND retired_hold_version IS NULL",
                decision.operationVersion(), tenant, decision.authorizationId().toString());
        if (changed != 1) throw conflict();
    }

    /** 结束记录按关系列与原修订恢复，伪造标记不能使原申请可重新付款。 */
    public Optional<SupplierAuthorizationRetirement> retirement(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM supplier_authorization_retirement WHERE tenant_id=? AND authorization_id=?", (row, index) -> {
            var value = json.read(row.getString("state_json"), SupplierAuthorizationRetirement.class);
            if (!value.authorizationId().equals(id) || value.operationVersion() != row.getLong("operation_version") || !value.basis().name().equals(row.getString("basis"))
                    || !value.retiredBy().equals(row.getString("retired_by")) || !value.retiredAt().equals(row.getTimestamp("retired_at").toInstant())) throw conflict();
            retirementProof(tenant, value); return value;
        }, tenant, id.toString()).stream().findFirst();
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
        Long retiredVersion = row.getObject("retired_hold_version", Long.class);
        if (!Objects.equals(retiredVersion == null ? source.requestId().toString() : null, row.getString("active_request_id"))) throw conflict();
        if (retiredVersion != null) {
            var decision = retirement(source.tenantId(), value.id()).orElseThrow(JdbcSupplierPaymentAuthorizationRepository::conflict);
            if (decision.operationVersion() != retiredVersion || !retirementProof(source.tenantId(), decision).command().authorization().equals(value)) throw conflict();
        }
        return value;
    }

    private SupplierPayableHoldOperation retirementProof(String tenant, SupplierAuthorizationRetirement decision) {
        var saved = jdbc.query("SELECT state_json FROM supplier_payable_hold_revision WHERE tenant_id=? AND operation_id=? AND version=?",
                (row, index) -> json.read(row.getString("state_json"), SupplierPayableHoldOperation.class), tenant, decision.authorizationId().toString(), decision.operationVersion());
        if (saved.size() != 1 || !saved.get(0).command().tenantId().equals(tenant) || !decision.matches(saved.get(0))) throw conflict();
        return saved.get(0);
    }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Supplier authorization retirement or original hold evidence changed"); }
}
