package io.agentflow.definition;

import io.agentflow.common.DomainException;

/**
 * 使用从 1 开始的 UTF-16 字符位置定位语法错误，不回显条件中的业务值。
 * @author owlzhangfq@gmail.com
 */
public final class ConditionSyntaxException extends DomainException {
    private final int position;

    /** 保存错误位置，供设计器定位。 */
    public ConditionSyntaxException(int position, String message) {
        super("INVALID_CONDITION", message);
        this.position = position;
    }

    public int position() { return position; }
}
