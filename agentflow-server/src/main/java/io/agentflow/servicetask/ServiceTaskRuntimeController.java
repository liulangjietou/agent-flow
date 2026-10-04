package io.agentflow.servicetask;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 授权申请的服务执行事实入口，不接受执行命令或外部回执。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/applications/{id}/rounds/{roundNo}/service-tasks")
public class ServiceTaskRuntimeController {
    private final ServiceTaskRuntimeService service;

    /** 组合申请授权、固定轮次与安全状态投影。 */
    public ServiceTaskRuntimeController(ServiceTaskRuntimeService service) { this.service = service; }

    /** 每次翻页重新授权，禁止浏览器缓存运行记录。 */
    @GetMapping
    public ResponseEntity<ServiceTaskRuntimeService.View> read(@PathVariable UUID id, @PathVariable String roundNo,
            @RequestParam MultiValueMap<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.read(id, ServiceTaskRuntimeQuery.parse(roundNo, parameters)));
    }
}
