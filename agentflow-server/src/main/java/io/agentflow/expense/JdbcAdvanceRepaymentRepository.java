package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.AdvanceRepaymentRepositoryMapper;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 已确认还款只追加，收款编号、资金流水和会计分录均按法人防重。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcAdvanceRepaymentRepository {
    private final AdvanceRepaymentRepositoryMapper sqlMapper;
    private final JsonUtil json;
    private final JdbcAdvanceRepaymentCheckRepository checks;
    private final JdbcAdvanceReceiptCreditRepository credits;

    /** 关联原查询和借款连续修订，不保存没有余额归属的人工声明。 */
    public JdbcAdvanceRepaymentRepository(
            AdvanceRepaymentRepositoryMapper sqlMapper,
            JsonUtil json,
            JdbcAdvanceRepaymentCheckRepository checks,
            JdbcAdvanceReceiptCreditRepository credits) {
        this.sqlMapper = sqlMapper;
        this.json = json;
        this.checks = checks;
        this.credits = credits;
    }

    /** 原确认、借款余额、规范化防重键及审计必须一起提交。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(AdvanceRepayment value, EmployeeAdvance after) {
        var check =
                checks.find(value.tenantId(), value.checkId())
                        .orElseThrow(JdbcAdvanceRepaymentRepository::changed);
        if (check.status() != AdvanceRepaymentCheck.Status.RECORDED
                || !value.id().equals(check.repaymentId())
                || !value.receipt().equals(check.receipt())
                || !value.recordedBy().equals(check.input().requestedBy())
                || !value.recordedAt().equals(check.updatedAt())) throw changed();
        var previous = revision(after.tenantId(), after.id(), after.version() - 1);
        previous.repay(previous.version(), value);
        if (!previous.state().equals(after.state())
                || !revision(after.tenantId(), after.id(), after.version())
                        .state()
                        .equals(after.state())) throw changed();
        var receipt = value.receipt();
        var request = receipt.request();
        try {
            sqlMapper.create(
                    value.tenantId(),
                    value.id().toString(),
                    request.advanceId().toString(),
                    after.version(),
                    value.checkId().toString(),
                    check.version(),
                    request.legalEntityId().toString(),
                    request.receiptReference(),
                    receipt.funding().channel().name(),
                    receipt.funding().transactionReference(),
                    receipt.posting().voucherReference(),
                    receipt.posting().entryReference(),
                    value.amount().value(),
                    value.amount().currency(),
                    value.recordedBy(),
                    Timestamp.from(value.recordedAt()),
                    json.write(value));
            credits.record(value);
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException(
                    "ADVANCE_REPAYMENT_ALREADY_RECORDED",
                    "Receipt, received funds or accounting entry has already been recorded");
        }
    }

    /** 同法人外部收款编号唯一，不因更换借款或查询编号再次入账。 */
    public Optional<AdvanceRepayment> forReceipt(
            String tenant, UUID legalEntity, String reference) {
        return SqlRows.map(sqlMapper.forReceipt(tenant, legalEntity.toString(), reference), row())
                .stream()
                .findFirst();
    }

    /** 独立复核先取得原还款事实，再核验同一借款的实时读取权限。 */
    public Optional<AdvanceRepayment> find(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.find(tenant, id.toString()), row()).stream().findFirst();
    }

    /** 有界历史按发生时间和编号稳定翻页；游标必须属于同一借款。 */
    public List<AdvanceRepayment> list(String tenant, UUID advanceId, UUID before, int limit) {
        var args = new java.util.ArrayList<Object>(List.of(tenant, advanceId.toString()));
        String filter = "";
        if (before != null) {
            var dates = sqlMapper.list(tenant, advanceId.toString(), before.toString());
            if (dates.isEmpty())
                throw new DomainException(
                        "INVALID_ADVANCE_REPAYMENT_QUERY",
                        "Repayment cursor must belong to this advance");
            filter = " AND (recorded_at<? OR (recorded_at=? AND id<?))";
            args.add(dates.get(0));
            args.add(dates.get(0));
            args.add(before.toString());
        }
        args.add(limit);
        return SqlRows.map(sqlMapper.listQuery((before != null), args.toArray()), row());
    }

    /** 按原登记恢复被消费查询及相邻借款修订，最新余额不能替代历史登记。 */
    public Optional<Recorded> findRecorded(String tenant, UUID id) {
        return SqlRows.map(
                        sqlMapper.findRecorded(tenant, id.toString()),
                        row -> {
                            var repayment = row().apply(row);
                            var advanceId = repayment.receipt().request().advanceId();
                            long version = row.getLong("advance_version"),
                                    checkVersion = row.getLong("check_version");
                            var before = revision(tenant, advanceId, version - 1);
                            var after = revision(tenant, advanceId, version);
                            var prior =
                                    json.read(
                                            row.getString("prior_check_json"),
                                            AdvanceRepaymentCheck.class);
                            var recorded =
                                    json.read(
                                            row.getString("recorded_check_json"),
                                            AdvanceRepaymentCheck.class);
                            if (!repayment.id().equals(id)
                                    || !repayment.tenantId().equals(tenant)
                                    || before.version() != version - 1
                                    || after.version() != version
                                    || !before.tenantId().equals(tenant)
                                    || !after.tenantId().equals(tenant)
                                    || !before.id().equals(advanceId)
                                    || !after.id().equals(advanceId)
                                    || prior.version() != checkVersion - 1
                                    || recorded.version() != checkVersion
                                    || !prior.input().id().equals(repayment.checkId())
                                    || !prior.input().tenantId().equals(tenant)
                                    || !prior.record(repayment, repayment.recordedAt())
                                            .equals(recorded)) throw changed();
                            before.repay(before.version(), repayment);
                            if (!before.state().equals(after.state())) throw changed();
                            return new Recorded(version, repayment);
                        })
                .stream()
                .findFirst();
    }

    /**
     * 该次登记对应的借款修订，不提供当前可用额或办理许可。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Recorded(long advanceVersion, AdvanceRepayment repayment) {}

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
                .orElseThrow(JdbcAdvanceRepaymentRepository::changed);
    }

    private Function<SqlRow, AdvanceRepayment> row() {
        return row -> {
            var value = json.read(row.getString("state_json"), AdvanceRepayment.class);
            var receipt = value.receipt();
            var source = receipt.request();
            if (!value.tenantId().equals(row.getString("tenant_id"))
                    || !value.id().toString().equals(row.getString("id"))
                    || !source.advanceId().toString().equals(row.getString("advance_id"))
                    || !value.checkId().toString().equals(row.getString("check_id"))
                    || !source.legalEntityId().toString().equals(row.getString("legal_entity_id"))
                    || !source.receiptReference().equals(row.getString("receipt_reference"))
                    || !receipt.funding().channel().name().equals(row.getString("channel"))
                    || !receipt.funding()
                            .transactionReference()
                            .equals(row.getString("transaction_reference"))
                    || !receipt.posting()
                            .voucherReference()
                            .equals(row.getString("voucher_reference"))
                    || !receipt.posting().entryReference().equals(row.getString("entry_reference"))
                    || value.amount().value().compareTo(row.getBigDecimal("amount")) != 0
                    || !value.amount().currency().equals(row.getString("currency"))
                    || !value.recordedBy().equals(row.getString("recorded_by"))
                    || !value.recordedAt()
                            .truncatedTo(ChronoUnit.MICROS)
                            .equals(row.getTimestamp("recorded_at").toInstant()))
                throw new IllegalStateException("Persisted repayment identity is inconsistent");
            return value;
        };
    }

    private static DomainException changed() {
        return new DomainException(
                "ADVANCE_REPAYMENT_SOURCE_CHANGED",
                "Recorded repayment must match the consumed check and both original advance"
                    + " revisions");
    }
}
