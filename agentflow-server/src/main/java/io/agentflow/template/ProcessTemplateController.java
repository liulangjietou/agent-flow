package io.agentflow.template;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionController.DefinitionResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模板目录接口，三个入口均先验证当前管理权限，复制重试沿用统一幂等协议。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/process-templates")
public class ProcessTemplateController {
    private final ClasspathProcessTemplateCatalog catalog;
    private final ProcessTemplateApplicationService service;
    private final TemplateCopyRepository copies;
    private final CurrentActor currentActor;
    private final IdempotencyExecutor idempotency;

    /** 创建模板接口。 */
    public ProcessTemplateController(ClasspathProcessTemplateCatalog catalog, ProcessTemplateApplicationService service,
                                     TemplateCopyRepository copies, CurrentActor currentActor, IdempotencyExecutor idempotency) {
        this.catalog = catalog;
        this.service = service;
        this.copies = copies;
        this.currentActor = currentActor;
        this.idempotency = idempotency;
    }

    /** 返回目录完整说明及本租户副本的当前状态。 */
    @GetMapping
    public List<TemplateResponse> list() {
        Actor actor = requireProcessAdmin();
        return catalog.list().stream().map(template -> new TemplateResponse(template,
                copies.findByTemplate(actor.tenantId(), template.key()))).toList();
    }

    /** 返回目录内已验证的场景，不创建任何流程资源。 */
    @GetMapping("/{key}/scenarios")
    public List<ProcessTemplate.Scenario> scenarios(@PathVariable String key) {
        requireProcessAdmin();
        return catalog.get(key).scenarios();
    }

    /** 复制指定版本为独立草稿，租户和操作人仅来自认证上下文。 */
    @PostMapping("/{key}/copy")
    public ResponseEntity<String> copy(@PathVariable String key, @RequestBody JsonNode body, HttpServletRequest request) {
        Actor actor = requireProcessAdmin();
        CopyRequest copy = CopyRequest.from(body);
        return idempotency.execute(request, HttpStatus.OK, () -> DefinitionResponse.from(service.copy(actor.tenantId(),
                actor.userId(), key, copy.templateVersion(), copy.key(), copy.name())));
    }

    private Actor requireProcessAdmin() {
        Actor actor = currentActor.actor();
        if (!actor.hasRole("ADMIN") && !actor.hasRole("PROCESS_ADMIN")) {
            throw new DomainException("FORBIDDEN", "Process administrator role is required");
        }
        return actor;
    }

    /**
     * 完整模板描述平铺返回，副本列表只来自当前租户。
     * @author owlzhangfq@gmail.com
     */
    public record TemplateResponse(@JsonUnwrapped ProcessTemplate template, List<TemplateCopyRepository.CopyView> copies) { }

    /**
     * 复制请求在入口严格校验一次，禁止 JSON 数字和字符串之间的宽松转换。
     * @author owlzhangfq@gmail.com
     */
    record CopyRequest(String key, String name, long templateVersion) {
        static CopyRequest from(JsonNode body) {
            if (body == null || !body.isObject() || body.size() != 3
                    || !body.path("key").isTextual() || !body.path("name").isTextual()
                    || !body.path("templateVersion").isIntegralNumber() || !body.path("templateVersion").canConvertToLong()) {
                throw invalid();
            }
            String key = body.get("key").textValue();
            String name = body.get("name").textValue();
            long version = body.get("templateVersion").longValue();
            if (!key.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || name.isBlank() || name.length() > 128 || version < 1) throw invalid();
            return new CopyRequest(key, name, version);
        }

        private static DomainException invalid() {
            return new DomainException("INVALID_TEMPLATE_COPY_REQUEST", "Template copy key, name or version is invalid");
        }
    }
}
