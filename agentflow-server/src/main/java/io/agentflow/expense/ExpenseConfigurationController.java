package io.agentflow.expense;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

/**
 * 费用类别和制度管理需要独立财务配置角色；认证及参数检查先于幂等回放。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/admin")
public class ExpenseConfigurationController {
    public static final String CONFIGURATION_ROLE = "FINANCE_CONFIG_ADMIN";
    private final CurrentActor actors;
    private final IdempotencyExecutor idempotency;
    private final ExpenseConfigurationService service;

    /** 组合角色校验、费用配置用例及通用幂等事务。 */
    public ExpenseConfigurationController(CurrentActor actors, IdempotencyExecutor idempotency, ExpenseConfigurationService service) {
        this.actors = actors; this.idempotency = idempotency; this.service = service;
    }

    /** 当前类别保留停用记录，未配置时返回零版。 */
    @GetMapping("/expense-categories")
    public ResponseEntity<ExpenseCategoryCatalog> categories(@RequestParam MultiValueMap<String, String> query) {
        var actor = administrator(); ExpenseConfigurationQuery.none(query);
        return noStore(service.categories(actor.tenantId()));
    }

    /** 类别变更要求旧版本及理由，成功回执与历史记录原子保存。 */
    @PutMapping("/expense-categories")
    public ResponseEntity<String> saveCategories(@Valid @RequestBody ExpenseConfigurationInput.Categories body,
            @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        var actor = administrator(); ExpenseConfigurationQuery.none(query);
        var categories = body.categories().stream().map(ExpenseConfigurationInput.Category::domain).toList();
        return idempotency.execute(request, HttpStatus.OK, () -> service.saveCategories(actor, body.expectedVersion(), categories, body.comment().strip()));
    }

    /** 类别历史按版本倒序读取轻量摘要。 */
    @GetMapping("/expense-categories/versions")
    public ResponseEntity<ExpenseConfigurationService.CategoryPage> categoryVersions(@RequestParam MultiValueMap<String, String> query) {
        var actor = administrator(); var page = ExpenseConfigurationQuery.history(query);
        return noStore(service.categoryVersions(actor.tenantId(), page.beforeVersion(), page.limit()));
    }

    /** 完整类别历史不被后续名称或启停变更覆盖。 */
    @GetMapping("/expense-categories/versions/{version}")
    public ResponseEntity<JdbcExpenseConfigurationRepository.CategoryRevision> categoryVersion(@PathVariable String version,
            @RequestParam MultiValueMap<String, String> query) {
        var actor = administrator(); ExpenseConfigurationQuery.none(query);
        return noStore(service.categoryRevision(actor.tenantId(), ExpenseConfigurationQuery.version(version)));
    }

    /** 租户制度草稿目录按业务键分页。 */
    @GetMapping("/expense-policies")
    public ResponseEntity<ExpenseConfigurationService.DraftPage> list(@RequestParam MultiValueMap<String, String> query) {
        var actor = administrator(); var page = ExpenseConfigurationQuery.directory(query);
        return noStore(service.list(actor.tenantId(), page.afterKey(), page.limit()));
    }

    /** 当前生效制度与最新类别同时返回，发布页据此进行三版本确认。 */
    @GetMapping("/expense-policies/current")
    public ResponseEntity<ExpenseConfigurationService.Current> current(@RequestParam MultiValueMap<String, String> query) {
        var actor = administrator(); ExpenseConfigurationQuery.none(query);
        return noStore(service.current(actor.tenantId()));
    }

    /** 发布生效历史明确记录完整制度集之间的切换。 */
    @GetMapping("/expense-policies/activations")
    public ResponseEntity<ExpenseConfigurationService.ActivationPage> activations(@RequestParam MultiValueMap<String, String> query) {
        var actor = administrator(); var page = ExpenseConfigurationQuery.history(query);
        return noStore(service.activations(actor.tenantId(), page.beforeVersion(), page.limit()));
    }

    /** 读取本租户当前草稿。 */
    @GetMapping("/expense-policies/{key}/draft")
    public ResponseEntity<ExpensePolicyDraft> draft(@PathVariable String key, @RequestParam MultiValueMap<String, String> query) {
        var actor = administrator(); ExpenseConfigurationQuery.key(key); ExpenseConfigurationQuery.none(query);
        return noStore(service.draft(actor.tenantId(), key));
    }

    /** 保存可反复编辑的草稿，零修订明确表示创建。 */
    @PutMapping("/expense-policies/{key}/draft")
    public ResponseEntity<String> saveDraft(@PathVariable String key, @Valid @RequestBody ExpenseConfigurationInput.Draft body,
            @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        var actor = administrator(); ExpenseConfigurationQuery.key(key); ExpenseConfigurationQuery.none(query);
        var definition = body.definition().domain();
        return idempotency.execute(request, HttpStatus.OK, () -> service.saveDraft(actor, key, body.expectedRevision(), definition, body.comment().strip()));
    }

    /** 查看特定草稿修订；没有发布的修改也留下审计记录。 */
    @GetMapping("/expense-policies/{key}/draft/versions/{version}")
    public ResponseEntity<JdbcExpenseConfigurationRepository.DraftRevision> draftRevision(@PathVariable String key, @PathVariable String version,
            @RequestParam MultiValueMap<String, String> query) {
        var actor = administrator(); ExpenseConfigurationQuery.key(key); ExpenseConfigurationQuery.none(query);
        return noStore(service.draftRevision(actor.tenantId(), key, ExpenseConfigurationQuery.version(version)));
    }

    /** 发布即显式切换本租户生效制度集；新提交必须重新核对版本。 */
    @PostMapping("/expense-policies/{key}/publish")
    public ResponseEntity<String> publish(@PathVariable String key, @Valid @RequestBody ExpenseConfigurationInput.Publish body,
            @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        var actor = administrator(); ExpenseConfigurationQuery.key(key); ExpenseConfigurationQuery.none(query);
        return idempotency.execute(request, HttpStatus.OK, () -> service.publish(actor, key, body.expectedDraftRevision(), body.expectedCategoryRevision(), body.expectedActiveRevision(), body.comment().strip()));
    }

    /** 发布版本历史只提供摘要分页。 */
    @GetMapping("/expense-policies/{key}/versions")
    public ResponseEntity<ExpenseConfigurationService.VersionPage> versions(@PathVariable String key, @RequestParam MultiValueMap<String, String> query) {
        var actor = administrator(); ExpenseConfigurationQuery.key(key); var page = ExpenseConfigurationQuery.history(query);
        return noStore(service.versions(actor.tenantId(), key, page.beforeVersion(), page.limit()));
    }

    /** 发布正文永久保留所用草稿及类别修订，不能通过更新草稿覆盖。 */
    @GetMapping("/expense-policies/{key}/versions/{version}")
    public ResponseEntity<PublishedExpensePolicy> version(@PathVariable String key, @PathVariable String version, @RequestParam MultiValueMap<String, String> query) {
        var actor = administrator(); ExpenseConfigurationQuery.key(key); ExpenseConfigurationQuery.none(query);
        return noStore(service.version(actor.tenantId(), key, ExpenseConfigurationQuery.version(version)));
    }

    private Actor administrator() { var actor = actors.actor(); actor.requireRole(CONFIGURATION_ROLE); return actor; }
    private static <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
