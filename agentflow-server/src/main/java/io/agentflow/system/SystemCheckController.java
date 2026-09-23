package io.agentflow.system;

import io.agentflow.common.CurrentActor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 受认证保护的管理员诊断接口，与公开健康探针分开。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/system/checks")
public class SystemCheckController {
    private final SystemCheckService service;
    private final CurrentActor currentActor;

    /** 注入当前主体与查询服务。 */
    public SystemCheckController(SystemCheckService service, CurrentActor currentActor) {
        this.service = service;
        this.currentActor = currentActor;
    }

    /** 即时读取诊断快照，禁止浏览器或代理缓存账号切换前的结果。 */
    @GetMapping
    public ResponseEntity<SystemCheckService.Report> check() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.check(currentActor.actor()));
    }
}
