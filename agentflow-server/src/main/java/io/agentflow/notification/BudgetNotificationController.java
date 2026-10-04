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
 * 本人预算消息的只读入口，原编号和轮次不能由查询参数替换。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class BudgetNotificationController {
    private final BudgetNotificationAccess access;
    /** 归属和原业务字段权限交由通知访问服务统一校验。 */
    public BudgetNotificationController(BudgetNotificationAccess access) { this.access = access; }

    /** 财务摘要禁止缓存，读取不会领取或发送预算命令。 */
    @GetMapping("/api/v1/notifications/{id}/budget-target")
    public ResponseEntity<BudgetNotificationAccess.Target> target(@PathVariable UUID id, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new DomainException("INVALID_INBOX_QUERY", "Budget notification target does not accept query parameters");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(access.target(id));
    }
}
