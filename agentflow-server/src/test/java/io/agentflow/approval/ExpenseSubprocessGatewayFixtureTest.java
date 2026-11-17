package io.agentflow.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import io.agentflow.support.GarbageCollection;
import java.lang.ref.WeakReference;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 夹具以 static final 字段挂在测试类上、随类常驻；close() 必须放下当前上下文的 Bean，
 * 否则测试类结束后该 JsonUtil 所在的 Spring 上下文连同连接池被一直钉住。
 * @author owlzhangfq@gmail.com
 */
class ExpenseSubprocessGatewayFixtureTest {

    @Test void closingTheClassScopedFixtureReleasesTheContextBeansItWasGiven() {
        var fixture = new ExpenseSubprocessGatewayFixture();
        var json = handOver(fixture);
        fixture.close();
        GarbageCollection.assertCollected(json, "Context JsonUtil held by a closed gateway fixture");
    }

    /** 单独方法交出 Bean，返回后调用方栈上只剩弱引用。 */
    private static WeakReference<JsonUtil> handOver(ExpenseSubprocessGatewayFixture fixture) {
        var json = new JsonUtil(new ObjectMapper());
        fixture.use(json, UUID.randomUUID(), "INV-RELEASE");
        return new WeakReference<>(json);
    }
}
