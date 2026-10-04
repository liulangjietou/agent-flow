package io.agentflow.signature;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;

/**
 * 签署回调只有经过原始回执验真后才进入短事务，接收成功不表示结果文件已经保存。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class SignatureCallbackController {
    private final SignatureCallbackVerifier verifier;
    private final SignatureOperationService operations;
    /** 回调签名认证与用户令牌认证互不替代。 */
    public SignatureCallbackController(SignatureCallbackVerifier verifier, SignatureOperationService operations) { this.verifier = verifier; this.operations = operations; }
    /** 重复和旧修订安全确认；冲突回执拒绝覆盖，响应不泄露业务状态或签名原文。 */
    @PostMapping(SignatureCallbackVerifier.PATH)
    public ResponseEntity<Void> receive(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        var callback = verifier.verify(request, Instant.now()); operations.receiveCallback(callback, Instant.now());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
