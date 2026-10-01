package io.agentflow.approval.model;

import io.agentflow.common.DomainException;
import java.util.HashSet;
import java.util.List;

/**
 * 从实际会签任务恢复的责任名单；增减签不产生审批意见，也不能移除已经完成的决定。
 * @author owlzhangfq@gmail.com
 */
public record CountersignMembership(int total, int completed, List<Member> pending, List<String> completedUsers) {
    public static final int MAX_MEMBERS = 100;

    /** 引擎计数与逐人事实必须一致，历史缺失时不推定已经同意。 */
    public CountersignMembership {
        pending = List.copyOf(pending);
        completedUsers = List.copyOf(completedUsers);
        if (total < 1 || completed < 0 || pending.isEmpty() || total != pending.size() + completed
                || completed != completedUsers.size()) throw invalid();
        var taskIds = new HashSet<String>();
        var users = new HashSet<String>(completedUsers);
        if (users.size() != completedUsers.size()) throw invalid();
        for (Member member : pending) {
            if (!taskIds.add(member.taskId()) || !users.add(member.user())) throw invalid();
            if (!member.delegated() && !member.user().equals(member.assignee())) throw invalid();
        }
    }

    /** 当前责任人只能增加尚未承担本节点责任的有效人员，资格由调用方目录复核。 */
    public void requireAddition(String sourceTaskId, String targetUser) {
        requireOperator(sourceTaskId);
        if (total >= MAX_MEMBERS) throw new DomainException("COUNTERSIGN_MEMBER_LIMIT", "Countersign member limit reached");
        if (completedUsers.contains(targetUser) || pending.stream().anyMatch(member -> member.user().equals(targetUser))) {
            throw new DomainException("COUNTERSIGN_MEMBER_EXISTS", "The user already has responsibility for this countersign node");
        }
    }

    /** 减签只移除其他尚未处理且未在委派中的任务，至少保留一张必要待办。 */
    public Member requireRemoval(String sourceTaskId, String targetTaskId) {
        requireOperator(sourceTaskId);
        Member target = member(targetTaskId);
        if (pending.size() <= 1) throw new DomainException("COUNTERSIGN_LAST_MEMBER", "The final pending countersign member cannot be removed");
        if (sourceTaskId.equals(targetTaskId)) throw new DomainException("COUNTERSIGN_SELF_REMOVAL", "The acting member must retain their own approval responsibility");
        if (target.delegated()) throw new DomainException("TASK_DELEGATION_PENDING", "Resolve the delegated task before removing its responsibility");
        return target;
    }

    private void requireOperator(String sourceTaskId) {
        if (member(sourceTaskId).delegated()) throw new DomainException("TASK_DELEGATION_PENDING", "Delegated assistance cannot change countersign responsibilities");
    }

    private Member member(String taskId) {
        return pending.stream().filter(member -> member.taskId().equals(taskId)).findFirst()
                .orElseThrow(() -> new DomainException("COUNTERSIGN_MEMBER_UNAVAILABLE", "The selected pending countersign task is no longer available"));
    }

    private static DomainException invalid() {
        return new DomainException("COUNTERSIGN_STATE_INVALID", "Countersign counts and responsibility facts do not agree");
    }

    /**
     * user 为原会签责任人，assignee 为实际处理人；委派不替换责任身份。
     * @author owlzhangfq@gmail.com
     */
    public record Member(String taskId, String user, String assignee, boolean delegated) { }
}
