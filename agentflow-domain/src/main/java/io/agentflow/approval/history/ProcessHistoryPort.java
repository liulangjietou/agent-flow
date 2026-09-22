package io.agentflow.approval.history;

import io.agentflow.approval.model.SubmissionRound;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 引擎历史防腐端口，返回已核对租户、申请及轮次绑定的事实。
 * @author owlzhangfq@gmail.com
 */
public interface ProcessHistoryPort {
    /** 读取系统变量关联的历史，不能从业务单号推断归属。 */
    ProcessHistory read(String tenantId, UUID applicationId, List<SubmissionRound> rounds);

    /**
     * 已核实的实例与任务集合，供旧审计可靠关联。
     * @author owlzhangfq@gmail.com
     */
    record ProcessHistory(List<HistoryEvent> events, Map<String, ProcessBinding> processes,
                          Map<String, TaskBinding> tasks) { }

    /**
     * 来源于引擎实际历史的实例绑定。
     * @author owlzhangfq@gmail.com
     */
    record ProcessBinding(String processInstanceId, int roundNo, Long definitionVersion) { }

    /**
     * 来源于已核实实例的历史人工任务。
     * @author owlzhangfq@gmail.com
     */
    record TaskBinding(String taskId, String processInstanceId, int roundNo, Long definitionVersion,
                       String nodeId, String nodeName) { }
}
