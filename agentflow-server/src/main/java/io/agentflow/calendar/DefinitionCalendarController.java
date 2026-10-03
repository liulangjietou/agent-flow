package io.agentflow.calendar;

import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
import java.util.UUID;

/**
 * 流程管理员只读选择日历修订，不因此取得管理规则或试算接口的权限。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/process-definitions/calendar-options")
public class DefinitionCalendarController {
    private final BusinessCalendarService service;
    private final CurrentActor currentActor;

    /** 复用日历查询用例，只投影选择所需的摘要。 */
    public DefinitionCalendarController(BusinessCalendarService service, CurrentActor currentActor) {
        this.service = service; this.currentActor = currentActor;
    }

    /** 分页读取当前修订，选择后必须保存其明确修订号。 */
    @GetMapping
    public ResponseEntity<BusinessCalendarService.CalendarPage> list(@RequestParam Map<String, String> query) {
        var actor = designer(); var parameters = CalendarQuery.directory(query);
        return noStore(service.list(actor.tenantId(), parameters.afterKey(), parameters.limit()));
    }

    /** 允许设计器显式选择旧修订，不把旧引用自动更新到最新版。 */
    @GetMapping("/{id}/versions")
    public ResponseEntity<BusinessCalendarService.VersionPage> versions(@PathVariable UUID id, @RequestParam Map<String, String> query) {
        var actor = designer(); var parameters = CalendarQuery.versions(query);
        return noStore(service.versions(actor.tenantId(), id, parameters.beforeRevision(), parameters.limit()));
    }

    /** 读取已有节点引用的精确摘要，不返回整份企业作息规则。 */
    @GetMapping("/{id}/versions/{revision}")
    public ResponseEntity<BusinessCalendarRepository.Summary> version(@PathVariable UUID id, @PathVariable long revision) {
        var actor = designer(); CalendarQuery.requireRevision(revision);
        var value = service.version(actor.tenantId(), id, revision);
        return noStore(new BusinessCalendarRepository.Summary(value.id(), value.key(), value.name(), value.rules().zoneId(),
                value.revision(), value.updatedBy(), value.updatedAt()));
    }

    private Actor designer() {
        var actor = currentActor.actor();
        if (!actor.hasRole("ADMIN") && !actor.hasRole("PROCESS_ADMIN")) throw new DomainException("FORBIDDEN", "Process administrator role is required");
        return actor;
    }
    private static <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
