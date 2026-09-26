package io.agentflow.calendar;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 日历管理与只读试算入口；权限在幂等回放之前核对，租户和操作者来自认证上下文。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/business-calendars")
public class BusinessCalendarController {
    private final BusinessCalendarService service;
    private final CurrentActor currentActor;
    private final IdempotencyExecutor idempotency;

    /** 组合日历用例、认证和已有写请求幂等协议。 */
    public BusinessCalendarController(BusinessCalendarService service, CurrentActor currentActor, IdempotencyExecutor idempotency) {
        this.service = service; this.currentActor = currentActor; this.idempotency = idempotency;
    }

    /** 读取当前租户目录；客户端不能指定租户或伪造作者。 */
    @GetMapping
    public ResponseEntity<BusinessCalendarService.CalendarPage> list(@RequestParam Map<String, String> query) {
        var actor = administrator();
        var parameters = CalendarQuery.directory(query);
        return noStore(service.list(actor.tenantId(), parameters.afterKey(), parameters.limit()));
    }

    /** 新日历唯一键限定在当前租户，成功后保留首版历史。 */
    @PostMapping
    public ResponseEntity<String> create(@RequestBody CreateRequest body, HttpServletRequest request) {
        var actor = administrator();
        return idempotency.execute(request, HttpStatus.CREATED, () -> CalendarResponse.from(service.create(actor, body.key(), body.name(), body.rules())));
    }

    /** 获取当前规则，供管理员核对后修改。 */
    @GetMapping("/{id}")
    public ResponseEntity<CalendarResponse> get(@PathVariable UUID id) { return noStore(CalendarResponse.from(service.get(administrator().tenantId(), id))); }

    /** 保存新修订，客户端不能改写日历身份和旧版本。 */
    @PutMapping("/{id}")
    public ResponseEntity<String> update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest body, HttpServletRequest request) {
        var actor = administrator();
        return idempotency.execute(request, HttpStatus.OK, () -> CalendarResponse.from(service.update(actor, id, body.name(), body.rules(), body.expectedRevision())));
    }

    /** 按版本倒序读取历史摘要。 */
    @GetMapping("/{id}/versions")
    public ResponseEntity<BusinessCalendarService.VersionPage> versions(@PathVariable UUID id, @RequestParam Map<String, String> query) {
        var actor = administrator(); var parameters = CalendarQuery.versions(query);
        return noStore(service.versions(actor.tenantId(), id, parameters.beforeRevision(), parameters.limit()));
    }

    /** 读取当时保存的完整规则，不用当前配置补写历史。 */
    @GetMapping("/{id}/versions/{revision}")
    public ResponseEntity<CalendarResponse> version(@PathVariable UUID id, @PathVariable long revision) {
        var actor = administrator(); CalendarQuery.requireRevision(revision);
        return noStore(CalendarResponse.from(service.version(actor.tenantId(), id, revision)));
    }

    /** 只读计算不要求幂等键，明确使用客户端选定的不可变修订。 */
    @PostMapping("/{id}/calculate")
    public ResponseEntity<BusinessCalendarService.Calculation> calculate(@PathVariable UUID id, @Valid @RequestBody CalculateRequest body) {
        var actor = administrator();
        return noStore(service.calculate(actor.tenantId(), id, body.revision(), body.startLocal(), body.overlapChoice(), body.workingMinutes()));
    }

    private Actor administrator() { var actor = currentActor.actor(); actor.requireRole("ADMIN"); return actor; }
    private static <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    /**
     * 首版请求只包含管理员明确输入的业务设置。
     * @author owlzhangfq@gmail.com
     */
    public record CreateRequest(String key, String name, CalendarRules rules) { }
    /**
     * 保存修订必须携带已核对的版本。
     * @author owlzhangfq@gmail.com
     */
    public record UpdateRequest(String name, CalendarRules rules, @NotNull @Positive Long expectedRevision) { }
    /**
     * 当地时刻存在两次时 overlapChoice 必填；不存在时要求重新选择。
     * @author owlzhangfq@gmail.com
     */
    public record CalculateRequest(@NotNull @Positive Long revision, LocalDateTime startLocal, CalendarRules.OverlapChoice overlapChoice, int workingMinutes) { }
    /**
     * 隐去租户内部字段，业务键、操作者与历史修订完整保留。
     * @author owlzhangfq@gmail.com
     */
    public record CalendarResponse(UUID id, String key, String name, long revision, CalendarRules rules, String updatedBy, Instant updatedAt) {
        /** 从领域聚合构建当前或历史版本的同一响应。 */
        static CalendarResponse from(BusinessCalendar calendar) { return new CalendarResponse(calendar.id(), calendar.key(), calendar.name(), calendar.revision(), calendar.rules(), calendar.updatedBy(), calendar.updatedAt()); }
    }
}
