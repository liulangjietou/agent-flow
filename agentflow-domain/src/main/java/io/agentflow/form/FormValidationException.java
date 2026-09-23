package io.agentflow.form;

import io.agentflow.common.DomainException;

import java.util.Map;

/**
 * 表单校验只返回字段及规则码，不将用户填写的值带入错误响应。
 * @author owlzhangfq@gmail.com
 */
public final class FormValidationException extends DomainException {
    private final Map<String, String> fieldErrors;

    /** 保存本次校验的字段错误。 */
    public FormValidationException(Map<String, String> fieldErrors) {
        super("FORM_VALIDATION_FAILED", "Form fields failed validation");
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> fieldErrors() { return fieldErrors; }
}
