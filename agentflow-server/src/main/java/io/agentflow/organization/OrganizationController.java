package io.agentflow.organization;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 仅租户管理员维护组织；认证、CSRF、页面身份绑定及写幂等沿用平台统一入口。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/organization")
public class OrganizationController {
    private final CurrentActor actors;
    private final OrganizationRepository repository;
    private final OrganizationService service;
    private final IdempotencyExecutor idempotency;

    /** 只组合已有认证和幂等组件，不引入独立登录或用户密码。 */
    public OrganizationController(CurrentActor actors, OrganizationRepository repository, OrganizationService service, IdempotencyExecutor idempotency) {
        this.actors = actors; this.repository = repository; this.service = service; this.idempotency = idempotency;
    }

    /** 初始化状态明确区分未建目录与已建空目录。 */
    @GetMapping
    public ResponseEntity<Map<String, Boolean>> status() { return noStore(Map.of("initialized", repository.initialized(administrator().tenantId()))); }

    /** 管理员明确启用本地来源；不能通过请求创建或切换认证租户。 */
    @PostMapping("/initialize")
    public ResponseEntity<String> initialize(HttpServletRequest request) {
        var actor = administrator();
        return idempotency.execute(request, HttpStatus.CREATED, () -> { service.initialize(actor); return Map.of("initialized", true); });
    }

    /** 分页读取法人、部门或岗位，不接受其他租户参数。 */
    @GetMapping("/units")
    public ResponseEntity<Page<OrganizationUnit>> units(@RequestParam Map<String, String> query) {
        var actor = administrator(); var p = OrganizationQuery.parse(query, Set.of("kind", "afterId", "limit"));
        return noStore(page(repository.units(actor.tenantId(), p.requiredKind(), p.afterId(), p.limit()), p.limit(), OrganizationUnit::id));
    }

    /** 新增单元时核对同租户引用；所有变更保留独立修订。 */
    @PostMapping("/units")
    public ResponseEntity<String> createUnit(@Valid @RequestBody CreateUnit body, HttpServletRequest request) {
        var actor = administrator();
        return idempotency.execute(request, HttpStatus.CREATED, () -> service.createUnit(actor, body.kind(), body.name(), body.legalEntityId(), body.parentDepartmentId(), body.active()));
    }

    /** 按客户端核对过的版本修改单元。 */
    @PutMapping("/units/{id}")
    public ResponseEntity<String> updateUnit(@PathVariable UUID id, @Valid @RequestBody UpdateUnit body, HttpServletRequest request) {
        var actor = administrator();
        return idempotency.execute(request, HttpStatus.OK, () -> service.updateUnit(actor, id, body.name(), body.parentDepartmentId(), body.active(), body.expectedRevision()));
    }

    /** 人员管理读取稳定主体，仅向管理员开放。 */
    @GetMapping("/people")
    public ResponseEntity<Page<OrganizationPerson>> people(@RequestParam Map<String, String> query) {
        var actor = administrator(); var p = OrganizationQuery.parse(query, Set.of("afterId", "limit"));
        return noStore(page(repository.people(actor.tenantId(), p.afterId(), p.limit()), p.limit(), OrganizationPerson::id));
    }

    /** 添加人员绑定，不接受客户端输入系统权限。 */
    @PostMapping("/people")
    public ResponseEntity<String> createPerson(@Valid @RequestBody CreatePerson body, HttpServletRequest request) {
        var actor = administrator();
        return idempotency.execute(request, HttpStatus.CREATED, () -> service.createPerson(actor, body.subject(), body.displayName(), body.active(), body.approvalEligible()));
    }

    /** 启停及审批资格属于人员自身状态，不能替换原身份绑定。 */
    @PutMapping("/people/{id}")
    public ResponseEntity<String> updatePerson(@PathVariable UUID id, @Valid @RequestBody UpdatePerson body, HttpServletRequest request) {
        var actor = administrator();
        return idempotency.execute(request, HttpStatus.OK, () -> service.updatePerson(actor, id, body.displayName(), body.active(), body.approvalEligible(), body.expectedRevision()));
    }

    /** 任职读取按稳定标识分页，可限定同租户人员。 */
    @GetMapping("/appointments")
    public ResponseEntity<Page<OrganizationAppointment>> appointments(@RequestParam Map<String, String> query) {
        var actor = administrator(); var p = OrganizationQuery.parse(query, Set.of("personId", "afterId", "limit"));
        return noStore(page(repository.appointments(actor.tenantId(), p.personId(), p.afterId(), p.limit()), p.limit(), OrganizationAppointment::id));
    }

    /** 创建任职，数据库与应用同时限制同租户关系。 */
    @PostMapping("/appointments")
    public ResponseEntity<String> createAppointment(@Valid @RequestBody CreateAppointment body, HttpServletRequest request) {
        var actor = administrator();
        return idempotency.execute(request, HttpStatus.CREATED, () -> service.createAppointment(actor, body.personId(), body.departmentId(), body.positionId(), body.active()));
    }

    /** 同一任职仅切换在用状态，历史关系不随调岗改写。 */
    @PutMapping("/appointments/{id}")
    public ResponseEntity<String> updateAppointment(@PathVariable UUID id, @Valid @RequestBody UpdateAppointment body, HttpServletRequest request) {
        var actor = administrator();
        return idempotency.execute(request, HttpStatus.OK, () -> service.updateAppointment(actor, id, body.active(), body.expectedRevision()));
    }

    /** 追加审计按目录修订倒序读取，无删除或改写入口。 */
    @GetMapping("/changes")
    public ResponseEntity<ChangePage> changes(@RequestParam Map<String, String> query) {
        var actor = administrator(); var p = OrganizationQuery.parse(query, Set.of("beforeRevision", "limit"));
        var values = repository.changes(actor.tenantId(), p.beforeRevision(), p.limit());
        boolean more = values.size() > p.limit();
        return noStore(new ChangePage(List.copyOf(values.subList(0, Math.min(p.limit(), values.size()))), more ? values.get(p.limit() - 1).revision() : null));
    }

    private Actor administrator() { var actor = actors.actor(); actor.requireRole("ADMIN"); return actor; }
    private static <T> ResponseEntity<T> noStore(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    private static <T> Page<T> page(List<T> values, int limit, Function<T, UUID> id) {
        return new Page<>(List.copyOf(values.subList(0, Math.min(limit, values.size()))), values.size() > limit ? id.apply(values.get(limit - 1)).toString() : null);
    }
    /**
     * 目录分页。
     * @author owlzhangfq@gmail.com
     */
    public record Page<T>(List<T> items, String nextAfterId) { }
    /**
     * 组织变更审计的倒序分页结果。
     * @author owlzhangfq@gmail.com
     */
    public record ChangePage(List<OrganizationRepository.Change> items, Long nextBeforeRevision) { }
    /**
     * 新增组织单元的入口参数。
     * @author owlzhangfq@gmail.com
     */
    public record CreateUnit(OrganizationUnit.Kind kind, String name, UUID legalEntityId, UUID parentDepartmentId, @NotNull Boolean active) { }
    /**
     * 组织单元可变字段与原修订。
     * @author owlzhangfq@gmail.com
     */
    public record UpdateUnit(String name, UUID parentDepartmentId, @NotNull Boolean active, @NotNull @Positive Long expectedRevision) { }
    /**
     * 绑定稳定身份的人员入口参数。
     * @author owlzhangfq@gmail.com
     */
    public record CreatePerson(String subject, String displayName, @NotNull Boolean active, @NotNull Boolean approvalEligible) { }
    /**
     * 人员可变资料与本地审批资格。
     * @author owlzhangfq@gmail.com
     */
    public record UpdatePerson(String displayName, @NotNull Boolean active, @NotNull Boolean approvalEligible, @NotNull @Positive Long expectedRevision) { }
    /**
     * 新增人员与部门岗位之间的任职。
     * @author owlzhangfq@gmail.com
     */
    public record CreateAppointment(UUID personId, UUID departmentId, UUID positionId, @NotNull Boolean active) { }
    /**
     * 任职只允许启停，不覆盖历史关系。
     * @author owlzhangfq@gmail.com
     */
    public record UpdateAppointment(@NotNull Boolean active, @NotNull @Positive Long expectedRevision) { }
}
