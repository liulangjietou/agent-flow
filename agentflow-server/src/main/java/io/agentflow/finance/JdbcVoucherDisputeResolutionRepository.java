package io.agentflow.finance;


import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.mapper.VoucherDisputeResolutionRepositoryMapper;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * 裁决凭据只追加，原冲突及结果均关联租户内不可变凭证修订。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcVoucherDisputeResolutionRepository {
    private final VoucherDisputeResolutionRepositoryMapper sqlMapper;
    private final JsonUtil json;
    private final JdbcVoucherOperationRepository operations;

    /** 保存前重读两份修订，拒绝只有人工说明而没有会计事实的伪裁决。 */
    public JdbcVoucherDisputeResolutionRepository(
            VoucherDisputeResolutionRepositoryMapper sqlMapper,
            JsonUtil json,
            JdbcVoucherOperationRepository operations) {
        this.sqlMapper = sqlMapper;
        this.json = json;
        this.operations = operations;
    }

    /** 与凭证版本变更及后续结算复核共用同一事务，不提供更新或删除入口。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(VoucherDisputeResolution value) {
        var before =
                operations
                        .revision(value.tenantId(), value.operationId(), value.disputedVersion())
                        .orElseThrow(JdbcVoucherDisputeResolutionRepository::mismatch);
        var after =
                operations
                        .revision(value.tenantId(), value.operationId(), value.resolvedVersion())
                        .orElseThrow(JdbcVoucherDisputeResolutionRepository::mismatch);
        if (!value.matches(before, after)) throw mismatch();
        sqlMapper.create(
                value.tenantId(),
                value.id().toString(),
                value.operationId().toString(),
                value.disputedVersion(),
                value.resolvedVersion(),
                value.observation().status().name(),
                value.resolvedBy(),
                timestamp(value.observation().observedAt()),
                timestamp(value.resolvedAt()),
                json.write(value));
    }

    /** 页面仅展示最后一次决定，历史决定及关联修订均继续保留。 */
    public Optional<VoucherDisputeResolution> latest(String tenant, UUID operationId) {
        return SqlRows.map(
                        sqlMapper.latest(tenant, operationId.toString()),
                        row -> {
                            var value =
                                    json.read(
                                            row.getString("state_json"),
                                            VoucherDisputeResolution.class);
                            if (!tenant.equals(value.tenantId())
                                    || !operationId.equals(value.operationId())
                                    || !value.id().toString().equals(row.getString("id"))
                                    || value.disputedVersion() != row.getLong("disputed_version")
                                    || value.resolvedVersion() != row.getLong("resolved_version")
                                    || !value.observation()
                                            .status()
                                            .name()
                                            .equals(row.getString("outcome"))
                                    || !value.resolvedBy().equals(row.getString("resolved_by"))
                                    || !value.observation()
                                            .observedAt()
                                            .truncatedTo(ChronoUnit.MICROS)
                                            .equals(row.getTimestamp("observed_at").toInstant())
                                    || !value.resolvedAt()
                                            .truncatedTo(ChronoUnit.MICROS)
                                            .equals(row.getTimestamp("resolved_at").toInstant())) {
                                throw new IllegalStateException(
                                        "Persisted dispute resolution identity is inconsistent");
                            }
                            return value;
                        })
                .stream()
                .findFirst();
    }

    // 外部原件保留纳秒；关系列使用数据库可重复保存的微秒，避免隐式四舍五入改变身份校验。
    private static Timestamp timestamp(Instant value) { return Timestamp.from(value.truncatedTo(ChronoUnit.MICROS)); }

    private static DomainException mismatch() { return new DomainException("VOUCHER_DISPUTE_EVIDENCE_CHANGED", "Resolution must match both persisted original voucher revisions"); }
}
