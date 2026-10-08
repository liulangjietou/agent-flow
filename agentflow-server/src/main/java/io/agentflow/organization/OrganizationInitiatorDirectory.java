package io.agentflow.organization;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.organization.mapper.OrganizationInitiatorDirectoryMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 仅向本人提供有效任职；提交时锁定目录并重新解析，页面选项不能代替当前授权。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class OrganizationInitiatorDirectory {
    private final OrganizationInitiatorDirectoryMapper sqlMapper;
    private final OrganizationRepository repository;

    /** 复用组织目录锁和租户数据源，不提供按任意主体查询的公开入口。 */
    public OrganizationInitiatorDirectory(
            OrganizationInitiatorDirectoryMapper sqlMapper, OrganizationRepository repository) {
        this.sqlMapper = sqlMapper;
        this.repository = repository;
    }

    /** 有界读取当前账号的任职；申请人不需要具备审批资格或管理员权限。 */
    @Transactional(readOnly = true)
    public List<InitiatorContext> options(Actor actor, String afterId, int limit) {
        return SqlRows.map(
                sqlMapper.options(actor.tenantId(), actor.userId(), afterId, limit + 1), this::map);
    }

    /** 目录锁持续到提交事务结束；他人、跨租户或停用任职统一拒绝。 */
    @Transactional
    public InitiatorContext snapshot(Actor actor, UUID appointmentId) {
        if (appointmentId == null) return null;
        return findCurrent(actor, appointmentId)
                .orElseThrow(() -> new DomainException("INITIATOR_APPOINTMENT_UNAVAILABLE", "Selected initiator appointment is not available"));
    }

    /** 后台预检把失效任职记录为检查结果，不通过事务代理抛异常后再尝试保存该结果。 */
    @Transactional
    public Optional<InitiatorContext> findCurrent(Actor actor, UUID appointmentId) {
        repository.lock(actor.tenantId());
        return SqlRows.map(
                        sqlMapper.findCurrent(
                                actor.tenantId(), actor.userId(), appointmentId.toString()),
                        this::map)
                .stream()
                .findFirst();
    }

    private InitiatorContext map(SqlRow row) {
        return new InitiatorContext(UUID.fromString(row.getString("id")), UUID.fromString(row.getString("person_id")),
                row.getString("subject"), row.getLong("directory_revision"), UUID.fromString(row.getString("legal_entity_id")),
                row.getString("legal_entity_name"), UUID.fromString(row.getString("department_id")), row.getString("department_name"),
                UUID.fromString(row.getString("position_id")), row.getString("position_name"));
    }
}
