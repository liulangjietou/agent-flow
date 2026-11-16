package io.agentflow.common;

/**
 * 领域规则异常，向 API 层暴露稳定的业务错误码。
 * @author owlzhangfq@gmail.com
 */
public class DomainException extends RuntimeException {
    private final String code;

    /** 以登记的错误码创建领域异常；新代码统一使用此入口。 */
    public DomainException(ErrorCode code, String message) {
        this(code.name(), message);
    }

    /** 以登记的错误码创建领域异常，并保留触发它的原始异常。 */
    public DomainException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code.name();
    }

    /**
     * 错误码来自持久化结果、外部回执或其他异常时使用；文本未在 ErrorCode 登记时按业务规则不满足处理。
     */
    public DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** 返回机器可读错误码。 */
    public String code() {
        return code;
    }

    /** 判断异常是否为指定错误码。 */
    public boolean is(ErrorCode expected) {
        return code.equals(expected.name());
    }

    /** 返回 HTTP 语义分类；未登记的历史错误码保持原有的业务规则不满足语义。 */
    public ErrorStatus status() {
        return ErrorCode.find(code).map(ErrorCode::status).orElse(ErrorStatus.UNPROCESSABLE_ENTITY);
    }
}
