package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseContent;
import io.agentflow.finance.FinanceCatalog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;

/**
 * 报销填报建议绑定原双版本、本人行程和明确选择的主数据；不包含金额、余额或审批事实。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseDraftAssistInput(UUID reportId, UUID applicationId, long applicationVersion, long financialVersion,
        UUID legalEntityId, ExpenseContent.Type reportType, String catalogVersion, Instant validUntil,
        String financeTargetDigest, List<Leg> itinerary, Options options, List<AssistModelPort.Source> sources) {
    public static final String BRIEF = "expense:brief";
    public static final String CATALOG = "expense:catalog";
    public static final int MAX_LEGS = 20;
    public static final int MAX_OPTIONS = 50;
    public static final int MAX_INPUT_BYTES = 64 * 1024;

    /** 发送清单与每段行程一一对应，引用摘要必须匹配已经冻结的原始内容。 */
    public ExpenseDraftAssistInput {
        if (reportId == null || applicationId == null || applicationVersion < 1 || financialVersion < 1
                || legalEntityId == null || reportType == null || !text(catalogVersion, 128) || validUntil == null
                || financeTargetDigest == null || !financeTargetDigest.matches("[a-f0-9]{64}") || options == null) throw invalid();
        itinerary = unique(itinerary, MAX_LEGS, Leg::id, false);
        var cityCodes = options.cities().stream().map(Choice::code).toList();
        if (itinerary.stream().anyMatch(leg -> !cityCodes.contains(leg.cityCode()))) throw invalid();
        var expectedSources = new HashSet<>(Set.of(BRIEF, CATALOG));
        itinerary.forEach(leg -> expectedSources.add(leg.sourceId()));
        if (sources == null || sources.size() != expectedSources.size()) throw invalid();
        int bytes = 0;
        for (var source : sources) {
            if (source == null || source.reference() == null || !text(source.label(), 256) || source.content() == null
                    || !expectedSources.remove(source.reference().sourceId())) throw invalid();
            byte[] content = source.content().getBytes(StandardCharsets.UTF_8);
            bytes += content.length;
            if (bytes > MAX_INPUT_BYTES || !digest(content).equals(source.reference().contentDigest())) throw invalid();
        }
        sources = List.copyOf(sources);
    }

    /** 过期主数据可以保留作历史依据，不能用于新执行或确认。 */
    public boolean currentAt(Instant at) { return at != null && validUntil.isAfter(at); }

    /** 模型引用只匹配本次已经授权的发送清单。 */
    public AssistInput.Reference reference(String sourceId) {
        return sources.stream().map(AssistModelPort.Source::reference).filter(value -> value.sourceId().equals(sourceId))
                .findFirst().orElseThrow(ExpenseDraftAssistInput::invalid);
    }

    /**
     * 本人提供的明确行程；生成结果只能引用本轮行程编号，不自行创造日期和城市。
     * @author owlzhangfq@gmail.com
     */
    public record Leg(int id, LocalDate startsOn, LocalDate endsOn, String cityCode, String purpose) {
        public Leg {
            if (id < 1 || id > MAX_LEGS || startsOn == null || endsOn == null || endsOn.isBefore(startsOn)
                    || !text(cityCode, 128) || !text(purpose, 2000)) throw invalid();
        }
        /** 本轮行程的证据键保持稳定，不能被解释为外部资源地址。 */
        public String sourceId() { return "expense:itinerary[" + id + "]"; }
    }

    /**
     * 主数据标识与展示名；法人归属在从本人授权目录投影时核对。
     * @author owlzhangfq@gmail.com
     */
    public record Choice(String code, String name) {
        public Choice { if (!text(code, 128) || !text(name, 128)) throw invalid(); }
    }

    /**
     * 明确选择的费用类别和分摊对象，不发送其他法人、完整财务账户或未选择的目录。
     * @author owlzhangfq@gmail.com
     */
    public record Options(List<FinanceCatalog.Category> categories, List<Choice> costCenters, List<Choice> projects, List<Choice> cities) {
        public Options {
            categories = unique(categories, MAX_OPTIONS, FinanceCatalog.Category::code, false);
            costCenters = unique(costCenters, MAX_OPTIONS, Choice::code, false);
            projects = unique(projects, MAX_OPTIONS, Choice::code, true);
            cities = unique(cities, MAX_LEGS, Choice::code, false);
        }
    }

    private static <T> List<T> unique(List<T> values, int maximum, Function<T, ?> key, boolean emptyAllowed) {
        if (values == null || !emptyAllowed && values.isEmpty() || values.size() > maximum
                || values.stream().anyMatch(java.util.Objects::isNull)
                || values.stream().map(key).distinct().count() != values.size()) throw invalid();
        return List.copyOf(values);
    }
    private static String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    private static boolean text(String value, int maximum) { return StringUtils.isNotBlank(value) && value.length() <= maximum; }
    private static DomainException invalid() { return new DomainException("INVALID_AGENT_INPUT", "Expense draft input or evidence is invalid"); }
}
