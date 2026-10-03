package io.agentflow.servicetask;

import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设计者只选部署者已经安装的契约，公开入口不提供新增目标、凭据或任意执行能力。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/process-definitions/service-task-options")
public class DefinitionServiceTaskController {
    private final ServiceTaskCatalog catalog;
    private final CurrentActor actors;

    /** 复用流程管理角色与当前租户边界。 */
    public DefinitionServiceTaskController(ServiceTaskCatalog catalog, CurrentActor actors) {
        this.catalog = catalog;
        this.actors = actors;
    }

    /** 展示最新安装版本及真实可用状态。 */
    @GetMapping
    public ResponseEntity<ServiceTaskCatalogViews.Directory> list(@RequestParam MultiValueMap<String, String> query) {
        var actor = designer();
        return noStore(catalog.list(actor.tenantId(), ServiceTaskCatalogQuery.directory(query)));
    }

    /** 浏览旧版不自动替换节点引用。 */
    @GetMapping("/{key}/versions")
    public ResponseEntity<ServiceTaskCatalogViews.Versions> versions(@PathVariable String key, @RequestParam MultiValueMap<String, String> query) {
        var actor = designer();
        ServiceTaskCatalogQuery.requireKey(key);
        return noStore(catalog.versions(actor.tenantId(), key, ServiceTaskCatalogQuery.versions(query)));
    }

    /** 原摘要随原版本返回，编辑者不能用同名最新版替代。 */
    @GetMapping("/{key}/versions/{version}")
    public ResponseEntity<ServiceTaskCatalogViews.Option> version(@PathVariable String key, @PathVariable String version) {
        var actor = designer();
        ServiceTaskCatalogQuery.requireKey(key);
        return noStore(catalog.option(actor.tenantId(), key, ServiceTaskCatalogQuery.version(version)));
    }

    private Actor designer() {
        var actor = actors.actor();
        if (!actor.hasRole("ADMIN") && !actor.hasRole("PROCESS_ADMIN")) throw new DomainException("FORBIDDEN", "Process administrator role is required");
        return actor;
    }

    private static <T> ResponseEntity<T> noStore(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
