package io.agentflow.approval.process;

/**
 * 个人待办与租户运营共用的引擎事实边界，绑定租户、申请和当前轮次。
 * @author owlzhangfq@gmail.com
 */
public final class FlowableActiveTaskSql {
    private static final String APPLICATION_JOIN = """
            FROM ACT_RU_TASK t JOIN approval_application a ON EXISTS (
                SELECT 1 FROM ACT_RU_VARIABLE v WHERE v.PROC_INST_ID_=t.PROC_INST_ID_
                AND v.EXECUTION_ID_=t.PROC_INST_ID_ AND v.TASK_ID_ IS NULL AND v.NAME_='applicationId' AND v.TEXT_=a.id)
            """;
    private static final String CURRENT_SCOPE = """
            WHERE a.tenant_id=? AND a.status='IN_APPROVAL' AND t.SUSPENSION_STATE_=1
            AND (t.TENANT_ID_ IS NULL OR t.TENANT_ID_='' OR t.TENANT_ID_=a.tenant_id)
            AND EXISTS (SELECT 1 FROM ACT_RU_VARIABLE v WHERE v.PROC_INST_ID_=t.PROC_INST_ID_
                AND v.EXECUTION_ID_=t.PROC_INST_ID_ AND v.TASK_ID_ IS NULL AND v.NAME_='tenantId' AND v.TEXT_=a.tenant_id)
            AND EXISTS (SELECT 1 FROM ACT_RU_VARIABLE v WHERE v.PROC_INST_ID_=t.PROC_INST_ID_
                AND v.EXECUTION_ID_=t.PROC_INST_ID_ AND v.TASK_ID_ IS NULL AND v.NAME_='roundNo' AND v.LONG_=a.round_no)
            """;

    private FlowableActiveTaskSql() { }

    /** 返回 t/a 别名的只读联查片段，第一个绑定参数始终为认证租户。 */
    public static String fromCurrentApplications() { return APPLICATION_JOIN + CURRENT_SCOPE; }

    /** 仅关联该真实实例的当前轮次；旧任务没有快照时仍保留待办，不补造组织归属。 */
    public static String fromCurrentApplicationsWithRound() {
        return APPLICATION_JOIN + """
                LEFT JOIN approval_submission_round r ON r.tenant_id=a.tenant_id AND r.application_id=a.id
                AND r.round_no=a.round_no AND r.process_instance_id=t.PROC_INST_ID_ AND r.status='IN_APPROVAL'
                """ + CURRENT_SCOPE;
    }
}
