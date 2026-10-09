package io.agentflow.expense;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.ExpenseResourceAdjustmentAuditMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 准备、授权及后续人工办理使用一致审计定位，不复制原账户或完整预算分摊。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class ExpenseResourceAdjustmentAudit {
    private final ExpenseResourceAdjustmentAuditMapper sqlMapper;
    private final JsonUtil json;
    private final CurrentActor actors;

    /** 审计身份取当前认证上下文，不能由请求体替换。 */
    public ExpenseResourceAdjustmentAudit(
            ExpenseResourceAdjustmentAuditMapper sqlMapper, JsonUtil json, CurrentActor actors) {
        this.sqlMapper = sqlMapper;
        this.json = json;
        this.actors = actors;
    }

    /** 审计与对应版本变化共用事务，回滚后不会留下成功办理记录。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(
            ExpenseResourceAdjustmentBasis basis,
            UUID id,
            long version,
            String action,
            String comment,
            Instant at) {
        var source = basis.settlement().input().source();
        var event = UUID.randomUUID();
        sqlMapper.record(
                UUID.randomUUID().toString(),
                basis.tenantId(),
                event.toString(),
                id.toString(),
                version,
                source.applicationId().toString(),
                action,
                actors.actor().userId(),
                json.write(
                        Map.of(
                                "reportId",
                                basis.reportId(),
                                "roundNo",
                                source.roundNo(),
                                "authorizedRole",
                                "FINANCE",
                                "comment",
                                comment)),
                Timestamp.from(at));
        return event;
    }
}
