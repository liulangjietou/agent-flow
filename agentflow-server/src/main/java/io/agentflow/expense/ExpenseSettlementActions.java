package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 财务显式重试只排队未完成的核销，并与人工理由审计同事务保存。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseSettlementActions {
    private final CurrentActor actors;
    private final ExpenseSettlementAccess access;
    private final ExpenseReportRepository reports;
    private final JdbcExpenseSettlementRepository settlements;
    private final ExpenseSettlementService execution;
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 不含银行调用，幂等事务失败不会留下无审计的结算重试。 */
    public ExpenseSettlementActions(CurrentActor actors, ExpenseSettlementAccess access, ExpenseReportRepository reports, JdbcExpenseSettlementRepository settlements,
            ExpenseSettlementService execution, JdbcTemplate jdbc, JsonUtil json) {
        this.actors = actors; this.access = access; this.reports = reports; this.settlements = settlements; this.execution = execution; this.jdbc = jdbc; this.json = json;
    }
    /** 原申请与财务版本、结算版本都须来自当前展示，不能复用旧轮次的重试按钮。 */
    @Transactional
    public Receipt retry(UUID reportId, Input input) {
        var actor = actors.actor(); access.requireFinance(reportId, input.roundNo()); reports.lock(actor.tenantId(), reportId);
        var context = access.requireFinance(reportId, input.roundNo());
        var current = settlements.find(actor.tenantId(), reportId).orElseThrow(ExpenseSettlementActions::conflict);
        if (context.application().status() != ApplicationStatus.APPROVED || !context.source().equals(current.input().source())
                || context.application().version() != input.applicationVersion() || context.businessVersion() != input.financialVersion()) throw conflict();
        var next = execution.retry(actor.tenantId(), reportId, input.settlementVersion()); UUID event = UUID.randomUUID();
        var payload = Map.of("roundNo", input.roundNo(), "applicationVersion", input.applicationVersion(), "financialVersion", input.financialVersion(),
                "previousStatus", current.status().name(), "currentStatus", next.status().name(), "comment", input.comment().trim());
        jdbc.update("""
                INSERT INTO audit_event(id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,application_id,action,actor_id,payload_json,occurred_at)
                VALUES(?,?,?,'ExpenseSettlement',?,?,?,'EXPENSE_SETTLEMENT_RETRY',?,?,?)
                """, UUID.randomUUID().toString(), actor.tenantId(), event.toString(), reportId.toString(), next.version(), context.application().id().toString(), actor.userId(), json.write(payload), Timestamp.from(Instant.now()));
        return new Receipt(reportId, context.application().id(), input.roundNo(), next.version(), event);
    }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Original approval, financial or settlement version changed"); }
    /**
     * 不接受客户端付款事实、金额、账户或替代核销状态。
     * @author owlzhangfq@gmail.com
     */
    public record Input(@Positive int roundNo, @Positive long applicationVersion, @Positive long financialVersion, @Positive long settlementVersion,
                        @NotBlank @Size(max = 2000) String comment) {
        /** 严格拒绝契约外的字段。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown expense settlement retry field"); }
    }
    /**
     * 幂等回执仅确认排队及审计，当前进度必须重新查询。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID reportId, UUID applicationId, int roundNo, long settlementVersion, UUID auditEventId) { }
}
