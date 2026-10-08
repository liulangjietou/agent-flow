package io.agentflow.finance;

import io.agentflow.common.JsonUtil;
import io.agentflow.finance.mapper.PaymentBatchRepositoryMapper;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 不可变批次和原授权、执行请求第一版共同保存，成员不能重复归入另一个批次。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcPaymentBatchRepository {
    private final PaymentBatchRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 与原出纳执行登记共用同一事务，不执行任何外部资金操作。 */
    public JdbcPaymentBatchRepository(PaymentBatchRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 任一成员或历史依据不一致时整批回滚，包括此前已经登记的执行意图。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(PaymentBatch value) {
        sqlMapper.create(
                value.tenantId(),
                value.id().toString(),
                value.legalEntityId().toString(),
                value.currency(),
                value.cashier(),
                value.items().size(),
                new BigDecimal(value.total()),
                Timestamp.from(value.createdAt()),
                json.write(value));
        for (int index = 0; index < value.items().size(); index++) {
            var item = value.items().get(index);
            sqlMapper.create2(
                    value.tenantId(),
                    value.id().toString(),
                    index + 1,
                    item.authorizationId().toString(),
                    item.authorizationVersion(),
                    item.requestId().toString());
        }
        verifyOriginalRegistrations(value);
    }

    /** 详情恢复始终核验原第一版证据，不要求单笔付款永远停留在初始状态。 */
    public Optional<PaymentBatch> find(String tenant, UUID id) {
        var result = SqlRows.map(sqlMapper.find(tenant, id.toString()), row()).stream().findFirst();
        result.ifPresent(this::verifyOriginalRegistrations);
        return result;
    }

    /** 目录只读取不可变批次摘要，在 SQL 分页之前限定当前法人任职。 */
    public List<PaymentBatch> page(String tenant, String cashier, PaymentBatch before, int limit) {
        var args = new ArrayList<Object>(List.of(tenant, tenant, cashier));
        var filter = "";
        if (before != null) {
            filter = " AND (created_at<? OR (created_at=? AND id<?))";
            args.add(Timestamp.from(before.createdAt()));
            args.add(Timestamp.from(before.createdAt()));
            args.add(before.id().toString());
        }
        args.add(limit + 1);
        return SqlRows.map(sqlMapper.pageQuery((before != null), args.toArray()), row());
    }

    private void verifyOriginalRegistrations(PaymentBatch value) {
        var registrations =
                SqlRows.map(
                        sqlMapper.verifyOriginalRegistrations(
                                value.tenantId(), value.id().toString()),
                        (row, index) -> {
                            if (index >= value.items().size() || row.getInt("line_no") != index + 1)
                                throw inconsistent();
                            var item = value.items().get(index);
                            if (!item.authorizationId()
                                            .toString()
                                            .equals(row.getString("authorization_id"))
                                    || item.authorizationVersion()
                                            != row.getLong("authorization_version")
                                    || !item.requestId()
                                            .toString()
                                            .equals(row.getString("request_id")))
                                throw inconsistent();
                            return new PaymentBatch.Registration(
                                    json.read(
                                            row.getString("authorization_json"),
                                            PaymentAuthorization.class),
                                    json.read(
                                            row.getString("request_json"),
                                            PaymentExecutionRequest.class));
                        });
        if (!PaymentBatch.submitted(value.id(), value.comment(), registrations, value.createdAt())
                .equals(value)) throw inconsistent();
    }

    private Function<SqlRow, PaymentBatch> row() {
        return row -> {
            var value = json.read(row.getString("state_json"), PaymentBatch.class);
            if (!value.id().toString().equals(row.getString("id"))
                    || !value.tenantId().equals(row.getString("tenant_id"))
                    || !value.legalEntityId().toString().equals(row.getString("legal_entity_id"))
                    || !value.currency().equals(row.getString("currency"))
                    || !value.cashier().equals(row.getString("cashier_id"))
                    || value.items().size() != row.getInt("item_count")
                    || new BigDecimal(value.total()).compareTo(row.getBigDecimal("total_value"))
                            != 0
                    || !value.createdAt().equals(row.getTimestamp("created_at").toInstant()))
                throw inconsistent();
            return value;
        };
    }

    private static IllegalStateException inconsistent() { return new IllegalStateException("Persisted payment batch does not match its original execution registrations"); }
}
