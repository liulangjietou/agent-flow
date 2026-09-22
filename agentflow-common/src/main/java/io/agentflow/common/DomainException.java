package io.agentflow.common;

/**
 * 领域规则异常，向 API 层暴露稳定的业务错误码。
 * @author owlzhangfq@gmail.com
 */
public class DomainException extends RuntimeException {
    private final String code;

    /** 创建领域异常。 */
    public DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** 返回机器可读错误码。 */
    public String code() {
        return code;
    }
}
