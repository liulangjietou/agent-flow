package io.agentflow.finance;

/**
 * 原外部冲销核对的持久状态；登记事件必须在独立登记记录保存之后发布。
 * @author owlzhangfq@gmail.com
 */
public record VoucherReversalCheckChanged(VoucherReversalCheck previous, VoucherReversalCheck current) { }
