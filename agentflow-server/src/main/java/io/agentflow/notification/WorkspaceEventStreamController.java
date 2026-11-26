package io.agentflow.notification;

import io.agentflow.common.CurrentActor;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 工作台轻量实时事件流；仅订阅当前认证主体自己的刷新信号，关闭反向代理缓冲。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/events/stream")
public class WorkspaceEventStreamController {
    private final CurrentActor actors;
    private final WorkspaceEventPublisher publisher;

    /** 认证与订阅均绑定当前可信主体。 */
    public WorkspaceEventStreamController(CurrentActor actors, WorkspaceEventPublisher publisher) {
        this.actors = actors;
        this.publisher = publisher;
    }

    /** 建立只读 SSE 事件流，不缓存、不代理缓冲。 */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletResponse response) {
        var actor = actors.actor();
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        return publisher.subscribe(actor);
    }
}
