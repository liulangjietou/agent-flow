package io.agentflow.common;

/**
 * 错误码对外暴露的 HTTP 语义分类；领域层只表达分类，不依赖 Web 框架类型。
 * @author owlzhangfq@gmail.com
 */
public enum ErrorStatus {
    BAD_REQUEST(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    CONFLICT(409),
    PAYLOAD_TOO_LARGE(413),
    UNPROCESSABLE_ENTITY(422),
    TOO_MANY_REQUESTS(429),
    INTERNAL_SERVER_ERROR(500),
    SERVICE_UNAVAILABLE(503);

    private final int httpStatus;

    ErrorStatus(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    /** 返回 HTTP 状态码数值。 */
    public int httpStatus() {
        return httpStatus;
    }
}
