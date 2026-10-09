package io.agentflow.approval;

import io.agentflow.approval.mapper.ApplicationRepositoryMapper;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.workspace.ApplicationAmountProjection;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.form.FormSchema;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.notification.NotificationTexts;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL/H2 兼容的申请聚合仓储实现，所有查询均带租户条件。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcApplicationRepository implements ApplicationRepository {
    private final ApplicationRepositoryMapper sqlMapper;
    private final JsonUtil jsonUtil;

    /** 创建仓储。 */
    public JdbcApplicationRepository(ApplicationRepositoryMapper sqlMapper, JsonUtil jsonUtil) {
        this.sqlMapper = sqlMapper;
        this.jsonUtil = jsonUtil;
    }

    @Override
    public Application save(Application application) {
        sqlMapper.save(
                application.id().toString(),
                application.tenantId(),
                application.businessNo(),
                application.processKey(),
                application.definitionVersion(),
                application.createdBy(),
                application.title(),
                jsonUtil.write(application.payload()),
                application.status().name(),
                application.roundNo(),
                application.version(),
                application.formSchema() == null ? null : jsonUtil.write(application.formSchema()),
                application.runtimeDefinitionId(),
                jsonUtil.write(application.notificationTexts()),
                ApplicationAmountProjection.extract(
                        application.payload(), application.formSchema()),
                application.businessReference() == null
                        ? null
                        : application.businessReference().type().name(),
                application.businessReference() == null
                        ? null
                        : application.businessReference().id().toString());
        return application;
    }

    @Override
    public Optional<Application> findById(String tenantId, UUID id) {
        List<Application> rows =
                SqlRows.map(sqlMapper.findById(tenantId, id.toString()), this::map);
        return rows.stream().findFirst();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Optional<Application> lockById(String tenantId, UUID id) {
        return SqlRows.map(sqlMapper.lockById(tenantId, id.toString()), this::map).stream()
                .findFirst();
    }

    @Override
    public Optional<Application> findByBusinessNo(String tenantId, String businessNo) {
        List<Application> rows =
                SqlRows.map(sqlMapper.findByBusinessNo(tenantId, businessNo), this::map);
        return rows.stream().findFirst();
    }

    @Override
    public Application update(Application application, long expectedVersion) {
        int updated =
                sqlMapper.update(
                        application.status().name(),
                        application.roundNo(),
                        application.version(),
                        application.title(),
                        jsonUtil.write(application.payload()),
                        ApplicationAmountProjection.extract(
                                application.payload(), application.formSchema()),
                        application.tenantId(),
                        application.id().toString(),
                        expectedVersion);
        if (updated != 1) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Application version has changed");
        }
        return application;
    }

    @Override
    public List<Application> findAll(String tenantId) {
        return SqlRows.map(sqlMapper.findAll(tenantId), this::map);
    }

    private Application map(SqlRow resultSet) {
        return Application.restore(UUID.fromString(resultSet.getString("id")), resultSet.getString("tenant_id"),
                resultSet.getString("business_no"), resultSet.getString("process_key"),
                resultSet.getLong("definition_version"), resultSet.getString("created_by"),
                resultSet.getString("title"), jsonUtil.map(resultSet.getString("payload_json")),
                ApplicationStatus.valueOf(resultSet.getString("status")), resultSet.getInt("round_no"),
                resultSet.getLong("version"), resultSet.getString("form_schema_json") == null ? null
                        : jsonUtil.read(resultSet.getString("form_schema_json"), FormSchema.class), resultSet.getString("runtime_definition_id"),
                resultSet.getString("notification_texts_json") == null ? null
                        : jsonUtil.read(resultSet.getString("notification_texts_json"), NotificationTexts.class),
                resultSet.getString("business_type") == null ? null : new BusinessReference(
                        BusinessReference.Type.valueOf(resultSet.getString("business_type")), UUID.fromString(resultSet.getString("business_id"))));
    }
}
