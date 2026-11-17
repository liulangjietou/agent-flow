package io.agentflow.support;

import org.mockito.Mockito;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.test.util.AopTestUtils;

/**
 * 上下文关闭时释放其 Mockito mock，经 META-INF/spring.factories 注册到每个 @SpringBootTest 上下文。
 * <p>
 * Mockito 内联 mock 表以 mock 为弱键、拦截器为强值；@MockitoSpyBean 的拦截器引用被 spy 的原 Bean，原 Bean 经
 * BeanFactory 回指上下文，上下文又持有 spy 本身，弱键因此永远可达。全量测试中被缓存淘汰的上下文会全部滞留，
 * 每个都带着连接池和数百 MB 对象。只清除本上下文的 mock：其余仍在缓存中的上下文的 spy 继续生效。
 * ContextClosedEvent 在销毁单例之前发布，此时仍能取到 mock 实例。
 * </p>
 * @author owlzhangfq@gmail.com
 */
public final class ClosedContextMocks implements ApplicationListener<ContextClosedEvent> {

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        if (!(event.getApplicationContext() instanceof ConfigurableApplicationContext context)) return;
        var beans = context.getBeanFactory();
        for (String name : beans.getSingletonNames()) {
            Object bean = beans.getSingleton(name);
            if (bean == null) continue;
            // @Transactional 等 Bean 的容器单例是 AOP 代理，@MockitoSpyBean 的 spy 在代理目标处。
            Object target = AopTestUtils.getUltimateTargetObject(bean);
            if (Mockito.mockingDetails(target).isMock()) Mockito.framework().clearInlineMock(target);
        }
    }
}
