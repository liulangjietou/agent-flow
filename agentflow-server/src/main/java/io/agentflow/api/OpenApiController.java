package io.agentflow.api;

import io.agentflow.common.JsonUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 提供随部署版本发布的接口契约，沿用 API 认证过滤器，不读取任何业务记录。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class OpenApiController {
    private final byte[] document;

    /** 启动时读取并校验 JSON；资源缺失或损坏直接阻止启动。 */
    public OpenApiController(JsonUtil json) throws IOException {
        try (var input = new ClassPathResource("api/openapi.json").getInputStream()) {
            document = input.readAllBytes();
        }
        json.map(new String(document, StandardCharsets.UTF_8));
    }

    /** 返回静态契约；浏览器可下载后导入客户端工具。 */
    @GetMapping(value = "/api/v1/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> get() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(document);
    }
}
