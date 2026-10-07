package io.agentflow.procurement;

import io.agentflow.observability.DiagnosticContext;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 出纳意图保留原预留版本和账户选择，已登记银行命令后永久占用该授权的执行身份。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSupplierPaymentExecutionRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final JdbcSupplierPayableHoldRepository holds;

    /** 同一数据库事务绑定原预留修订、当前意图及连续历史。 */
    public JdbcSupplierPaymentExecutionRepository(JdbcTemplate jdbc, JsonUtil json, JdbcSupplierPayableHoldRepository holds) {
        this.jdbc = jdbc; this.json = json; this.holds = holds;
    }

    /** 初始记录必须对应当前真实 HELD，唯一键阻止两个出纳同时持有未完成选择。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(SupplierPaymentExecutionRequest value) {
        var input = value.input(); var original = holds.revision(input.tenantId(), input.authorizationId(), input.holdVersion()).orElseThrow(JdbcSupplierPaymentExecutionRepository::conflict);
        if (!original.equals(holds.find(input.tenantId(), input.authorizationId()).orElseThrow(JdbcSupplierPaymentExecutionRepository::conflict))
                || !value.equals(SupplierPaymentExecutionRequest.queue(input.id(), original, input.cashier(), input.debitReference(), input.debitVersion(), value.createdAt()))) throw conflict();
        try {
            jdbc.update("""
                    INSERT INTO supplier_payment_execution_request(trace_id,tenant_id,id,authorization_id,hold_version,cashier_id,input_json,state_json,
                    version,status,attempts,created_at,updated_at,next_attempt_at,active_authorization_id)
                    VALUES(?,?,?,?,?,?,?,?,1,'QUEUED',0,?,?,?,?)
                    """, DiagnosticContext.capture().traceId(), input.tenantId(), input.id().toString(), input.authorizationId().toString(), input.holdVersion(), input.cashier(), json.write(input), json.write(value),
                    timestamp(value.createdAt()), timestamp(value.updatedAt()), timestamp(value.nextAttemptAt()), input.authorizationId().toString());
        } catch (DuplicateKeyException duplicate) { throw new DomainException("SUPPLIER_PAYMENT_EXECUTION_PENDING", "Original supplier authorization already has a cashier execution owner"); }
        append(value);
    }

    /** 只更新相同原输入的前版，READY 必须同时存在对应领取建立的唯一银行命令。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(SupplierPaymentExecutionRequest value) {
        requireRegistration(value); var input = value.input();
        int changed = jdbc.update("""
                UPDATE supplier_payment_execution_request SET state_json=?,version=?,status=?,attempts=?,updated_at=?,next_attempt_at=?,lease_until=?,
                active_authorization_id=?,registered_authorization_id=? WHERE tenant_id=? AND id=? AND version=? AND input_json=?
                """, json.write(value), value.version(), value.status().name(), value.attempts(), timestamp(value.updatedAt()), timestamp(value.nextAttemptAt()), timestamp(value.leaseUntil()),
                owner(value), registered(value), input.tenantId(), input.id().toString(), value.version() - 1, json.write(input));
        if (changed != 1) throw conflict(); append(value);
    }

    /** 原租户与意图号共同定位，不通过供应商名称或业务号猜测出纳选择。 */
    public Optional<SupplierPaymentExecutionRequest> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM supplier_payment_execution_request WHERE tenant_id=? AND id=?", this::restore, tenant, id.toString()).stream().findFirst();
    }

    /** 活动或已登记命令的意图阻止另选账户；明确停止且未登记的历史仍保留。 */
    public Optional<SupplierPaymentExecutionRequest> owner(String tenant, UUID authorizationId) {
        return jdbc.query("SELECT * FROM supplier_payment_execution_request WHERE tenant_id=? AND active_authorization_id=?", this::restore, tenant, authorizationId.toString()).stream().findFirst();
    }

    /** 唯一银行命令对应的登记请求永久保留，不能以最近一次失败选择替代。 */
    public Optional<SupplierPaymentExecutionRequest> registered(String tenant, UUID authorizationId) {
        return jdbc.query("SELECT * FROM supplier_payment_execution_request WHERE tenant_id=? AND registered_authorization_id=?",
                this::restore, tenant, authorizationId.toString()).stream().findFirst();
    }

    /** 工作区可读取最近一次选择及其终止原因，历史不会被新选择覆盖。 */
    public Optional<SupplierPaymentExecutionRequest> latest(String tenant, UUID authorizationId) {
        return jdbc.query("SELECT * FROM supplier_payment_execution_request WHERE tenant_id=? AND authorization_id=? ORDER BY created_at DESC,id DESC LIMIT 1",
                this::restore, tenant, authorizationId.toString()).stream().findFirst();
    }

    /** 银行登记引用当时的实际领取修订，后续终态不能篡改该依据。 */
    public Optional<SupplierPaymentExecutionRequest> revision(String tenant, UUID id, long version) {
        return jdbc.query("SELECT state_json FROM supplier_payment_execution_revision WHERE tenant_id=? AND execution_request_id=? AND version=?", (row, index) -> {
            var value = json.read(row.getString("state_json"), SupplierPaymentExecutionRequest.class);
            if (!value.input().tenantId().equals(tenant) || !value.input().id().equals(id) || value.version() != version) throw conflict(); return value;
        }, tenant, id.toString(), version).stream().findFirst();
    }

    /** 有界扫描只返回任务标识，不在队列列表中输出账户选择。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("""
                SELECT q.tenant_id,q.id,q.trace_id,business.business_no,submitted.process_instance_id FROM supplier_payment_execution_request q
                LEFT JOIN supplier_payment_authorization origin ON origin.tenant_id=q.tenant_id AND origin.id=q.authorization_id
                LEFT JOIN approval_application business ON business.tenant_id=q.tenant_id AND business.id=origin.application_id
                LEFT JOIN approval_submission_round submitted ON submitted.tenant_id=business.tenant_id AND submitted.application_id=business.id AND submitted.round_no=origin.round_no
                WHERE (q.status='QUEUED' AND q.next_attempt_at<=?)
                OR (q.status='RUNNING' AND q.lease_until<=?) ORDER BY COALESCE(q.next_attempt_at,q.lease_until),q.created_at,q.id LIMIT 10
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id")), row.getString("trace_id"), row.getString("business_no"), row.getString("process_instance_id")), timestamp(now), timestamp(now));
    }

    private SupplierPaymentExecutionRequest restore(ResultSet row, int index) throws SQLException {
        var value = json.read(row.getString("state_json"), SupplierPaymentExecutionRequest.class); var input = value.input();
        if (!input.equals(json.read(row.getString("input_json"), SupplierPaymentExecutionRequest.Input.class)) || !input.tenantId().equals(row.getString("tenant_id"))
                || !input.id().toString().equals(row.getString("id")) || !input.authorizationId().toString().equals(row.getString("authorization_id"))
                || input.holdVersion() != row.getLong("hold_version") || !input.cashier().equals(row.getString("cashier_id")) || value.version() != row.getLong("version")
                || !value.status().name().equals(row.getString("status")) || value.attempts() != row.getInt("attempts")
                || !value.createdAt().equals(instant(row.getTimestamp("created_at"))) || !value.updatedAt().equals(instant(row.getTimestamp("updated_at")))
                || !Objects.equals(value.nextAttemptAt(), instant(row.getTimestamp("next_attempt_at"))) || !Objects.equals(value.leaseUntil(), instant(row.getTimestamp("lease_until")))
                || !Objects.equals(owner(value), row.getString("active_authorization_id")) || !Objects.equals(registered(value), row.getString("registered_authorization_id"))) {
            throw new IllegalStateException("Persisted supplier cashier request is inconsistent");
        }
        requireRegistration(value); return value;
    }

    private void requireRegistration(SupplierPaymentExecutionRequest value) {
        if (value.status() != SupplierPaymentExecutionRequest.Status.READY) return;
        var input = value.input();
        var commands = jdbc.query("SELECT command_json FROM supplier_payment_operation WHERE tenant_id=? AND id=? AND execution_request_id=? AND execution_request_version=?",
                (row, index) -> json.read(row.getString("command_json"), SupplierPaymentCommand.class), input.tenantId(), input.authorizationId().toString(), input.id().toString(), value.version() - 1);
        var previous = revision(input.tenantId(), input.id(), value.version() - 1).orElseThrow(JdbcSupplierPaymentExecutionRepository::conflict);
        if (commands.size() != 1 || !previous.ready(commands.get(0), value.updatedAt()).equals(value)) throw conflict();
    }
    private void append(SupplierPaymentExecutionRequest value) {
        jdbc.update("INSERT INTO supplier_payment_execution_revision(tenant_id,execution_request_id,version,state_json) VALUES(?,?,?,?)", value.input().tenantId(), value.input().id().toString(), value.version(), json.write(value));
    }
    private static String owner(SupplierPaymentExecutionRequest value) { return value.ownsAuthorization() ? value.input().authorizationId().toString() : null; }
    private static String registered(SupplierPaymentExecutionRequest value) { return value.status() == SupplierPaymentExecutionRequest.Status.READY ? value.input().authorizationId().toString() : null; }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Supplier cashier request or original registration changed"); }

    /**
     * 后台扫描不加载供应商或财务正文。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id, String traceId, String businessNo, String processInstanceId) {
        /** 原业务关联不存在时保持空值，不借用当前审批轮次或工作线程。 */
        public Candidate(String tenantId, UUID id, String traceId) { this(tenantId, id, traceId, null, null); }
        /** 旧候选缺来源时由工作器建立稳定诊断作用域。 */
        public Candidate(String tenantId, UUID id) { this(tenantId, id, null); }
    }
}
