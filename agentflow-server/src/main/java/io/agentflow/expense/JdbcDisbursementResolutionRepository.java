package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.DisbursementResolutionRepositoryMapper;
import io.agentflow.jdbc.JdbcTimestampPrecision;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 原放款决定关联相邻借款修订及被消费查询，新增入款同时进入跨入口防重账本。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcDisbursementResolutionRepository {
    private final DisbursementResolutionRepositoryMapper sqlMapper;
    private final JsonUtil json;
    private final JdbcDisbursementReturnCheckRepository checks;
    private final JdbcAdvanceReceiptCreditRepository credits;

    /** 全部持久事实由同一借款事务提交。 */
    public JdbcDisbursementResolutionRepository(
            DisbursementResolutionRepositoryMapper sqlMapper,
            JsonUtil json,
            JdbcDisbursementReturnCheckRepository checks,
            JdbcAdvanceReceiptCreditRepository credits) {
        this.sqlMapper = sqlMapper;
        this.json = json;
        this.checks = checks;
        this.credits = credits;
    }

    /** 重放本次状态变化，不能保存与余额不一致的人工决定或单独的防重记录。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(AdvanceDisbursementReturn decision, EmployeeAdvance after) {
        var check =
                checks.find(decision.tenantId(), decision.checkId())
                        .orElseThrow(JdbcDisbursementResolutionRepository::changed);
        if (check.status() != AdvanceDisbursementReturnCheck.Status.RESOLVED
                || !decision.id().equals(check.resolutionId())
                || !decision.receipt().equals(check.receipt())
                || !decision.resolvedBy().equals(check.input().requestedBy())
                || !decision.resolvedAt().equals(check.updatedAt())) throw changed();
        var before = revision(after.tenantId(), after.id(), after.version() - 1);
        before.resolveDisbursementReview(before.version(), decision);
        if (!before.state().equals(after.state())
                || !revision(after.tenantId(), after.id(), after.version())
                        .state()
                        .equals(after.state())) throw changed();
        sqlMapper.create(
                decision.tenantId(),
                decision.id().toString(),
                after.id().toString(),
                after.version(),
                decision.checkId().toString(),
                check.version(),
                decision.receipt().status().name(),
                decision.resolvedBy(),
                Timestamp.from(
                        JdbcTimestampPrecision.roundedToMicros(decision.receipt().observedAt())),
                Timestamp.from(JdbcTimestampPrecision.roundedToMicros(decision.resolvedAt())),
                json.write(decision));
        try {
            for (var entry : after.disbursementReturns())
                if (entry.resolutionId().equals(decision.id())) credits.record(decision, entry);
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException(
                    "DISBURSEMENT_RETURN_ALREADY_RECORDED",
                    "Received funds or credit already belongs to another repayment, disbursement"
                        + " adjustment or expense return");
        }
    }

    /** 最近人工决定用于展示和约束后续查询，读取不修改原件或续期。 */
    public Optional<AdvanceDisbursementReturn> latest(String tenant, UUID advanceId) {
        return SqlRows.map(sqlMapper.latest(tenant, advanceId.toString()), row()).stream()
                .findFirst();
    }

    /** 按原决定恢复被消费查询及相邻借款修订，最新余额不能替代历史裁决。 */
    public Optional<Resolved> find(String tenant, UUID id) {
        return SqlRows.map(
                        sqlMapper.find(tenant, id.toString()),
                        row -> {
                            var decision = row().apply(row);
                            var advanceId =
                                    decision.receipt().request().command().binding().businessId();
                            long version = row.getLong("advance_version"),
                                    checkVersion = row.getLong("check_version");
                            var before = revision(tenant, advanceId, version - 1);
                            var after = revision(tenant, advanceId, version);
                            var prior =
                                    json.read(
                                            row.getString("prior_check_json"),
                                            AdvanceDisbursementReturnCheck.class);
                            var resolved =
                                    json.read(
                                            row.getString("resolved_check_json"),
                                            AdvanceDisbursementReturnCheck.class);
                            if (!decision.id().equals(id)
                                    || !decision.tenantId().equals(tenant)
                                    || before.version() != version - 1
                                    || after.version() != version
                                    || !before.tenantId().equals(tenant)
                                    || !after.tenantId().equals(tenant)
                                    || !before.id().equals(advanceId)
                                    || !after.id().equals(advanceId)
                                    || prior.version() != checkVersion - 1
                                    || resolved.version() != checkVersion
                                    || !prior.input().id().equals(decision.checkId())
                                    || !prior.input().tenantId().equals(tenant)
                                    || !prior.resolve(decision, decision.resolvedAt())
                                            .equals(resolved)) throw changed();
                            before.resolveDisbursementReview(before.version(), decision);
                            if (!before.state().equals(after.state())) throw changed();
                            return new Resolved(version, decision);
                        })
                .stream()
                .findFirst();
    }

    /**
     * 该次决定对应的借款修订，不提供当前可用额或办理许可。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Resolved(long advanceVersion, AdvanceDisbursementReturn decision) {}

    private EmployeeAdvance revision(String tenant, UUID id, long version) {
        return SqlRows.map(
                        sqlMapper.revision(tenant, id.toString(), version),
                        row ->
                                EmployeeAdvance.restore(
                                        json.read(
                                                row.getString("state_json"),
                                                EmployeeAdvance.State.class)))
                .stream()
                .findFirst()
                .orElseThrow(JdbcDisbursementResolutionRepository::changed);
    }

    private Function<SqlRow, AdvanceDisbursementReturn> row() {
        return row -> {
            var value = json.read(row.getString("state_json"), AdvanceDisbursementReturn.class);
            var command = value.receipt().request().command();
            if (!value.tenantId().equals(row.getString("tenant_id"))
                    || !value.id().toString().equals(row.getString("id"))
                    || !command.binding()
                            .businessId()
                            .toString()
                            .equals(row.getString("advance_id"))
                    || !value.checkId().toString().equals(row.getString("check_id"))
                    || !value.receipt().status().name().equals(row.getString("outcome"))
                    || !value.resolvedBy().equals(row.getString("resolved_by"))
                    || !JdbcTimestampPrecision.roundedToMicros(value.receipt().observedAt())
                            .equals(row.getTimestamp("observed_at").toInstant())
                    || !JdbcTimestampPrecision.roundedToMicros(value.resolvedAt())
                            .equals(row.getTimestamp("resolved_at").toInstant()))
                throw new IllegalStateException(
                        "Persisted disbursement resolution identity is inconsistent");
            return value;
        };
    }

    private static DomainException changed() { return new DomainException("DISBURSEMENT_RETURN_SOURCE_CHANGED", "Disbursement decision must match its consumed check and both advance revisions"); }
}
