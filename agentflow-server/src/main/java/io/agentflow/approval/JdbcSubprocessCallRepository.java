package io.agentflow.approval;

import io.agentflow.approval.model.SubprocessCall;
import io.agentflow.approval.repository.SubprocessCallRepository;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.SubprocessPolicy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 父轮次可能在原生启动返回后才入库，调用记录先绑定父申请及实际实例，子轮次必须已经存在。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSubprocessCallRepository implements SubprocessCallRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;

    /** 关系与业务快照使用同一数据源和 JSON 契约。 */
    public JdbcSubprocessCallRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(SubprocessCall call) {
        try {
            jdbc.update("""
                    INSERT INTO approval_subprocess_call
                    (id,tenant_id,parent_application_id,parent_round_no,parent_instance_id,parent_runtime_definition_id,
                     node_id,node_name,activation_id,child_application_id,child_round_no,child_instance_id,
                     child_definition_id,child_process_key,child_definition_version,child_runtime_definition_id,policy_json,created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, call.id().toString(), call.tenantId(), call.parentApplicationId().toString(), call.parentRoundNo(),
                    call.parentProcessInstanceId(), call.parentRuntimeDefinitionId(), call.nodeId(), call.nodeName(), call.activationId(),
                    call.childApplicationId().toString(), SubprocessCall.CHILD_ROUND, call.childProcessInstanceId(),
                    call.childDefinitionId().toString(), call.policy().processKey(), call.policy().version(), call.childRuntimeDefinitionId(),
                    json.write(call.policy()), call.createdAt().atOffset(ZoneOffset.UTC));
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException("SUBPROCESS_CALL_EXISTS", "This subprocess activation already has a child application");
        }
    }

    @Override
    public Optional<SubprocessCall> findByChild(String tenantId, UUID childApplicationId) {
        return jdbc.query("SELECT * FROM approval_subprocess_call WHERE tenant_id=? AND child_application_id=?",
                this::map, tenantId, childApplicationId.toString()).stream().findFirst();
    }

    @Override
    public Optional<SubprocessCall> findByActivation(String tenantId, String parentInstanceId, String activationId) {
        return jdbc.query("SELECT * FROM approval_subprocess_call WHERE tenant_id=? AND parent_instance_id=? AND activation_id=?",
                this::map, tenantId, parentInstanceId, activationId).stream().findFirst();
    }

    @Override
    public List<SubprocessCall> findByParentRound(String tenantId, UUID parentApplicationId, int roundNo) {
        return jdbc.query("SELECT * FROM approval_subprocess_call WHERE tenant_id=? AND parent_application_id=? AND parent_round_no=? ORDER BY created_at,id",
                this::map, tenantId, parentApplicationId.toString(), roundNo);
    }

    private SubprocessCall map(ResultSet row, int index) throws SQLException {
        return new SubprocessCall(UUID.fromString(row.getString("id")), row.getString("tenant_id"),
                UUID.fromString(row.getString("parent_application_id")), row.getInt("parent_round_no"),
                row.getString("parent_instance_id"), row.getString("parent_runtime_definition_id"), row.getString("node_id"),
                row.getString("node_name"), row.getString("activation_id"), UUID.fromString(row.getString("child_application_id")),
                row.getString("child_instance_id"), UUID.fromString(row.getString("child_definition_id")), row.getString("child_runtime_definition_id"),
                json.read(row.getString("policy_json"), SubprocessPolicy.class), row.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
