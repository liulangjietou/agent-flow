package io.agentflow.approval.process;

import io.agentflow.common.Actor;
import java.util.Collections;
import java.util.List;

/**
 * Flowable 7.2 的参与关系只读防腐层，对齐现有详情的引擎查询语义。
 * @author owlzhangfq@gmail.com
 */
public final class FlowableApplicationParticipationSql {
    private FlowableApplicationParticipationSql() { }

    /** 以申请表 a 为关联边界，绑定当前用户及角色，不将候选关系复制为永久授权。 */
    public static String predicate(Actor actor, List<Object> parameters) {
        var sql = new StringBuilder("""
                (EXISTS (SELECT 1 FROM ACT_RU_TASK t
                    WHERE EXISTS (SELECT 1 FROM ACT_RU_VARIABLE v WHERE v.PROC_INST_ID_=t.PROC_INST_ID_
                        AND v.TASK_ID_ IS NULL AND v.NAME_='applicationId' AND v.TYPE_='string' AND v.TEXT_=a.id)
                    AND EXISTS (SELECT 1 FROM ACT_RU_VARIABLE v WHERE v.PROC_INST_ID_=t.PROC_INST_ID_
                        AND v.TASK_ID_ IS NULL AND v.NAME_='tenantId' AND v.TYPE_='string' AND v.TEXT_=a.tenant_id)
                    AND (t.ASSIGNEE_=? OR EXISTS (SELECT 1 FROM ACT_RU_IDENTITYLINK i
                        WHERE i.TASK_ID_=t.ID_ AND (i.USER_ID_=?
                """);
        parameters.add(actor.userId()); parameters.add(actor.userId());
        // 详情允许所有当前身份关联（包含委托所有者），不能收窄为未认领任务的候选人。
        if (!actor.roles().isEmpty()) {
            sql.append(" OR i.GROUP_ID_ IN (").append(String.join(",", Collections.nCopies(actor.roles().size(), "?"))).append(")");
            parameters.addAll(actor.roles().stream().sorted().toList());
        }
        sql.append("""
                )))) OR EXISTS (SELECT 1 FROM ACT_HI_TASKINST h WHERE h.ASSIGNEE_=?
                    AND EXISTS (SELECT 1 FROM ACT_HI_VARINST v WHERE v.PROC_INST_ID_=h.PROC_INST_ID_
                        AND v.TASK_ID_ IS NULL AND v.NAME_='applicationId' AND v.VAR_TYPE_='string' AND v.TEXT_=a.id)
                    AND EXISTS (SELECT 1 FROM ACT_HI_VARINST v WHERE v.PROC_INST_ID_=h.PROC_INST_ID_
                        AND v.TASK_ID_ IS NULL AND v.NAME_='tenantId' AND v.VAR_TYPE_='string' AND v.TEXT_=a.tenant_id)))
                """);
        parameters.add(actor.userId());
        return sql.toString();
    }
}
