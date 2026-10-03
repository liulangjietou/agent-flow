package io.agentflow.approval.history;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.SubmissionRound;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 轮次流程图读模型端口；只展示实际绑定的流程和运行事实，不复制引擎状态。
 * @author owlzhangfq@gmail.com
 */
public interface RoundDiagramPort {
    /** 在调用方完成申请授权后，核对轮次与引擎实例并生成只读图。 */
    Diagram read(Application application, SubmissionRound round);

    /**
     * 轮次结论独立于节点状态；连线包含实际定义结构和本轮流转证据。
     * @author owlzhangfq@gmail.com
     */
    record Diagram(UUID applicationId, int roundNo, long definitionVersion, SubmissionRound.Status status,
                   Instant observedAt, List<Node> nodes, List<Edge> edges) { }

    /**
     * 未记录到达、当前活跃或已经离开；离开不等于审批通过。
     * @author owlzhangfq@gmail.com
     */
    enum State { NOT_REACHED, ACTIVE, LEFT }

    /**
     * 未记录不等于从未走过，历史缺失时不推断路径。
     * @author owlzhangfq@gmail.com
     */
    enum EdgeState { TAKEN, NOT_RECORDED }

    /**
     * 人工节点的当前任务数直接来自引擎，包含会签剩余任务。
     * @author owlzhangfq@gmail.com
     */
    record Node(String id, String name, String type, State state, long activeTasks,
                Instant firstEnteredAt, Instant lastLeftAt, List<CandidateSnapshot> candidateSnapshots) { }

    /**
     * 节点激活时已记录的本地候选账号；不代表当前资格、实际办理人或审批结论。
     * @author owlzhangfq@gmail.com
     */
    record CandidateSnapshot(String id, long directoryRevision, List<String> candidateUserIds) {
        public CandidateSnapshot { candidateUserIds = List.copyOf(candidateUserIds); }
    }

    /**
     * 只返回安全的拓扑信息，不向申请读者暴露内部表达式和人员规则。
     * @author owlzhangfq@gmail.com
     */
    record Edge(String id, String source, String target, boolean defaultBranch, EdgeState state,
                long traversalCount, Instant firstTakenAt, Instant lastTakenAt) { }
}
