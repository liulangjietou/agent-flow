package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseContent;
import io.agentflow.expense.ExpenseRiskEvidence;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

/**
 * 风险提示的本地来源绑定；单据身份、轮次和日历版本保留在本地，只有明确选择的 sources 可以发送。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseRiskInput(List<Document> documents, CalendarReference calendar, List<Concern> concerns,
                               List<AssistModelPort.Source> sources) {
    public static final String COVERAGE_SOURCE = "expense:coverage";
    public static final int MAX_CONCERNS = 20;
    private static final int MAX_LABEL_LENGTH = 256;

    /** 主单据固定为序号一；所有比较范围、覆盖信息及所选观察都必须由操作者明确确认。 */
    public ExpenseRiskInput {
        if (CollectionUtils.isEmpty(documents) || documents.size() > ExpenseRiskEvidence.MAX_DOCUMENTS
                || documents.stream().anyMatch(Objects::isNull) || CollectionUtils.isEmpty(concerns)
                || concerns.size() > MAX_CONCERNS || concerns.stream().anyMatch(Objects::isNull)
                || CollectionUtils.isEmpty(sources) || sources.size() > AssistInput.MAX_REFERENCES) throw invalid();
        var reportIds = new HashSet<UUID>(); var applicationIds = new HashSet<UUID>(); var expectedSources = new HashSet<String>();
        int selectedLines = 0;
        for (int index = 0; index < documents.size(); index++) {
            var document = documents.get(index);
            if (document.ordinal() != index + 1 || !reportIds.add(document.reportId()) || !applicationIds.add(document.applicationId())) throw invalid();
            selectedLines += document.lineNos().size(); expectedSources.add(document.sourceId());
        }
        if (selectedLines > ExpenseRiskEvidence.MAX_SELECTED_LINES) throw invalid();
        expectedSources.add(COVERAGE_SOURCE);
        int documentCount = documents.size();
        for (var concern : concerns) {
            if (!expectedSources.add(concern.sourceId()) || concern.documents().stream().anyMatch(index -> index > documentCount)
                    || concern.kind() == Kind.NON_WORKING_DAY && calendar == null) throw invalid();
        }
        var actualSources = new HashSet<String>();
        for (var source : sources) {
            if (source == null || source.reference() == null || StringUtils.isBlank(source.label()) || source.label().length() > MAX_LABEL_LENGTH
                    || StringUtils.isBlank(source.content()) || !actualSources.add(source.reference().sourceId())) throw invalid();
        }
        if (!actualSources.equals(expectedSources)) throw invalid();
        documents = List.copyOf(documents); concerns = List.copyOf(concerns); sources = List.copyOf(sources);
    }

    /** 服务端来源序号对应所选主单据及对照单，不使用原单号构造模型定位。 */
    public static String documentSource(int ordinal) { return "expense:document[" + ordinal + "]"; }

    /** 模型须逐项解释这些观察，不能遗漏、增加或变更观察类别。 */
    public List<String> concernIds() { return concerns.stream().map(Concern::sourceId).toList(); }

    /**
     * 每份来源绑定原申请版本、费用轮次版本及完整原事实摘要；所选行号只在该轮内解释。
     * @author owlzhangfq@gmail.com
     */
    public record Document(int ordinal, UUID reportId, UUID applicationId, long applicationVersion, int roundNo,
                           long financialVersion, String snapshotDigest, List<Integer> lineNos) {
        /** 重复单据或行号会扩大比较范围，因此在输入边界直接拒绝。 */
        public Document {
            if (ordinal < 1 || ordinal > ExpenseRiskEvidence.MAX_DOCUMENTS || reportId == null || applicationId == null
                    || applicationVersion < 1 || roundNo < 1 || financialVersion < 1 || !digest(snapshotDigest)
                    || CollectionUtils.isEmpty(lineNos) || lineNos.size() > ExpenseRiskEvidence.MAX_SELECTED_LINES
                    || lineNos.stream().anyMatch(line -> line == null || line < 1 || line > ExpenseContent.MAX_LINES)
                    || new HashSet<>(lineNos).size() != lineNos.size()) throw invalid();
            lineNos = lineNos.stream().sorted().toList();
        }
        /** 原始身份不进入发送来源的标识。 */
        public String sourceId() { return documentSource(ordinal); }
    }

    /**
     * 日历选择固定版本与规则摘要；管理员修改后须重新选择，不能把新日历套到旧同意上。
     * @author owlzhangfq@gmail.com
     */
    public record CalendarReference(UUID id, long revision, String rulesDigest) {
        /** 身份与规则摘要由工作日历读取边界提供。 */
        public CalendarReference { if (id == null || revision < 1 || !digest(rulesDigest)) throw invalid(); }
    }

    /**
     * 观察类型只是本地事实的解释方向，不是违规程度、审批结论或执行动作。
     * @author owlzhangfq@gmail.com
     */
    public enum Kind { SAME_DAY, CROSS_DOCUMENT, NON_WORKING_DAY, CONSECUTIVE_INVOICES }

    /**
     * 一个观察只引用本次选择的单据序号；其原文通过独立来源摘要绑定。
     * @author owlzhangfq@gmail.com
     */
    public record Concern(String sourceId, Kind kind, List<Integer> documents) {
        /** 跨单观察必须有两份不同单据，其他观察也必须具有可定位的费用来源。 */
        public Concern {
            if (sourceId == null || !sourceId.matches("expense:risk\\[[1-9][0-9]{0,3}\\]") || kind == null
                    || CollectionUtils.isEmpty(documents) || documents.size() > ExpenseRiskEvidence.MAX_DOCUMENTS
                    || documents.stream().anyMatch(index -> index == null || index < 1 || index > ExpenseRiskEvidence.MAX_DOCUMENTS)
                    || new HashSet<>(documents).size() != documents.size() || kind == Kind.CROSS_DOCUMENT && documents.size() < 2) throw invalid();
            documents = documents.stream().sorted().toList();
        }
        /** 人工解释必须同时引用观察本身及参与该观察的原费用来源。 */
        public List<String> requiredSourceIds() {
            var ids = documents.stream().map(ExpenseRiskInput::documentSource).collect(Collectors.toCollection(java.util.ArrayList::new));
            ids.add(sourceId); ids.add(COVERAGE_SOURCE); return List.copyOf(ids);
        }
    }

    private static boolean digest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static DomainException invalid() { return new DomainException("INVALID_AGENT_INPUT", "Risk evidence must bind bounded selected documents and observations"); }
}
