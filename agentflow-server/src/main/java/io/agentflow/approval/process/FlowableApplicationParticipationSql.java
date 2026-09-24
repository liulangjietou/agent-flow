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

    /** 供已按认证租户筛选的申请表 a 使用；每次按实时参与关系计算可见集合。 */
    public static String predicate(Actor actor, List<Object> parameters) {
        // 租户参数来自认证上下文，与外层申请筛选一致。避免相关子查询逐申请扫描引擎表。
        // IN 保留存在性语义，多任务、多变量或多身份关联不会放大申请分页。
        var sql = new StringBuilder("""
                (a.id IN (SELECT v.TEXT_ FROM ACT_RU_VARIABLE v
                    JOIN ACT_RU_TASK t ON t.PROC_INST_ID_=v.PROC_INST_ID_
                    WHERE v.TASK_ID_ IS NULL AND v.NAME_='applicationId' AND v.TYPE_='string'
                    AND EXISTS (SELECT 1 FROM ACT_RU_VARIABLE tenant WHERE tenant.PROC_INST_ID_=t.PROC_INST_ID_
                        AND tenant.TASK_ID_ IS NULL AND tenant.NAME_='tenantId' AND tenant.TYPE_='string' AND tenant.TEXT_=?)
                    AND (t.ASSIGNEE_=? OR EXISTS (SELECT 1 FROM ACT_RU_IDENTITYLINK i
                        WHERE i.TASK_ID_=t.ID_ AND (i.USER_ID_=?
                """);
        parameters.add(actor.tenantId()); parameters.add(actor.userId()); parameters.add(actor.userId());
        // 详情允许所有当前身份关联（包含委托所有者），不能收窄为未认领任务的候选人。
        if (!actor.roles().isEmpty()) {
            sql.append(" OR i.GROUP_ID_ IN (").append(String.join(",", Collections.nCopies(actor.roles().size(), "?"))).append(")");
            parameters.addAll(actor.roles().stream().sorted().toList());
        }
        sql.append("""
                )))) OR a.id IN (SELECT v.TEXT_ FROM ACT_HI_VARINST v
                    JOIN ACT_HI_TASKINST h ON h.PROC_INST_ID_=v.PROC_INST_ID_
                    WHERE h.ASSIGNEE_=? AND v.TASK_ID_ IS NULL AND v.NAME_='applicationId' AND v.VAR_TYPE_='string'
                    AND EXISTS (SELECT 1 FROM ACT_HI_VARINST tenant WHERE tenant.PROC_INST_ID_=h.PROC_INST_ID_
                        AND tenant.TASK_ID_ IS NULL AND tenant.NAME_='tenantId' AND tenant.VAR_TYPE_='string' AND tenant.TEXT_=?)))
                """);
        parameters.add(actor.userId()); parameters.add(actor.tenantId());
        return sql.toString();
    }
}
