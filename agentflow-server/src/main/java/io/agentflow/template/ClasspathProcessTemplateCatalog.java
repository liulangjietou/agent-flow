package io.agentflow.template;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从固定 classpath 资源加载只读目录，启动时完成全部场景验证。
 * @author owlzhangfq@gmail.com
 */
@Component
public class ClasspathProcessTemplateCatalog {
    private static final List<String> KEYS = List.of("leave-request", "seal-application", "contract-review", "procurement-payment", "budget-adjustment");
    private static final Set<String> FIELDS = Set.of("key", "templateVersion", "name", "category", "description", "scope",
            "businessType", "dependencies", "defaultRoles", "fieldDescriptions", "risks", "upgradePolicy",
            "notificationTexts", "notificationsAvailable", "graph", "formSchema", "scenarios");
    private final Map<String, ProcessTemplate> templates;

    /** 资源缺失、结构损坏或验收场景不一致均阻止启动。 */
    public ClasspathProcessTemplateCatalog(ResourceLoader resources, JsonUtil json) {
        Map<String, ProcessTemplate> loaded = new LinkedHashMap<>();
        for (String key : KEYS) {
            String location = "classpath:process-templates/" + key + ".json";
            try (var input = resources.getResource(location).getInputStream()) {
                String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> properties = json.map(content);
                if (!properties.keySet().equals(FIELDS)) throw new IllegalArgumentException("Template properties do not match");
                Object version = properties.get("templateVersion");
                // 目录版本也是来源身份，不接受 Jackson 将小数或字符串转换为整数。
                if (!(version instanceof Integer || version instanceof Long)) throw new IllegalArgumentException("Template version must be an integer");
                ProcessTemplate template = json.read(content, ProcessTemplate.class);
                if (!key.equals(template.key())) throw new IllegalArgumentException("Template resource key does not match");
                template.verifyScenarios();
                loaded.put(key, template);
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("Unable to load process template: " + location, exception);
            }
        }
        templates = java.util.Collections.unmodifiableMap(loaded);
    }

    /** 返回具有稳定顺序的不可变目录。 */
    public List<ProcessTemplate> list() { return List.copyOf(templates.values()); }

    /** 按目录标识读取，不允许以任意路径访问 classpath。 */
    public ProcessTemplate get(String key) {
        ProcessTemplate template = templates.get(key);
        if (template == null) throw new DomainException("NOT_FOUND", "Process template not found");
        return template;
    }

    /** 必须显式匹配所见目录版本，不能静默升级来源。 */
    public ProcessTemplate requireVersion(String key, long version) {
        ProcessTemplate template = get(key);
        if (template.templateVersion() != version) {
            throw new DomainException("TEMPLATE_VERSION_CONFLICT", "Process template version changed");
        }
        return template;
    }
}
