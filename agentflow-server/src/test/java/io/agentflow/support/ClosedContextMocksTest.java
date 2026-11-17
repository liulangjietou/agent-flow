package io.agentflow.support;

import java.lang.ref.WeakReference;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.support.SpringFactoriesLoader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Mockito 内联 mock 表以 mock 为弱键、以拦截器为强值，拦截器经被 spy 的原对象回指上下文，
 * 上下文又持有 spy 本身：键永远可达，关闭后的上下文因此一直留在堆里。关闭时清除本上下文的 mock 才能释放。
 * @author owlzhangfq@gmail.com
 */
class ClosedContextMocksTest {

    @Test void closingAContextReleasesItEvenWhenItHoldsASpy() {
        WeakReference<GenericApplicationContext> closed = closedContextWithSpy();
        GarbageCollection.assertCollected(closed, "Closed context holding a Mockito spy");
    }

    @Test void spiesBehindTransactionalProxiesAreReleasedToo() {
        WeakReference<GenericApplicationContext> closed = closedContext(true);
        GarbageCollection.assertCollected(closed, "Closed context holding a proxied Mockito spy");
    }

    @Test void mocksOfContextsThatStayOpenKeepWorking() {
        try (var open = contextWithSpy()) {
            closedContextWithSpy();
            var spy = open.getBean(Probe.class);
            when(spy.value()).thenReturn("stubbed");
            assertThat(spy.value()).isEqualTo("stubbed");
            assertThat(Mockito.mockingDetails(spy).isMock()).isTrue();
        }
    }

    @Test void springBootTestContextsPickUpTheListener() {
        assertThat(SpringFactoriesLoader.forDefaultResourceLocation().load(ApplicationListener.class))
                .anyMatch(ClosedContextMocks.class::isInstance);
    }

    /** 单独方法创建并关闭，返回后栈上不再有上下文的强引用。 */
    private static WeakReference<GenericApplicationContext> closedContextWithSpy() { return closedContext(false); }

    private static WeakReference<GenericApplicationContext> closedContext(boolean proxied) {
        var context = contextWithSpy(proxied);
        context.close();
        return new WeakReference<>(context);
    }

    private static GenericApplicationContext contextWithSpy() { return contextWithSpy(false); }

    /**
     * 被 spy 的原对象回指上下文，与真实 Bean 经 BeanFactory 回指上下文的结构相同。
     * proxied 模拟 @Transactional Bean：容器单例是 AOP 代理，spy 只是代理目标。
     */
    private static GenericApplicationContext contextWithSpy(boolean proxied) {
        var context = new GenericApplicationContext();
        context.addApplicationListener(new ClosedContextMocks());
        context.refresh();
        Object spy = Mockito.spy(new Probe(context));
        if (proxied) {
            var factory = new ProxyFactory(spy); factory.setProxyTargetClass(true);
            spy = factory.getProxy();
        }
        context.getBeanFactory().registerSingleton("probe", spy);
        return context;
    }

    /**
     * 回指所属上下文的被 spy 对象。
     * @author owlzhangfq@gmail.com
     */
    static class Probe {
        private final GenericApplicationContext owner;
        Probe(GenericApplicationContext owner) { this.owner = owner; }
        String value() { return owner.getDisplayName(); }
    }
}
