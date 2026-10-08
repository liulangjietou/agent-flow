package io.agentflow.approval.process;

import io.agentflow.common.Actor;

import java.util.List;

/**
 * Flowable 7.2 的参与关系只读防腐层，对齐现有详情的引擎查询语义。
 *
 * @author owlzhangfq@gmail.com
 */
public final class FlowableParticipationParameters {
    private FlowableParticipationParameters() {}

    /** 供已按认证租户筛选的申请表 a 使用；每次按实时参与关系计算可见集合。 */
    public static void append(Actor actor, List<Object> parameters) {
        // 租户参数来自认证上下文，与外层申请筛选一致。避免相关子查询逐申请扫描引擎表。
        // IN 保留存在性语义，多任务、多变量或多身份关联不会放大申请分页。

        parameters.add(actor.tenantId());
        parameters.add(actor.userId());
        parameters.add(actor.userId());
        // 详情允许所有当前身份关联（包含委托所有者），不能收窄为未认领任务的候选人。
        if (!actor.roles().isEmpty()) {

            parameters.addAll(actor.roles().stream().sorted().toList());
        }

        parameters.add(actor.userId());
        parameters.add(actor.tenantId());
        return;
    }
}
