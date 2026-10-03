package io.agentflow.servicetask;

import java.util.Objects;

/**
 * 可信服务副作用端口，只有已经持久化的原命令可以发送；查询不创建新操作。
 * @author owlzhangfq@gmail.com
 */
public interface ServiceTaskGateway {
    /** 在数据库事务外发送原命令，远端按编号及完整摘要幂等。 */
    Result execute(ServiceTaskOperation.Input input);
    /** 在数据库事务外查询原号，未找到必须是原服务的权威结论。 */
    Result query(ServiceTaskOperation.Input input);

    /** @author owlzhangfq@gmail.com */
    sealed interface Result permits Observed, Unavailable { }
    /** @author owlzhangfq@gmail.com */
    record Observed(ServiceTaskObservation value) implements Result {
        public Observed { Objects.requireNonNull(value); }
    }
    /** @author owlzhangfq@gmail.com */
    record Unavailable(ServiceTaskOperation.Failure failure) implements Result {
        public Unavailable { Objects.requireNonNull(failure); }
    }
}
