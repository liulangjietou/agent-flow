package io.agentflow.onboarding;


import io.agentflow.common.JsonUtil;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.onboarding.mapper.TenantInitializationRepositoryMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 首次配置凭据与操作审计原子追加；数据库唯一键和租户外键保留来源完整性。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcTenantInitializationRepository implements TenantInitializationRepository {
    private final TenantInitializationRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 使用既有业务数据源及统一 JSON 组件。 */
    public JdbcTenantInitializationRepository(
            TenantInitializationRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    @Override
    public Optional<TenantInitialization> find(String tenantId) {
        return SqlRows.map(
                        sqlMapper.find(tenantId),
                        row ->
                                json.read(
                                        row.getString("snapshot_json"), TenantInitialization.class))
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(TenantInitialization value) {
        sqlMapper.append(
                value.tenantId(),
                value.id().toString(),
                value.workspaceName(),
                value.initializedBy(),
                Timestamp.from(value.initializedAt()),
                value.organization().personId().toString(),
                value.organization().appointmentId().toString(),
                value.calendar().id().toString(),
                value.calendar().revision(),
                json.write(value));
        // 统一检索只需操作来源，详细快照留在受限初始化入口，不复制任何通知地址或凭据。
        sqlMapper.append2(
                UUID.randomUUID().toString(),
                value.tenantId(),
                UUID.randomUUID().toString(),
                value.id().toString(),
                value.initializedBy(),
                json.write(
                        Map.of(
                                "workspaceName",
                                value.workspaceName(),
                                "appointmentId",
                                value.organization().appointmentId(),
                                "calendarId",
                                value.calendar().id(),
                                "calendarRevision",
                                value.calendar().revision())),
                Timestamp.from(value.initializedAt()));
    }
}
