package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceCatalog;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

/**
 * 本次全部项目的可信负责人来源；项目映射完整保留，组织资格由提交编排核验。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseProjectOwners(String catalogVersion, UUID legalEntityId, List<FinanceCatalog.Project> projects) {
    /** 缺负责人或重复项目不能恢复为有效责任；空集合明确表示本次没有项目。 */
    public ExpenseProjectOwners {
        if (StringUtils.isBlank(catalogVersion) || catalogVersion.length() > 128 || legalEntityId == null || projects == null) throw invalid();
        var codes = new HashSet<String>();
        for (var project : projects) {
            if (project == null || !legalEntityId.equals(project.legalEntityId()) || project.ownerSubject() == null
                    || !codes.add(project.code())) throw invalid();
        }
        projects = projects.stream().sorted(Comparator.comparing(FinanceCatalog.Project::code)).toList();
    }

    /** 只接受该法人实际分摊中的项目，不用目录中其他项目或其他法人的负责人补缺。 */
    public static ExpenseProjectOwners from(FinanceCatalog catalog, ExpenseContent content) {
        var codes = projectCodes(content);
        var selected = catalog.projects().stream().filter(project -> project.legalEntityId().equals(content.legalEntityId())
                && codes.contains(project.code())).toList();
        if (selected.size() != codes.size() || selected.stream().anyMatch(project -> project.ownerSubject() == null)) {
            throw new DomainException("EXPENSE_PROJECT_OWNER_UNAVAILABLE", "Every allocated project requires an owner from the authorized finance catalog");
        }
        return new ExpenseProjectOwners(catalog.sourceVersion(), content.legalEntityId(), selected);
    }

    /** 预检、提交和恢复必须保留同一目录版本、法人及完整原项目集合。 */
    public boolean matches(String version, ExpenseContent content) {
        return catalogVersion.equals(version) && legalEntityId.equals(content.legalEntityId())
                && projects.stream().map(FinanceCatalog.Project::code).collect(Collectors.toSet()).equals(projectCodes(content));
    }

    /** 会签按主体去重，原项目到主体的映射仍保留在 projects 中。 */
    public List<String> subjects() { return projects.stream().map(FinanceCatalog.Project::ownerSubject).distinct().sorted().toList(); }

    private static Set<String> projectCodes(ExpenseContent content) {
        return content.lines().stream().flatMap(line -> line.allocations().stream()).map(CostAllocation::projectCode)
                .filter(Objects::nonNull).collect(Collectors.toSet());
    }
    private static DomainException invalid() {
        return new DomainException("INVALID_EXPENSE_PROJECT_OWNERS", "Project owners must retain a unique complete project mapping within one legal entity and catalog version");
    }
}
