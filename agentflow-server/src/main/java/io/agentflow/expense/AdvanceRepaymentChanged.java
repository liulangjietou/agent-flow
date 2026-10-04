package io.agentflow.expense;

/**
 * 原查询、登记或触发复核的事实保存后，在同一事务投影最小通知。
 * @author owlzhangfq@gmail.com
 */
public record AdvanceRepaymentChanged(AdvanceRepaymentCheck current) { }
