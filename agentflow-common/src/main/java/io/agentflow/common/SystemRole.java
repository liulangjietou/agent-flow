package io.agentflow.common;

/**
 * 平台内置系统角色。认证层（演示登录或 OIDC 声明映射）把它们作为文本放入 {@link Actor#roles()}，
 * 业务代码一律通过本枚举判断，避免散落的角色字符串。
 * @author owlzhangfq@gmail.com
 */
public enum SystemRole {
    /** 普通员工，可发起申请。 */
    EMPLOYEE,
    /** 可被选为审批人。 */
    APPROVER,
    /** 部门主管。 */
    MANAGER,
    /** 财务办理：核定、凭证、付款授权等。 */
    FINANCE,
    /** 出纳：执行付款，与财务授权职责分离。 */
    CASHIER,
    /** 财务配置管理：费用制度、科目映射等版本化配置。 */
    FINANCE_CONFIG_ADMIN,
    /** 流程管理：设计、发布与停用流程定义。 */
    PROCESS_ADMIN,
    /** 平台管理员。 */
    ADMIN
}
