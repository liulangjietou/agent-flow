package io.agentflow.finance;

import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 财务授权及其唯一执行登记同版本保存，已执行授权持续占用业务单据，不能换号重复付款。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcPaymentAuthorizationRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 授权仓储仅保存人工决定，不读取外部账户或发送付款。 */
    public JdbcPaymentAuthorizationRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 调用方持有申请和业务锁；业务独占及凭证外键同时约束并发新授权。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(PaymentAuthorization value) {
        if (value.version() != 1 || value.status() != PaymentAuthorization.Status.AUTHORIZED) throw conflict();
        var terms = value.terms(); var binding = terms.binding(); var decision = value.decision();
        jdbc.update("""
                INSERT INTO payment_authorization(tenant_id,id,business_type,business_id,application_id,round_no,application_version,business_version,
                purpose,voucher_operation_id,voucher_kind,terms_json,decision_json,state_json,version,status,active_business_id,authorized_at,expires_at,updated_at,legal_entity_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,'AUTHORIZED',?,?,?,?,?)
                """, terms.tenantId(), terms.id().toString(), businessType(terms.purpose()).name(), binding.businessId().toString(), binding.applicationId().toString(),
                binding.roundNo(), binding.applicationVersion(), binding.businessVersion(), terms.purpose().name(), terms.voucherOperationId().toString(), kind(terms.purpose()).name(),
                json.write(terms), json.write(decision), json.write(value), activeBusiness(value), Timestamp.from(decision.authorizedAt()), Timestamp.from(decision.expiresAt()), Timestamp.from(value.updatedAt()), terms.payee().legalEntityId().toString());
        append(value);
    }

    /** 原条款不可更新；第三版只允许以保留的执行事实安全结束，并同事务追加证据。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(PaymentAuthorization value) {
        if (value.version() != 2 && value.status() != PaymentAuthorization.Status.RETIRED) throw conflict();
        if (value.status() == PaymentAuthorization.Status.RETIRED) requireRetirementProof(value);
        int changed = jdbc.update("""
                UPDATE payment_authorization SET state_json=?,version=?,status=?,active_business_id=?,updated_at=?
                WHERE tenant_id=? AND id=? AND version=? AND status=? AND terms_json=? AND decision_json=?
                """, json.write(value), value.version(), value.status().name(), activeBusiness(value), Timestamp.from(value.updatedAt()), value.terms().tenantId(), value.terms().id().toString(),
                value.version() - 1, value.version() == 2 ? "AUTHORIZED" : "EXECUTION_REGISTERED",
                json.write(value.terms()), json.write(value.decision()));
        if (changed != 1) throw conflict(); append(value);
        if (value.retirement() != null) {
            var retirement = value.retirement();
            jdbc.update("""
                    INSERT INTO payment_retirement(tenant_id,authorization_id,authorization_version,operation_version,basis,retired_by,retired_at,retirement_json)
                    VALUES(?,?,3,?,?,?,?,?)
                    """, value.terms().tenantId(), value.terms().id().toString(), retirement.operationVersion(), retirement.basis().name(),
                    retirement.retiredBy(), Timestamp.from(retirement.retiredAt()), json.write(retirement));
        }
    }

    /** 租户必须从认证或持久任务取得；读取恢复时核对关系列与不可变快照。 */
    public Optional<PaymentAuthorization> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM payment_authorization WHERE tenant_id=? AND id=?", row(), tenant, id.toString()).stream().findFirst();
    }
    /** 跨轮次查询同一业务的当前占用，已登记执行的授权不能被新轮次遮蔽。 */
    public Optional<PaymentAuthorization> active(String tenant, BusinessReference.Type type, UUID businessId) {
        return jdbc.query("SELECT * FROM payment_authorization WHERE tenant_id=? AND business_type=? AND active_business_id=?", row(), tenant, type.name(), businessId.toString()).stream().findFirst();
    }
    /** 工作区读取该轮最近的人工决定，不把其他轮次授权当作当前授权。 */
    public Optional<PaymentAuthorization> latest(String tenant, UUID applicationId, int round) {
        return jdbc.query("SELECT * FROM payment_authorization WHERE tenant_id=? AND application_id=? AND round_no=? ORDER BY authorized_at DESC,id DESC LIMIT 1",
                row(), tenant, applicationId.toString(), round).stream().findFirst();
    }
    /** 出纳列表先在数据库按当前法人任职过滤，再读取原授权；不扫描全租户付款后交给页面筛选。 */
    public List<PaymentAuthorization> cashierPage(String tenant, String cashier, Instant beforeTime, UUID beforeId, int limit) {
        var args = new ArrayList<Object>(List.of(tenant, tenant, cashier)); var filter = "";
        if (beforeTime != null) {
            filter = " AND (authorized_at<? OR (authorized_at=? AND id<?))";
            args.add(Timestamp.from(beforeTime)); args.add(Timestamp.from(beforeTime)); args.add(beforeId.toString());
        }
        args.add(limit + 1);
        return jdbc.query("SELECT * FROM payment_authorization WHERE tenant_id=? AND legal_entity_id IN (" + PaymentPersonnel.ELIGIBLE_ENTITIES + ")"
                + filter + " ORDER BY authorized_at DESC,id DESC LIMIT ?", row(), args.toArray());
    }
    /** 业务种类固定取原用途，不接受客户端单独声明。 */
    public static BusinessReference.Type businessType(PaymentCommand.Purpose purpose) { return purpose == PaymentCommand.Purpose.EMPLOYEE_ADVANCE ? BusinessReference.Type.ADVANCE_REQUEST : BusinessReference.Type.EXPENSE; }
    /** 付款只依赖借款或费用挂账凭证，不能反向依赖付款凭证。 */
    public static VoucherCommand.Kind kind(PaymentCommand.Purpose purpose) { return purpose == PaymentCommand.Purpose.EMPLOYEE_ADVANCE ? VoucherCommand.Kind.EMPLOYEE_ADVANCE : VoucherCommand.Kind.EXPENSE_ACCRUAL; }

    private RowMapper<PaymentAuthorization> row() {
        return (row, index) -> {
            var value = json.read(row.getString("state_json"), PaymentAuthorization.class); var terms = value.terms(); var binding = terms.binding();
            if (!terms.equals(json.read(row.getString("terms_json"), PaymentAuthorization.Terms.class)) || !value.decision().equals(json.read(row.getString("decision_json"), PaymentAuthorization.Decision.class))
                    || !terms.tenantId().equals(row.getString("tenant_id")) || !terms.id().toString().equals(row.getString("id"))
                    || !businessType(terms.purpose()).name().equals(row.getString("business_type")) || !binding.businessId().toString().equals(row.getString("business_id"))
                    || !binding.applicationId().toString().equals(row.getString("application_id")) || binding.roundNo() != row.getInt("round_no")
                    || binding.applicationVersion() != row.getLong("application_version") || binding.businessVersion() != row.getLong("business_version")
                    || !terms.purpose().name().equals(row.getString("purpose")) || !terms.voucherOperationId().toString().equals(row.getString("voucher_operation_id"))
                    || !kind(terms.purpose()).name().equals(row.getString("voucher_kind")) || !Objects.equals(activeBusiness(value), row.getString("active_business_id"))
                    || !terms.payee().legalEntityId().toString().equals(row.getString("legal_entity_id"))
                    || value.version() != row.getLong("version") || !value.status().name().equals(row.getString("status"))
                    || !value.decision().authorizedAt().equals(row.getTimestamp("authorized_at").toInstant()) || !value.decision().expiresAt().equals(row.getTimestamp("expires_at").toInstant())
                    || !value.updatedAt().equals(row.getTimestamp("updated_at").toInstant())) throw new IllegalStateException("Persisted payment authorization identity is inconsistent");
            if (value.retirement() != null) {
                var saved = jdbc.query("SELECT retirement_json FROM payment_retirement WHERE tenant_id=? AND authorization_id=? AND authorization_version=3 AND operation_version=? AND basis=? AND retired_by=? AND retired_at=?",
                        (record, ignored) -> json.read(record.getString("retirement_json"), PaymentAuthorization.Retirement.class),
                        terms.tenantId(), terms.id().toString(), value.retirement().operationVersion(), value.retirement().basis().name(), value.retirement().retiredBy(), Timestamp.from(value.retirement().retiredAt()));
                if (saved.size() != 1 || !saved.get(0).equals(value.retirement())) throw new IllegalStateException("Persisted payment retirement identity is inconsistent");
                requireRetirementProof(value);
            }
            return value;
        };
    }
    private void requireRetirementProof(PaymentAuthorization value) {
        var proofs = jdbc.query("SELECT state_json FROM payment_operation_revision WHERE tenant_id=? AND operation_id=? AND version=?",
                (row, index) -> json.read(row.getString("state_json"), PaymentOperation.class), value.terms().tenantId(), value.terms().id().toString(), value.retirement().operationVersion());
        if (proofs.size() != 1 || !value.matchesRetirement(proofs.get(0))) throw conflict();
        var current = jdbc.queryForObject("SELECT version FROM payment_operation WHERE tenant_id=? AND id=?", Long.class, value.terms().tenantId(), value.terms().id().toString());
        if (!Objects.equals(current, value.retirement().operationVersion())) throw conflict();
    }
    private static String activeBusiness(PaymentAuthorization value) { return value.status() == PaymentAuthorization.Status.AUTHORIZED || value.status() == PaymentAuthorization.Status.EXECUTION_REGISTERED ? value.terms().binding().businessId().toString() : null; }
    private void append(PaymentAuthorization value) { jdbc.update("INSERT INTO payment_authorization_revision(tenant_id,authorization_id,version,state_json) VALUES(?,?,?,?)", value.terms().tenantId(), value.terms().id().toString(), value.version(), json.write(value)); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Payment authorization terms or version changed"); }
}
