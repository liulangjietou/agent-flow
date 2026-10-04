package io.agentflow.notification;

import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本人外部冲销核对消息的受控只读入口，不接受替换原编号或轮次的参数。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class ReversalCheckNotificationController {
    private final ReversalCheckNotificationAccess access;
    /** 归属与财务字段读取边界由通知访问服务统一复核。 */
    public ReversalCheckNotificationController(ReversalCheckNotificationAccess access) { this.access = access; }
    /** 不缓存财务摘要，也不在读取中准备、授权或发送冲销。 */
    @GetMapping("/api/v1/notifications/{id}/reversal-check-target")
    public ResponseEntity<ReversalCheckNotificationAccess.Target> target(@PathVariable UUID id, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new DomainException("INVALID_INBOX_QUERY", "Reversal check notification target does not accept query parameters");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(access.target(id));
    }
}
