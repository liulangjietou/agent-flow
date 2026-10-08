package io.agentflow.approval;

import io.agentflow.approval.mapper.SubprocessCallRepositoryMapper;
import io.agentflow.approval.model.SubprocessCall;
import io.agentflow.approval.repository.SubprocessCallRepository;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.SubprocessPolicy;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 父轮次可能在原生启动返回后才入库，调用记录先绑定父申请及实际实例，子轮次必须已经存在。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSubprocessCallRepository implements SubprocessCallRepository {
    private final SubprocessCallRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 关系与业务快照使用同一数据源和 JSON 契约。 */
    public JdbcSubprocessCallRepository(SubprocessCallRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(SubprocessCall call) {
        try {
            sqlMapper.append(
                    call.id().toString(),
                    call.tenantId(),
                    call.parentApplicationId().toString(),
                    call.parentRoundNo(),
                    call.parentProcessInstanceId(),
                    call.parentRuntimeDefinitionId(),
                    call.nodeId(),
                    call.nodeName(),
                    call.activationId(),
                    call.childApplicationId().toString(),
                    SubprocessCall.CHILD_ROUND,
                    call.childProcessInstanceId(),
                    call.childDefinitionId().toString(),
                    call.policy().processKey(),
                    call.policy().version(),
                    call.childRuntimeDefinitionId(),
                    json.write(call.policy()),
                    call.createdAt().atOffset(ZoneOffset.UTC));
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException(
                    "SUBPROCESS_CALL_EXISTS",
                    "This subprocess activation already has a child application");
        }
    }

    @Override
    public Optional<SubprocessCall> findByChild(String tenantId, UUID childApplicationId) {
        return SqlRows.map(
                        sqlMapper.findByChild(tenantId, childApplicationId.toString()), this::map)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<SubprocessCall> findByActivation(
            String tenantId, String parentInstanceId, String activationId) {
        return SqlRows.map(
                        sqlMapper.findByActivation(tenantId, parentInstanceId, activationId),
                        this::map)
                .stream()
                .findFirst();
    }

    @Override
    public List<SubprocessCall> findByParentRound(
            String tenantId, UUID parentApplicationId, int roundNo) {
        return SqlRows.map(
                sqlMapper.findByParentRound(tenantId, parentApplicationId.toString(), roundNo),
                this::map);
    }

    @Override
    public List<SubprocessCall> pageByParentRound(
            String tenantId, UUID parentApplicationId, int roundNo, UUID afterId, int limit) {

        if (afterId == null)
            return SqlRows.map(
                    sqlMapper.pageByParentRoundQuery(
                            new Object[] {
                                tenantId, parentApplicationId.toString(), roundNo, limit
                            }),
                    this::map);
        var position =
                SqlRows.map(
                        sqlMapper.pageByParentRound(
                                tenantId,
                                parentApplicationId.toString(),
                                roundNo,
                                afterId.toString()),
                        row -> row.getObject("created_at", OffsetDateTime.class));
        if (position.isEmpty())
            throw new DomainException(
                    "INVALID_SUBPROCESS_QUERY", "Cursor does not belong to this application round");
        var time = position.get(0);
        return SqlRows.map(
                sqlMapper.pageByParentRoundQuery2(
                        new Object[] {
                            tenantId,
                            parentApplicationId.toString(),
                            roundNo,
                            time,
                            time,
                            afterId.toString(),
                            limit
                        }),
                this::map);
    }

    private SubprocessCall map(SqlRow row) {
        return new SubprocessCall(UUID.fromString(row.getString("id")), row.getString("tenant_id"),
                UUID.fromString(row.getString("parent_application_id")), row.getInt("parent_round_no"),
                row.getString("parent_instance_id"), row.getString("parent_runtime_definition_id"), row.getString("node_id"),
                row.getString("node_name"), row.getString("activation_id"), UUID.fromString(row.getString("child_application_id")),
                row.getString("child_instance_id"), UUID.fromString(row.getString("child_definition_id")), row.getString("child_runtime_definition_id"),
                json.read(row.getString("policy_json"), SubprocessPolicy.class), row.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
