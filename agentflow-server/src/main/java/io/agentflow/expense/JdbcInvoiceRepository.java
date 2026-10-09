package io.agentflow.expense;


import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.InvoiceRepositoryMapper;
import io.agentflow.mybatis.SqlRows;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * 发票仓储以有效占用键实现租户内互斥；不同上传原件也不能并发报销同一张发票。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcInvoiceRepository implements InvoiceRepository {
    private static final FinancialResourceStore.Kind KIND = FinancialResourceStore.Kind.INVOICE;
    private final FinancialResourceStore store;
    private final InvoiceRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 互斥键与发票状态使用同一数据库和事务。 */
    public JdbcInvoiceRepository(
            FinancialResourceStore store, InvoiceRepositoryMapper sqlMapper, JsonUtil json) {
        this.store = store;
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    @Override @Transactional
    public void create(Invoice invoice, String actor) {
        if (invoice.verification() != Invoice.Verification.PENDING || invoice.occupation() != Invoice.Occupation.AVAILABLE) throw new DomainException("INVALID_INVOICE", "New invoice must begin with an unverified original");
        store.create(KIND, stored(invoice), actor);
    }

    @Override @Transactional
    public void update(Invoice invoice, long expectedVersion, String actor, String operation) {
        store.update(KIND, stored(invoice), expectedVersion, actor, operation);
        syncClaim(invoice);
    }

    @Override
    public Optional<Invoice> find(String tenantId, UUID id) {
        return store.find(KIND, tenantId, id).map(this::decode);
    }

    @Override
    public java.util.Map<UUID, Invoice> findAll(String tenantId, java.util.Collection<UUID> ids) {
        return store.findAll(KIND, tenantId, ids).stream().map(this::decode)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Invoice::id, value -> value));
    }

    private Invoice decode(FinancialResourceStore.Stored row) {
        var value = Invoice.restore(json.read(row.state(), Invoice.State.class));
        row.requireConsistent(stored(value)); return value;
    }

    private void syncClaim(Invoice invoice) {
        var previous =
                SqlRows.map(
                                sqlMapper.syncClaim(invoice.tenantId(), invoice.id().toString()),
                                row ->
                                        new Claim(
                                                row.getString("invoice_key"),
                                                new ExpenseUse(
                                                        UUID.fromString(row.getString("report_id")),
                                                        row.getInt("round_no"),
                                                        row.getInt("line_no")),
                                                row.getString("status")))
                        .stream()
                        .findFirst()
                        .orElse(null);
        if (previous != null
                && "CONSUMED".equals(previous.status())
                && (invoice.occupation() != Invoice.Occupation.CONSUMED
                        || !previous.use().equals(invoice.use()))) {
            if (invoice.occupation() != Invoice.Occupation.AVAILABLE
                    || invoice.verification() != Invoice.Verification.PENDING
                    || SqlRows.single(
                                    sqlMapper.syncClaim2(
                                            invoice.tenantId(),
                                            invoice.id().toString(),
                                            invoice.version(),
                                            previous.use().reportId().toString(),
                                            previous.use().roundNo(),
                                            previous.use().lineNo()))
                            != 1) {
                throw new DomainException(
                        "INVOICE_OCCUPATION_CHANGED",
                        "Consumed invoice occupation requires an authorized reversal before"
                            + " release");
            }
            sqlMapper.syncClaim3(invoice.tenantId(), invoice.id().toString());
            return;
        }
        if (invoice.occupation() == Invoice.Occupation.AVAILABLE) {
            sqlMapper.syncClaim4(invoice.tenantId(), invoice.id().toString());
            return;
        }
        String key = invoice.facts().key().canonical();
        var use = invoice.use();
        if (previous != null) {
            if (!key.equals(previous.key()))
                throw new DomainException(
                        "INVOICE_FACTS_CHANGED", "Occupied invoice canonical identity changed");
            sqlMapper.syncClaim5(
                    use.reportId().toString(),
                    use.roundNo(),
                    use.lineNo(),
                    invoice.occupation().name(),
                    invoice.tenantId(),
                    invoice.id().toString());
        } else {
            try {
                sqlMapper.syncClaim6(
                        invoice.tenantId(),
                        key,
                        invoice.id().toString(),
                        use.reportId().toString(),
                        use.roundNo(),
                        use.lineNo(),
                        invoice.occupation().name());
            } catch (DuplicateKeyException occupied) {
                throw new DomainException(
                        "INVOICE_OCCUPIED",
                        "Invoice already has an active occupation in this tenant");
            }
        }
    }

    private FinancialResourceStore.Stored stored(Invoice invoice) {
        return new FinancialResourceStore.Stored(invoice.id(), invoice.tenantId(), invoice.ownerId(), invoice.originalFileId().toString(), invoice.version(), invoice.originalDigest(), json.write(invoice.state()));
    }

    /**
     * 已锁定发票对应的现行互斥键。
     *
     * @author owlzhangfq@gmail.com
     */
    private record Claim(String key, ExpenseUse use, String status) {}
}
