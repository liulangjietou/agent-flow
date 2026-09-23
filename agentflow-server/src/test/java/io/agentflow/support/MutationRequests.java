package io.agentflow.support;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.UUID;

/**
 * 为既有业务回归中的独立写操作补齐请求契约；幂等协议测试自行指定或省略键。
 * @author owlzhangfq@gmail.com
 */
public final class MutationRequests {
    private MutationRequests() { }

    /** 创建带独立幂等键的 POST 请求，不改变业务测试原有的认证或请求体。 */
    public static MockHttpServletRequestBuilder post(String uri, Object... variables) {
        return MockMvcRequestBuilders.post(uri, variables).header("Idempotency-Key", UUID.randomUUID().toString());
    }

    /** 创建带独立幂等键的 PUT 请求，不将两个独立操作误当成同一请求重放。 */
    public static MockHttpServletRequestBuilder put(String uri, Object... variables) {
        return MockMvcRequestBuilders.put(uri, variables).header("Idempotency-Key", UUID.randomUUID().toString());
    }
}
