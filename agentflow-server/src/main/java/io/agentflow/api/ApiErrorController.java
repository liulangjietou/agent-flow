package io.agentflow.api;

import io.agentflow.common.ErrorCode;
import io.agentflow.observability.RequestTrace;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 容器错误分派的最终出口：未被 {@link GlobalExceptionHandler} 识别的异常和框架 sendError 都在这里收敛为
 * code/traceId 契约，不回显异常信息。
 * <p>
 * 不在 MVC 层用 {@code @ExceptionHandler(Exception.class)} 兜底，是为了让未知异常仍沿过滤器链冒泡：
 * 幂等、事务等外层组件据此识别失败并回滚，测试也能断言真实根因。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class ApiErrorController implements ErrorController {
    static final String PATH = "/error";
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrorController.class);

    /** 按容器记录的状态码映射稳定错误码；服务端错误统一为 INTERNAL_ERROR。 */
    @RequestMapping(PATH)
    public ResponseEntity<Map<String, Object>> error(HttpServletRequest request) {
        Throwable failure = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        boolean dependency = dependencyFailure(failure);
        HttpStatus status = dependency ? HttpStatus.SERVICE_UNAVAILABLE : status(request);
        String path = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String original ? original : request.getRequestURI();
        ErrorCode code = dependency ? ErrorCode.DEPENDENCY_UNAVAILABLE : code(status);
        if (status.is5xxServerError()) {
            LOG.error("Unexpected request failure, errorCode={}, path={}, traceId={}", code, path, RequestTrace.id(request), failure);
        }
        String message = dependency ? "A required dependency is unavailable; retry later"
                : status.is5xxServerError() ? "Unexpected server error; report the traceId to the administrator" : "Request cannot be processed";
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code.name(), "message", message, "traceId", RequestTrace.id(request), "path", path));
    }

    /** 数据库连接或资源暂不可用：提示稍后重试，不回显底层主机等信息。 */
    private static boolean dependencyFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException || cause instanceof TransientDataAccessResourceException) return true;
        }
        return false;
    }

    private static HttpStatus status(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus resolved = value instanceof Integer code ? HttpStatus.resolve(code) : null;
        return resolved == null ? HttpStatus.INTERNAL_SERVER_ERROR : resolved;
    }

    private static ErrorCode code(HttpStatus status) {
        if (status.is5xxServerError()) return ErrorCode.INTERNAL_ERROR;
        if (status == HttpStatus.NOT_FOUND) return ErrorCode.NOT_FOUND;
        if (status == HttpStatus.PAYLOAD_TOO_LARGE) return ErrorCode.REQUEST_BODY_TOO_LARGE;
        if (status == HttpStatus.UNAUTHORIZED) return ErrorCode.UNAUTHENTICATED;
        if (status == HttpStatus.FORBIDDEN) return ErrorCode.FORBIDDEN;
        return ErrorCode.INVALID_REQUEST;
    }
}
