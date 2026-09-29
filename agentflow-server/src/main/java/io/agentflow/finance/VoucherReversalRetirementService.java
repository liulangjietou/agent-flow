package io.agentflow.finance;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * 原冲销结束、原件恢复和相关冻结释放同事务编排，已发生的银行及会计事实不撤销。
 * @author owlzhangfq@gmail.com
 */
@Service
public class VoucherReversalRetirementService {
    private final CurrentActor actors;
    private final VoucherDisputeService access;
    private final VoucherReversalSources sources;
    private final JdbcVoucherReversalOperationRepository operations;
    private final JdbcVoucherOperationRepository originals;
    private final JdbcVoucherReversalRecordRepository records;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 权限沿用原凭证，恢复领域只处理各自明确来源，外部查询仍由已有原件工作器执行。 */
    public VoucherReversalRetirementService(CurrentActor actors, VoucherDisputeService access, VoucherReversalSources sources,
            JdbcVoucherReversalOperationRepository operations, JdbcVoucherOperationRepository originals, JdbcVoucherReversalRecordRepository records,
            ApplicationEventPublisher events, JdbcTemplate jdbc, JsonUtil json) {
        this.actors = actors; this.access = access; this.sources = sources; this.operations = operations; this.originals = originals;
        this.records = records; this.events = events; this.jdbc = jdbc; this.json = json;
    }
    /** 当前独立财务明确结束，来源、两组操作版本及当前原过账证据必须一致。 */
    @Transactional
    public Receipt retire(UUID applicationId, UUID operationId, Input input) {
        access.requireFinance(applicationId, operationId, input.roundNo()); var actor = actors.actor(); sources.locked(actor.tenantId(), operationId);
        var context = access.requireFinance(applicationId, operationId, input.roundNo()); var source = sources.find(context, operationId);
        var value = operations.forOriginal(actor.tenantId(), operationId).orElseThrow(VoucherReversalRetirementService::conflict);
        if (context.application().version() != input.applicationVersion() || context.businessVersion() != input.businessVersion() || source.current().version() != input.operationVersion()
                || !value.input().command().id().equals(input.reversalId()) || value.version() != input.reversalVersion()) throw conflict();
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS); requireUnrecorded(actor.tenantId(), operationId);
        var record = VoucherReversalRetirement.create(value, source.current(), actor.userId(), input.evidenceReference(), input.comment(), now);
        var stopped = value.stopForRetirement(now); if (stopped != value) operations.update(stopped);
        var released = source.current().releaseReversal(input.reversalId(), now); originals.update(released); operations.retire(record);
        events.publishEvent(new VoucherOperationChanged(source.current(), released)); events.publishEvent(new VoucherReversalRetired(released, record));
        var audit = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audit_event(id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,application_id,action,actor_id,payload_json,occurred_at)
                VALUES(?,?,?,'VoucherReversalRetirement',?,1,?,'VOUCHER_REVERSAL_RETIRED',?,?,?)
                """, UUID.randomUUID().toString(), actor.tenantId(), audit.toString(), record.id().toString(), applicationId.toString(), actor.userId(),
                json.write(Map.of("operationId", operationId, "reversalId", input.reversalId(), "roundNo", input.roundNo(), "basis", record.basis(), "evidenceReference", input.evidenceReference(), "comment", input.comment())), Timestamp.from(now));
        return new Receipt(applicationId, operationId, input.roundNo(), record.id(), input.reversalId(), stopped.version(), released.version(), record.basis(), audit);
    }
    /** 页面和写入口使用相同结束依据，当前角色与任职由页面读取入口先检查。 */
    public String issue(VoucherReversalOperation value, VoucherReversalSources.Source source, String actor, Instant now) {
        try { requireUnrecorded(source.request().command().tenantId(), source.request().command().id()); VoucherReversalRetirement.requireSource(value, source.current(), actor, now); return null; }
        catch (DomainException unavailable) { return unavailable.code(); }
    }
    private void requireUnrecorded(String tenant, UUID original) {
        if (records.forOperation(tenant, original).isPresent()) throw new DomainException("VOUCHER_REVERSAL_ALREADY_RECORDED", "A registered independent reversal prevents restoring the original voucher");
    }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Original voucher or reversal retirement source changed"); }
    /**
     * 客户端只传展示版本与财务说明，不能声明未执行或手工填入 ERP 结果。
     * @author owlzhangfq@gmail.com
     */
    public record Input(@Positive int roundNo, @Positive long applicationVersion, @Positive long businessVersion, @Positive long operationVersion,
            @NotNull UUID reversalId, @Positive long reversalVersion, @NotBlank @Size(max = 128) @Pattern(regexp = "[^\\p{Cntrl}]+") String evidenceReference,
            @NotBlank @Size(max = 2000) String comment) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown reversal retirement field"); }
    }
    /**
     * 结束事实和恢复版本单独回执，不伪装成 ERP 已经完成新的过账。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID applicationId, UUID operationId, int roundNo, UUID retirementId, UUID reversalId, long reversalVersion,
            long operationVersion, VoucherReversalOperation.RetirementBasis basis, UUID auditEventId) { }
}
