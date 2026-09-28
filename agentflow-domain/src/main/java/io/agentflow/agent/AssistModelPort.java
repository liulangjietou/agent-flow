package io.agentflow.agent;

import java.util.List;

/**
 * 摘要模型端口；输入只含已授权的冻结来源，输出不能直接驱动审批。
 * @author owlzhangfq@gmail.com
 */
public interface AssistModelPort {
    /** 在事务外生成有来源绑定的建议，失败只返回稳定分类。 */
    AssistSuggestion generate(String promptVersion, List<Source> sources);

    /**
     * 来源内容是惰性文本，不能作为地址、指令或工具执行。
     * @author owlzhangfq@gmail.com
     */
    record Source(AssistInput.Reference reference, String label, String content) { }

    /**
     * 不携带模型响应正文、提示内容或凭据的执行异常。
     * @author owlzhangfq@gmail.com
     */
    final class ModelFailure extends RuntimeException {
        private final AssistRun.Failure failure;
        /** 失败类别可直接保存为运行结果。 */
        public ModelFailure(AssistRun.Failure failure) { super(failure.name()); this.failure = failure; }
        public AssistRun.Failure failure() { return failure; }
    }
}
