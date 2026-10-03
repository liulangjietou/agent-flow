package io.agentflow.finance;

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
 * 科目映射管理只向专用配置角色开放，认证与输入检查先于幂等回放。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/admin/account-mappings")
public class AccountMappingConfigurationController {
    public static final String CONFIGURATION_ROLE = "FINANCE_CONFIG_ADMIN";
    private final CurrentActor actors;
    private final IdempotencyExecutor idempotency;
    private final AccountMappingConfigurationService service;

    /** 组合统一认证与幂等事务，领域服务不读取浏览器身份或地址。 */
    public AccountMappingConfigurationController(CurrentActor actors, IdempotencyExecutor idempotency, AccountMappingConfigurationService service) {
        this.actors = actors; this.idempotency = idempotency; this.service = service;
    }

    /** 租户目录只返回摘要，可筛选法人、币种并按业务键分页。 */
    @GetMapping
    public ResponseEntity<AccountMappingConfigurationService.DraftPage> list(@RequestParam MultiValueMap<String, String> query) {
        var actor = administrator(); var page = AccountMappingConfigurationQuery.directory(query);
        return noStore(service.list(actor.tenantId(), page.legalEntityId(), page.currency(), page.afterKey(), page.limit()));
    }

    /** 当前生效配置必须指定法人及币种，未配置返回明确零版。 */
    @GetMapping("/current")
    public ResponseEntity<AccountMappingConfigurationService.Current> current(@RequestParam MultiValueMap<String, String> query) {
        var actor = administrator(); var scope = AccountMappingConfigurationQuery.scope(query);
        return noStore(service.current(actor.tenantId(), scope.legalEntityId(), scope.currency()));
    }

    /** 同一范围内的业务键切换通过独立生效历史追溯。 */
    @GetMapping("/activations")
    public ResponseEntity<AccountMappingConfigurationService.ActivationPage> activations(@RequestParam MultiValueMap<String, String> query) {
        var actor = administrator(); var page = AccountMappingConfigurationQuery.activations(query);
        return noStore(service.activations(actor.tenantId(), page.scope().legalEntityId(), page.scope().currency(), page.beforeVersion(), page.limit()));
    }

    /** 当前草稿与已发布版本明确分开读取。 */
    @GetMapping("/{key}/draft")
    public ResponseEntity<AccountMappingDraft> draft(@PathVariable String key, @RequestParam MultiValueMap<String, String> query) {
        var actor = administrator(); AccountMappingConfigurationQuery.key(key); AccountMappingConfigurationQuery.none(query);
        return noStore(service.draft(actor.tenantId(), key));
    }

    /** 保存草稿连同历史和幂等成功回执原子提交。 */
    @PutMapping("/{key}/draft")
    public ResponseEntity<String> saveDraft(@PathVariable String key, @Valid @RequestBody AccountMappingConfigurationInput.Draft body,
            @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        var actor = administrator(); AccountMappingConfigurationQuery.key(key); AccountMappingConfigurationQuery.none(query);
        return idempotency.execute(request, HttpStatus.OK, () -> service.saveDraft(actor, key, body.expectedRevision(), body.definition(), body.comment().strip()));
    }

    /** 旧修订保留原操作者、理由和完整定义。 */
    @GetMapping("/{key}/draft/versions/{revision}")
    public ResponseEntity<JdbcAccountMappingRepository.DraftRevision> draftRevision(@PathVariable String key, @PathVariable String revision,
            @RequestParam MultiValueMap<String, String> query) {
        var actor = administrator(); AccountMappingConfigurationQuery.key(key); AccountMappingConfigurationQuery.none(query);
        return noStore(service.draftRevision(actor.tenantId(), key, AccountMappingConfigurationQuery.version(revision)));
    }

    /** 发布必须确认原草稿、类别与同范围生效修订，不能指定目标系统。 */
    @PostMapping("/{key}/publish")
    public ResponseEntity<String> publish(@PathVariable String key, @Valid @RequestBody AccountMappingConfigurationInput.Publish body,
            @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        var actor = administrator(); AccountMappingConfigurationQuery.key(key); AccountMappingConfigurationQuery.none(query);
        return idempotency.execute(request, HttpStatus.OK, () -> service.publish(actor, key, body.expectedDraftRevision(), body.expectedCategoryRevision(), body.expectedActiveRevision(), body.comment().strip()));
    }

    /** 发布历史只列摘要，避免列表携带全部账户配置。 */
    @GetMapping("/{key}/versions")
    public ResponseEntity<AccountMappingConfigurationService.VersionPage> versions(@PathVariable String key, @RequestParam MultiValueMap<String, String> query) {
        var actor = administrator(); AccountMappingConfigurationQuery.key(key); var page = AccountMappingConfigurationQuery.history(query);
        return noStore(service.versions(actor.tenantId(), key, page.beforeVersion(), page.limit()));
    }

    /** 已发布版本完整读取，后续草稿不得覆盖原映射证据。 */
    @GetMapping("/{key}/versions/{version}")
    public ResponseEntity<PublishedAccountMapping> version(@PathVariable String key, @PathVariable String version, @RequestParam MultiValueMap<String, String> query) {
        var actor = administrator(); AccountMappingConfigurationQuery.key(key); AccountMappingConfigurationQuery.none(query);
        return noStore(service.version(actor.tenantId(), key, AccountMappingConfigurationQuery.version(version)));
    }

    private Actor administrator() { var actor = actors.actor(); actor.requireRole(CONFIGURATION_ROLE); return actor; }
    private static <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
