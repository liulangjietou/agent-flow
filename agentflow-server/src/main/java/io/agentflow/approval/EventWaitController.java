package io.agentflow.approval;

import io.agentflow.approval.process.EventWaitService;
import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/**
 * 参与者只读原生等待身份，不能通过此入口发送事件、写变量或跳过等待。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/applications/{id}/rounds/{roundNo}/event-waits")
public class EventWaitController {
    private final EventWaitService service;

    /** 等待读取仍由申请详情的参与权限控制。 */
    public EventWaitController(EventWaitService service) { this.service = service; }

    /** 轮次为明确正整数，查询参数不能覆盖租户、版本或来源。 */
    @GetMapping
    public ResponseEntity<EventWaitService.View> read(@PathVariable UUID id, @PathVariable String roundNo, HttpServletRequest request) {
        int round;
        try {
            if (request.getQueryString() != null || !roundNo.matches("[1-9][0-9]{0,9}")) throw new NumberFormatException();
            round = Integer.parseInt(roundNo);
        } catch (NumberFormatException failure) { throw new DomainException("INVALID_EVENT_WAIT_QUERY", "A positive round number and no query parameters are required"); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read(id, round));
    }
}
