package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.ExpensePaymentReturnsRepositoryMapper;
import io.agentflow.finance.ExpensePaymentReturnPort;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 报销退回的当前账本与不可变修订，共用报销锁但不改写原结算输入。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpensePaymentReturnsRepository {
    private final ExpensePaymentReturnsRepositoryMapper sqlMapper;
    private final JsonUtil json;

    public JdbcExpensePaymentReturnsRepository(
            ExpensePaymentReturnsRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 首次只能保存无退回、无冻结的原始查询意图。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ExpensePaymentReturns value) {
        if (!value.equals(ExpensePaymentReturns.open(value.request(), value.createdAt())))
            throw conflict();
        var command = value.request().command();
        sqlMapper.create(
                command.tenantId(),
                command.binding().businessId().toString(),
                command.id().toString(),
                json.write(value.request()),
                json.write(value),
                Timestamp.from(value.createdAt()),
                Timestamp.from(value.updatedAt()));
        append(value);
    }

    /** 版本、原付款及创建时刻共同锁定；并发登记不能丢失此前入款。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(ExpensePaymentReturns value) {
        var command = value.request().command();
        int changed =
                sqlMapper.update(
                        json.write(value),
                        value.version(),
                        value.reviewRequired(),
                        Timestamp.from(value.updatedAt()),
                        command.tenantId(),
                        command.binding().businessId().toString(),
                        value.version() - 1,
                        json.write(value.request()),
                        Timestamp.from(value.createdAt()));
        if (changed != 1) throw conflict();
        append(value);
    }

    /** 当前状态仅按租户和原报销定位。 */
    public Optional<ExpensePaymentReturns> find(String tenant, UUID reportId) {
        return SqlRows.map(sqlMapper.find(tenant, reportId.toString()), row()).stream().findFirst();
    }

    /** 财务决定必须可重放到已保存的前后修订。 */
    public ExpensePaymentReturns revision(String tenant, UUID reportId, long version) {
        return SqlRows.map(
                        sqlMapper.revision(tenant, reportId.toString(), version),
                        row -> json.read(row.getString("state_json"), ExpensePaymentReturns.class))
                .stream()
                .findFirst()
                .orElseThrow(JdbcExpensePaymentReturnsRepository::conflict);
    }

    private Function<SqlRow, ExpensePaymentReturns> row() {
        return row -> {
            var value = json.read(row.getString("state_json"), ExpensePaymentReturns.class);
            var command = value.request().command();
            if (!command.tenantId().equals(row.getString("tenant_id"))
                    || !command.binding().businessId().toString().equals(row.getString("report_id"))
                    || !command.id().toString().equals(row.getString("payment_id"))
                    || !value.request()
                            .equals(
                                    json.read(
                                            row.getString("input_json"),
                                            ExpensePaymentReturnPort.Request.class))
                    || value.version() != row.getLong("version")
                    || value.reviewRequired() != row.getBoolean("review_required")
                    || !value.createdAt().equals(row.getTimestamp("created_at").toInstant())
                    || !value.updatedAt().equals(row.getTimestamp("updated_at").toInstant())) {
                throw new IllegalStateException(
                        "Persisted expense return ledger identity is inconsistent");
            }
            return value;
        };
    }

    private void append(ExpensePaymentReturns value) {
        var command = value.request().command();
        sqlMapper.append(
                command.tenantId(),
                command.binding().businessId().toString(),
                value.version(),
                json.write(value));
    }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Expense return ledger version or original payment changed"); }
}
