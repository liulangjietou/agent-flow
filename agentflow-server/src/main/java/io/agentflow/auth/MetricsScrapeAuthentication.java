package io.agentflow.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 采集凭证仅授权固定指标入口，不转换成租户用户，也不接受业务登录凭证。
 * @author owlzhangfq@gmail.com
 */
@Component
public class MetricsScrapeAuthentication {
    static final String PATH = "/actuator/prometheus";
    private static final String BEARER = "Bearer ";
    private static final int MAX_TOKEN_LENGTH = 256;
    private static final String INVALID_CONFIGURATION = "Invalid metrics token file configuration";
    private final byte[] tokenDigest;

    /** 仅在显式启用时读取有界密钥文件；错误不得包含路径、内容或底层异常。 */
    public MetricsScrapeAuthentication(@Value("${agentflow.monitoring.enabled:false}") boolean enabled,
                                      @Value("${agentflow.monitoring.token-file:}") String tokenFile) {
        tokenDigest = enabled ? digest(readToken(tokenFile)) : null;
    }

    /** 固定入口由该凭证独立鉴权，其他路径继续使用原业务认证。 */
    public boolean matches(HttpServletRequest request) {
        // 容器已解析百分号编码；必须与后续路由使用同一语义，避免编码别名落入业务认证。
        return PATH.equals(request.getServletPath()) || PATH.equals(request.getRequestURI());
    }

    /** 返回是否允许只读采集；失败响应不反射凭证或请求参数。 */
    public boolean authorize(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (tokenDigest == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return false;
        }
        var headers = request.getHeaders("Authorization");
        String header = headers.hasMoreElements() ? headers.nextElement() : null;
        if (headers.hasMoreElements() || header == null || !header.startsWith(BEARER)
                || header.length() > BEARER.length() + MAX_TOKEN_LENGTH
                || !MessageDigest.isEqual(tokenDigest, digest(header.substring(BEARER.length())))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer realm=\"metrics\"");
            return false;
        }
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            response.setHeader("Allow", "GET, HEAD");
            return false;
        }
        return true;
    }

    private static String readToken(String file) {
        try (var input = Files.newInputStream(Path.of(file))) {
            // 允许文件末尾一个换行，内部空白和非 URL 安全字符均拒绝。
            String token = new String(input.readNBytes(MAX_TOKEN_LENGTH + 3), StandardCharsets.UTF_8)
                    .replaceFirst("\\r?\\n\\z", "");
            if (!token.matches("[A-Za-z0-9_-]{43,256}")) throw new IllegalArgumentException(INVALID_CONFIGURATION);
            return token;
        } catch (IOException | RuntimeException failure) {
            throw new IllegalArgumentException(INVALID_CONFIGURATION);
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }
}
