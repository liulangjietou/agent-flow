package io.agentflow.approval.comment;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.workspace.JdbcWorkspaceReadAdapter;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.notification.TaskAudiencePort;
import io.agentflow.organization.LocalOrganizationDirectory;
import io.agentflow.organization.OrganizationPerson;
import io.agentflow.organization.OrganizationRepository;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 只从当前轮次的既有读取关系生成提醒目录，不枚举整个租户，也不授予新权限。
 * @author owlzhangfq@gmail.com
 */
@Component
public class CommentMentionDirectory {
    public static final int MAX_MENTIONS = 20;
    private final TaskAudiencePort tasks;
    private final JdbcWorkspaceReadAdapter workspace;
    private final LocalOrganizationDirectory directory;
    private final OrganizationRepository organizations;

    /** 任务、真实办理事实和当前人员启停状态共同决定可提醒范围。 */
    public CommentMentionDirectory(TaskAudiencePort tasks, JdbcWorkspaceReadAdapter workspace,
                                    LocalOrganizationDirectory directory, OrganizationRepository organizations) {
        this.tasks = tasks; this.workspace = workspace; this.directory = directory; this.organizations = organizations;
    }

    /** 分页只投影当前可提醒账号；每一页都重新授权，返回其所对应的申请版本。 */
    public Page page(Application application, Actor actor, Query query) {
        var found = recipients(application, actor).stream()
                .filter(user -> user.compareTo(query.afterUser()) > 0 && user.toLowerCase(Locale.ROOT).contains(query.q()))
                .limit(query.limit() + 1L).toList();
        var items = found.stream().limit(query.limit()).toList();
        return new Page(application.id(), application.roundNo(), application.version(), items,
                found.size() > items.size() ? items.get(items.size() - 1) : null);
    }

    /** 写事务中在申请锁之后锁目录，防止本地人员停用与提醒保存交错。 */
    public void requireRecipients(Application application, Actor actor, List<String> requested) {
        if (requested.isEmpty()) return;
        if (organizations.initialized(actor.tenantId())) organizations.lock(actor.tenantId());
        if (!recipients(application, actor).containsAll(requested)) {
            throw new DomainException("COMMENT_MENTION_UNAVAILABLE", "Mention recipients are no longer available for the current round");
        }
    }

    private Set<String> recipients(Application application, Actor actor) {
        var found = new TreeSet<String>();
        if (application.status() != ApplicationStatus.IN_APPROVAL) return found;
        // 申请人可以尚未登记本地组织；已明确停用的人员不能继续收到新的提醒。
        if (organizations.personBySubject(application.tenantId(), application.createdBy()).map(OrganizationPerson::active).orElse(true)) {
            found.add(application.createdBy());
        }
        tasks.unfinished(application.tenantId(), application.id()).forEach(task -> found.addAll(task.recipients()));
        workspace.participantsInRound(application.tenantId(), application.id(), application.roundNo()).stream()
                .filter(user -> directory.activeRecipient(application.tenantId(), user)).forEach(found::add);
        found.remove(actor.userId());
        return found;
    }

    /** 入口限定搜索长度、页大小和排序位置，客户端不能替换申请、租户或轮次。 */
    public record Query(String q, String afterUser, int limit) {
        /** 搜索条件只影响已有读取关系的投影，不作为授权依据。 */
        public static Query parse(Map<String, String> raw) {
            try {
                if (!Set.of("q", "afterUser", "limit").containsAll(raw.keySet())) throw invalid();
                String q = raw.getOrDefault("q", "").strip(), after = raw.getOrDefault("afterUser", "");
                int limit = Integer.parseInt(raw.getOrDefault("limit", "30"));
                if (q.length() > 128 || after.length() > 128 || limit < 1 || limit > 50) throw invalid();
                return new Query(q.toLowerCase(Locale.ROOT), after, limit);
            } catch (IllegalArgumentException exception) { throw invalid(); }
        }
        private static DomainException invalid() { return new DomainException("INVALID_COMMENT_QUERY", "Invalid comment mention query"); }
    }

    /** 名单只含已有账号标识，不输出组织资料或认证角色。 @author owlzhangfq@gmail.com */
    public record Page(UUID applicationId, int roundNo, long applicationVersion, List<String> items, String nextAfter) { }
}
