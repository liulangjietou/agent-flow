package io.agentflow.expense;

/**
 * 原还款复核查询及实际裁决持久化后，同事务生成最小通知。
 * @author owlzhangfq@gmail.com
 */
public record AdvanceRepaymentReviewChanged(AdvanceRepaymentReviewCheck current) { }
