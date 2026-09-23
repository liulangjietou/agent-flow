package io.agentflow.approval.process;

/**
 * 个人待办与租户运营共用的引擎事实边界，绑定租户、申请和当前轮次。
 * @author owlzhangfq@gmail.com
 */
public final class FlowableActiveTaskSql {
    private static final String FROM = """
            FROM ACT_RU_TASK t JOIN approval_application a ON EXISTS (
                SELECT 1 FROM ACT_RU_VARIABLE v WHERE v.PROC_INST_ID_=t.PROC_INST_ID_
                AND v.EXECUTION_ID_=t.PROC_INST_ID_ AND v.TASK_ID_ IS NULL AND v.NAME_='applicationId' AND v.TEXT_=a.id)
            WHERE a.tenant_id=? AND a.status='IN_APPROVAL' AND t.SUSPENSION_STATE_=1
            AND (t.TENANT_ID_ IS NULL OR t.TENANT_ID_='' OR t.TENANT_ID_=a.tenant_id)
            AND EXISTS (SELECT 1 FROM ACT_RU_VARIABLE v WHERE v.PROC_INST_ID_=t.PROC_INST_ID_
                AND v.EXECUTION_ID_=t.PROC_INST_ID_ AND v.TASK_ID_ IS NULL AND v.NAME_='tenantId' AND v.TEXT_=a.tenant_id)
            AND EXISTS (SELECT 1 FROM ACT_RU_VARIABLE v WHERE v.PROC_INST_ID_=t.PROC_INST_ID_
                AND v.EXECUTION_ID_=t.PROC_INST_ID_ AND v.TASK_ID_ IS NULL AND v.NAME_='roundNo' AND v.LONG_=a.round_no)
            """;

    private FlowableActiveTaskSql() { }

    /** 返回 t/a 别名的只读联查片段，第一个绑定参数始终为认证租户。 */
    public static String fromCurrentApplications() { return FROM; }
}
