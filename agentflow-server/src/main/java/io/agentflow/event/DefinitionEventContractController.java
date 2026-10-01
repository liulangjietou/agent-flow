package io.agentflow.event;

import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * 设计器只读已发布契约的精确版本，不因此获得发布、启停或审计原因读取权限。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/process-definitions/event-contract-options")
public class DefinitionEventContractController {
    private final EventContractService service;
    private final CurrentActor actors;

    /** 复用发布目录用例，以独立只读授权边界投影选择项。 */
    public DefinitionEventContractController(EventContractService service, CurrentActor actors) { this.service = service; this.actors = actors; }

    /** 最新版停用时仍显示真实状态，不能自动降级选择旧版。 */
    @GetMapping
    public ResponseEntity<EventContractViews.Directory> list(@RequestParam Map<String, String> query) {
        var actor = designer(); return noStore(service.list(actor.tenantId(), EventContractQuery.directory(query)));
    }

    /** 选择旧版必须由设计者明确指定，后续发布不改变此目录中的旧正文。 */
    @GetMapping("/{key}/versions")
    public ResponseEntity<EventContractViews.Versions> versions(@PathVariable String key, @RequestParam Map<String, String> query) {
        var actor = designer(); return noStore(service.versions(actor.tenantId(), key, EventContractQuery.versions(query, false)));
    }

    /** 精确摘要同时用于已保存节点引用的重新核对。 */
    @GetMapping("/{key}/versions/{version}")
    public ResponseEntity<EventContractViews.Option> version(@PathVariable String key, @PathVariable long version) {
        var actor = designer(); return noStore(EventContractViews.Option.from(service.version(actor.tenantId(), key, version)));
    }

    private Actor designer() {
        var actor = actors.actor();
        if (!actor.hasRole("ADMIN") && !actor.hasRole("PROCESS_ADMIN")) throw new DomainException("FORBIDDEN", "Process administrator role is required");
        return actor;
    }
    private static <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
