package io.agentflow.definition;

import io.agentflow.common.DomainException;
import java.util.List;

/**
 * 保留结构化规则码，供设计器定位节点与连线，不混入测试数据。
 * @author owlzhangfq@gmail.com
 */
public final class DefinitionValidationException extends DomainException {
    private final List<String> errors;

    /** 保存本次图校验发现的规则错误。 */
    public DefinitionValidationException(List<String> errors) {
        super("INVALID_DEFINITION", String.join(",", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() { return errors; }
}
