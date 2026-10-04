package io.agentflow.expense;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.BudgetPrecheckPort;
import io.agentflow.finance.EmployeeAccountSnapshot;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 项目全集、可信目录身份和旧预检字节兼容；同一负责人不能抹掉其承担的多个项目。
 * @author owlzhangfq@gmail.com
 */
class ExpenseProjectOwnersTest {
    private static final UUID LEGAL = UUID.fromString("00000000-0000-0000-0000-000000000014");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000015");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 4);
    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private final JsonUtil json = new JsonUtil(new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));

    @Test void repeatedProjectsAndSharedOwnerRetainEveryProjectButOnlyOneSubject() {
        var content = content("B", "A", "B");
        var source = ExpenseProjectOwners.from(catalog(List.of(project("B", "manager"), project("A", "manager"),
                project("UNUSED", null), new FinanceCatalog.Project(OTHER, "A", "另一法人项目", "other-owner"))), content);
        assertThat(source.projects()).extracting(FinanceCatalog.Project::code).containsExactly("A", "B");
        assertThat(source.subjects()).containsExactly("manager");
        assertThat(source.matches("catalog-v1", content)).isTrue();
        assertThat(json.read(json.write(source), ExpenseProjectOwners.class)).isEqualTo(source);
        var mutable = new ArrayList<>(source.projects());
        var copied = new ExpenseProjectOwners(source.catalogVersion(), LEGAL, mutable); mutable.clear();
        assertThat(copied).isEqualTo(source);
        assertThatThrownBy(() -> copied.projects().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void selectedProjectCannotBeMissingOwnerOrBorrowAnotherLegalEntityOwner() {
        for (var entries : List.of(List.<FinanceCatalog.Project>of(), List.of(project("A", null)),
                List.of(new FinanceCatalog.Project(OTHER, "A", "另一法人项目", "manager")))) {
            fails("EXPENSE_PROJECT_OWNER_UNAVAILABLE", () -> ExpenseProjectOwners.from(catalog(entries), content("A")));
        }
        var source = ExpenseProjectOwners.from(catalog(List.of(project("A", "manager"))), content("A"));
        assertThat(source.matches("another-version", content("A"))).isFalse();
        assertThat(source.matches("catalog-v1", content("A", "B"))).isFalse();
        assertThat(source.matches("catalog-v1", content())).isFalse();
        var foreign = new ExpenseContent(OTHER, ExpenseContent.Type.DAILY, "合成项目费用", content("A").lines(), List.of());
        assertThat(source.matches("catalog-v1", foreign)).isFalse();
    }

    @Test void emptyProjectSetIsAnExplicitSourceAndHistoricalCatalogStillReads() {
        var source = ExpenseProjectOwners.from(catalog(List.of(project("UNUSED", null))), content());
        assertThat(source.projects()).isEmpty(); assertThat(source.subjects()).isEmpty();
        assertThat(source.matches("catalog-v1", content())).isTrue();
        String original = "{\"legalEntityId\":\"" + LEGAL + "\",\"code\":\"A\",\"name\":\"旧项目\"}";
        assertThat(json.write(json.read(original, FinanceCatalog.Project.class))).isEqualTo(original);
    }

    @Test void snapshotRejectsDuplicateMissingOrForeignProjectIdentity() {
        var project = project("A", "manager");
        for (var entries : List.of(List.of(project, project), List.of(project("A", null)),
                List.of(new FinanceCatalog.Project(OTHER, "A", "另一法人项目", "manager")))) {
            fails("INVALID_EXPENSE_PROJECT_OWNERS", () -> new ExpenseProjectOwners("catalog-v1", LEGAL, entries));
        }
        fails("INVALID_EXPENSE_PROJECT_OWNERS", () -> new ExpenseProjectOwners(" ", LEGAL, List.of()));
        fails("INVALID_EXPENSE_PROJECT_OWNERS", () -> new ExpenseProjectOwners("v", null, List.of()));
        fails("INVALID_EXPENSE_PROJECT_OWNERS", () -> new ExpenseProjectOwners("v", LEGAL, null));
    }

    @Test void ownerSubjectIsOptionalOnlyForHistoricalCatalogAndNeverCoercedOrTrimmed() {
        for (String invalid : List.of("", " ", "x".repeat(129))) {
            fails("INVALID_FINANCE_CATALOG", () -> project("A", invalid));
        }
        assertThat(project("A", "manager").ownerSubject()).isEqualTo("manager");
        assertThat(new FinanceCatalog.Project(LEGAL, "A", "旧项目").ownerSubject()).isNull();
    }

    @Test void precheckSourceMustMatchItsVersionLegalEntityAndFullAllocationSet() {
        var old = evidence(content("A"));
        assertThat(old.projectOwners()).isNull();
        var source = ExpenseProjectOwners.from(catalog(List.of(project("A", "manager"))), old.preview().content());
        var current = withOwners(old, source);
        assertThat(current.projectOwners()).isEqualTo(source);
        for (var invalid : List.of(new ExpenseProjectOwners("another-version", LEGAL, source.projects()),
                new ExpenseProjectOwners("catalog-v1", LEGAL, List.of()),
                new ExpenseProjectOwners("catalog-v1", LEGAL, List.of(project("A", "manager"), project("B", "manager"))),
                new ExpenseProjectOwners("catalog-v1", OTHER, List.of(new FinanceCatalog.Project(OTHER, "A", "另一法人项目", "manager"))))) {
            fails("INVALID_EXPENSE_PRECHECK", () -> withOwners(old, invalid));
        }
    }

    private ExpensePrecheckEvidence withOwners(ExpensePrecheckEvidence old, ExpenseProjectOwners owners) {
        return new ExpensePrecheckEvidence(old.catalogVersion(), old.legalEntity(), old.rateDate(), old.budget(), old.preview(),
                old.resources(), old.invoices(), old.validUntil(), old.policySelection(), old.priorControls(), owners);
    }
    private ExpensePrecheckEvidence evidence(ExpenseContent content) {
        var report = ExpenseReport.draft(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice", content);
        var amount = money("10");
        var assessment = new ExpenseAssessment(new ExpenseExchangeRate("CNY", "CNY", BigDecimal.ONE, "rate-v1", DATE),
                new ExpensePolicySnapshot(UUID.randomUUID(), 1, amount, amount, ExpensePolicySnapshot.Decision.WITHIN_LIMIT,
                        "synthetic", "synthetic"), money("0"));
        report.freeze(1, 1, "CNY", new EmployeeAccountSnapshot(LEGAL, "alice", "private-ref", "****1234", "a".repeat(64), "v1"),
                Map.of(1, assessment), "alice", NOW);
        var budget = new BudgetPrecheckPort.Assessment(BudgetPrecheckPort.Request.from(report, DATE), "budget-v1", NOW, NOW.plusSeconds(60));
        return new ExpensePrecheckEvidence("catalog-v1", legal(LEGAL), DATE, budget, report.currentRound(), List.of(), List.of(), NOW.plusSeconds(60));
    }
    private static FinanceCatalog catalog(List<FinanceCatalog.Project> projects) {
        return new FinanceCatalog("alice", "catalog-v1", NOW.plusSeconds(60), List.of(legal(LEGAL), legal(OTHER)),
                List.of(), List.of(), projects, List.of());
    }
    private static FinanceCatalog.LegalEntity legal(UUID id) { return new FinanceCatalog.LegalEntity(id, "合成法人", "CNY", false, "entity-v1", "Asia/Shanghai"); }
    private static FinanceCatalog.Project project(String code, String owner) { return new FinanceCatalog.Project(LEGAL, code, "项目" + code, owner); }
    private static ExpenseContent content(String... projects) {
        var lines = new ArrayList<ExpenseLine>();
        for (int i = 0; i < Math.max(1, projects.length); i++) {
            lines.add(new ExpenseLine(i + 1, "OFFICE", DATE, null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM, money("10"), money("0"),
                    List.of(), null, List.of(new CostAllocation("IT", projects.length == 0 ? null : projects[i], money("10"))), "合成费用", null));
        }
        return new ExpenseContent(LEGAL, ExpenseContent.Type.DAILY, "合成项目费用", lines, List.of());
    }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private static void fails(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(DomainException.class).extracting(error -> ((DomainException) error).code()).isEqualTo(code);
    }
}
