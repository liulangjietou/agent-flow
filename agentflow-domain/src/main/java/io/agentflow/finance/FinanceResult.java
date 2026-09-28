package io.agentflow.finance;

import io.agentflow.common.DomainException;
import java.util.Objects;

/**
 * 财务端口明确区分真实业务拒绝与系统不可用；任何失败都不能被解释成查验或付款成功。
 * @author owlzhangfq@gmail.com
 */
public sealed interface FinanceResult<T> permits FinanceResult.Success, FinanceResult.Rejected, FinanceResult.Unavailable {
    /** 只读应用入口可以直接取得值，批量预检则逐项收集具体结果。 */
    @SuppressWarnings("unchecked")
    default T requireValue() {
        if (this instanceof Success<?> success) return (T) success.value();
        if (this instanceof Rejected<?> rejected) throw new DomainException("FINANCE_RULE_REJECTED", rejected.reason().name());
        throw new DomainException("FINANCE_GATEWAY_UNAVAILABLE", ((Unavailable<?>) this).failure().name());
    }

    /**
     * 真实响应且通过边界校验的成功值。
     * @author owlzhangfq@gmail.com
     */
    record Success<T>(T value) implements FinanceResult<T> {
        /** 空内容不代表成功。 */
        public Success { Objects.requireNonNull(value); }
    }
    /**
     * 企业业务规则的封闭拒绝分类，不透传远端文本或任意错误码。
     * @author owlzhangfq@gmail.com
     */
    record Rejected<T>(Reason reason) implements FinanceResult<T> {
        /** 每次拒绝必须有明确分类。 */
        public Rejected { Objects.requireNonNull(reason); }
    }
    /**
     * 网络、配置及协议不可用没有业务通过或驳回含义。
     * @author owlzhangfq@gmail.com
     */
    record Unavailable<T>(Failure failure) implements FinanceResult<T> {
        /** 失败不能默认为空目录或默认账户。 */
        public Unavailable { Objects.requireNonNull(failure); }
    }
    /**
     * 已支持端口的业务拒绝原因。
     * @author owlzhangfq@gmail.com
     */
    enum Reason { LEGAL_ENTITY_UNAVAILABLE, EMPLOYEE_UNAVAILABLE, ACCOUNT_UNAVAILABLE, RATE_UNAVAILABLE, POLICY_NOT_FOUND,
        EXPENSE_PROHIBITED, INVOICE_INVALID, INVOICE_CANCELLED, INVOICE_BUYER_MISMATCH, PRIOR_REQUEST_REQUIRED, COST_OBJECT_UNAVAILABLE,
        BUDGET_INSUFFICIENT, BUDGET_POLICY_UNAVAILABLE, ACCOUNTING_PERIOD_CLOSED, ACCOUNT_MAPPING_UNAVAILABLE }
    /**
     * 对外稳定的依赖失败分类。
     * @author owlzhangfq@gmail.com
     */
    enum Failure { NOT_CONFIGURED, TARGET_CHANGED, TIMEOUT, CONNECTION, AUTHENTICATION, REMOTE_FAILURE, INVALID_RESPONSE, RESPONSE_TOO_LARGE }
}
