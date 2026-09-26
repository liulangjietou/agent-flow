package io.agentflow.organization;

import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/**
 * 节点激活时解析有效成员并记录当时目录修订；单人候选和全员会签共用这个解析边界。
 * @author owlzhangfq@gmail.com
 */
@Service
public class OrganizationAssigneeResolver {
    private final OrganizationRepository repository;
    private final LocalOrganizationDirectory directory;

    /** 在实际审批事务中锁定目录，成员与目录修订保持一致。 */
    public OrganizationAssigneeResolver(OrganizationRepository repository, LocalOrganizationDirectory directory) {
        this.repository = repository; this.directory = directory;
    }

    /** 运行时无人匹配必须回滚节点推进，不能跳过人工审批。 */
    @Transactional
    public Selection resolve(String tenantId, String rule) {
        long revision = repository.lock(tenantId);
        List<String> members = directory.roleMembers(tenantId, rule.substring("role:".length()));
        if (members.isEmpty()) throw new DomainException("ORGANIZATION_NO_APPROVERS", "No active approvers match the organization rule");
        return new Selection(revision, rule, members);
    }

    /**
     * 当时的目录修订、规则和实际成员均保留，不从当前目录补写历史。
     * @author owlzhangfq@gmail.com
     */
    public record Selection(long directoryRevision, String rule, List<String> subjects) {
        public Selection { subjects = List.copyOf(subjects); }
    }
}
