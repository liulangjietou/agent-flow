package io.agentflow.common;

import org.springframework.stereotype.Component;

/**
 * 请求范围内的认证主体访问器。Web 层过滤器负责设置和清理。
 * @author owlzhangfq@gmail.com
 */
@Component
public class CurrentActor {
    private static final ThreadLocal<Actor> HOLDER = new ThreadLocal<>();

    /** 返回当前主体；没有认证主体时快速失败。 */
    public Actor actor() {
        Actor actor = HOLDER.get();
        if (actor == null) {
            throw new DomainException("UNAUTHENTICATED", "Authentication is required");
        }
        return actor;
    }

    /** 设置当前请求主体。 */
    public void set(Actor actor) {
        HOLDER.set(actor);
    }

    /** 清理当前线程主体，避免线程池污染。 */
    public void clear() {
        HOLDER.remove();
    }
}
