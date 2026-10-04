package io.agentflow.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseLine;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 财务主数据的按员工授权目录，法人、币种和成本对象不能由页面自行编造。
 * @author owlzhangfq@gmail.com
 */
public record FinanceCatalog(String employeeId, String sourceVersion, Instant validUntil, List<LegalEntity> legalEntities,
                             List<Category> categories, List<CostCenter> costCenters, List<Project> projects, List<City> cities) {
    private static final int MAX_ENTITIES = 200;
    private static final int MAX_ENTRIES = 2000;

    /** 目录只包含仍可用的对象，禁止重复键或孤立的成本对象。 */
    public FinanceCatalog {
        text(employeeId, 128); text(sourceVersion, 128);
        if (validUntil == null) throw invalid();
        legalEntities = copy(legalEntities, MAX_ENTITIES); categories = copy(categories, MAX_ENTRIES);
        costCenters = copy(costCenters, MAX_ENTRIES); projects = copy(projects, MAX_ENTRIES); cities = copy(cities, MAX_ENTRIES);
        Set<UUID> entities = new HashSet<>();
        for (var entity : legalEntities) if (!entities.add(entity.id())) throw invalid();
        if (categories.stream().map(Category::code).distinct().count() != categories.size()
                || cities.stream().map(City::code).distinct().count() != cities.size()) throw invalid();
        var centers = new HashSet<String>(); var projectKeys = new HashSet<String>();
        for (var center : costCenters) if (!entities.contains(center.legalEntityId()) || !centers.add(center.legalEntityId() + ":" + center.code())) throw invalid();
        for (var project : projects) if (!entities.contains(project.legalEntityId()) || !projectKeys.add(project.legalEntityId() + ":" + project.code())) throw invalid();
    }

    /** 按稳定标识选择本次法人，目录失效时应用服务重新读取。 */
    public LegalEntity legalEntity(UUID id) {
        return legalEntities.stream().filter(entity -> entity.id().equals(id)).findFirst()
                .orElseThrow(() -> new DomainException("FINANCE_LEGAL_ENTITY_UNAVAILABLE", "Legal entity is not available to this employee"));
    }

    private static <T> List<T> copy(List<T> entries, int maximum) {
        if (entries == null || entries.size() > maximum || entries.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
        return List.copyOf(entries);
    }
    private static void text(String value, int maximum) { if (StringUtils.isBlank(value) || value.length() > maximum) throw invalid(); }
    private static DomainException invalid() { return new DomainException("INVALID_FINANCE_CATALOG", "Finance catalog entries are invalid or inconsistent"); }

    /**
     * 法人本位币和纸质签收制度来自真实主数据。
     * @author owlzhangfq@gmail.com
     */
    public record LegalEntity(UUID id, String name, String baseCurrency, boolean paperReceiptRequired, String sourceVersion, String timeZone) {
        /** 法人必须明确本位币和业务日时区，不能使用服务器默认时区猜测提交日。 */
        public LegalEntity {
            if (id == null) throw invalid(); text(name, 256); text(sourceVersion, 128); Money.zero(baseCurrency); text(timeZone, 64);
            try { java.time.ZoneId.of(timeZone); } catch (java.time.DateTimeException malformed) { throw invalid(); }
        }
    }
    /**
     * 可用费用类别；具体标准额度仍由版本化制度判定。
     * @author owlzhangfq@gmail.com
     */
    public record Category(String code, String name, List<ExpenseLine.Unit> units) {
        /** 计量单位限制来自目录，不将页面自由文本解释为财务类别。 */
        public Category { text(code, 64); text(name, 128); units = copy(units, ExpenseLine.Unit.values().length); if (units.isEmpty() || new HashSet<>(units).size() != units.size()) throw invalid(); }
    }
    /**
     * 成本中心属于明确法人。
     * @author owlzhangfq@gmail.com
     */
    public record CostCenter(UUID legalEntityId, String code, String name) {
        /** 成本标识保留原样，不进行大小写合并。 */
        public CostCenter { if (legalEntityId == null) throw invalid(); text(code, 128); text(name, 128); }
    }
    /**
     * 项目属于明确法人。
     * @author owlzhangfq@gmail.com
     */
    public record Project(UUID legalEntityId, String code, String name,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String ownerSubject) {
        /** 旧目录没有负责人，不补造责任，也不改变其 JSON 字节形状。 */
        public Project(UUID legalEntityId, String code, String name) { this(legalEntityId, code, name, null); }
        /** 项目代码独立于展示名称，负责人使用来源系统的稳定主体。 */
        public Project {
            if (legalEntityId == null) throw invalid(); text(code, 128); text(name, 128);
            if (ownerSubject != null) text(ownerSubject, 128);
        }
    }
    /**
     * 城市代码用于费用标准匹配，名称只用于展示。
     * @author owlzhangfq@gmail.com
     */
    public record City(String code, String name) {
        /** 城市身份由主数据提供。 */
        public City { text(code, 128); text(name, 128); }
    }
}
