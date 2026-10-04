package io.agentflow.expense;

import io.agentflow.calendar.CalendarRules;
import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

/**
 * 对已授权、已选定的费用事实作确定性计算；不认定违规、不决定审批，也不提供读取或模型发送权限。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseRiskEvidence {
    public static final int MAX_DOCUMENTS = 20;
    public static final int MAX_SELECTED_LINES = ExpenseContent.MAX_LINES;
    public static final int MAX_SELECTED_INVOICES = MAX_SELECTED_LINES * ExpenseLine.MAX_INVOICES;
    private static final int MAX_CATEGORY_CODE_LENGTH = 64;
    private static final Comparator<LineId> LINE_ORDER = Comparator.comparingInt(LineId::document).thenComparingInt(LineId::lineNo);
    private static final Comparator<InvoiceId> INVOICE_ORDER = Comparator.comparing(InvoiceId::line, LINE_ORDER).thenComparingInt(InvoiceId::ordinal);

    private ExpenseRiskEvidence() { }

    /** 输入由费用服务完成身份、轮次和字段授权；输出只保留选择内的序号，不复制原票号和其他敏感材料。 */
    public static Evidence derive(Input input) {
        var lines = input.lines().stream().sorted(Comparator.comparing(Line::id, LINE_ORDER)).toList();
        var grouped = new LinkedHashMap<DayKey, List<Line>>();
        for (var line : lines) grouped.computeIfAbsent(new DayKey(line.incurredOn(), line.categoryCode(), line.claimedGross().currency()), ignored -> new ArrayList<>()).add(line);
        var sameDay = new ArrayList<SameDay>();
        for (var entry : grouped.entrySet()) {
            var members = entry.getValue(); if (members.size() < 2) continue;
            // 合计只用于解释已选原币事实，不受单笔 Money 上限限制，也不能作为核定额或跨币种路由金额。
            var total = members.stream().map(line -> line.claimedGross().value()).reduce(BigDecimal.ZERO.setScale(Money.SCALE), BigDecimal::add);
            var key = entry.getKey();
            sameDay.add(new SameDay(key.date(), key.category(), key.currency(), members.stream().map(Line::id).toList(), total,
                    (int) members.stream().map(line -> line.id().document()).distinct().count()));
        }
        sameDay.sort(Comparator.comparing(SameDay::incurredOn).thenComparing(SameDay::categoryCode).thenComparing(SameDay::currency));
        var days = lines.stream().map(line -> workday(line, input.calendar())).toList();
        int selectedInvoices = lines.stream().mapToInt(Line::invoiceCount).sum();
        int distinctInvoices = (int) input.invoices().stream().map(Invoice::key).distinct().count();
        return new Evidence(sameDay, acrossDocuments(lines), days, consecutive(input.invoices()),
                new InvoiceCoverage(selectedInvoices, input.invoices().size(), distinctInvoices, selectedInvoices == input.invoices().size()));
    }

    private static List<CrossDocument> acrossDocuments(List<Line> lines) {
        var groups = new LinkedHashMap<CategoryCurrency, List<Line>>();
        for (var line : lines) groups.computeIfAbsent(new CategoryCurrency(line.categoryCode(), line.claimedGross().currency()), ignored -> new ArrayList<>()).add(line);
        var result = new ArrayList<CrossDocument>();
        for (var entry : groups.entrySet()) {
            var members = entry.getValue(); int documents = (int) members.stream().map(line -> line.id().document()).distinct().count();
            if (documents < 2) continue;
            var dates = members.stream().map(Line::incurredOn).sorted().toList();
            var total = members.stream().map(line -> line.claimedGross().value()).reduce(BigDecimal.ZERO.setScale(Money.SCALE), BigDecimal::add);
            result.add(new CrossDocument(entry.getKey().category(), entry.getKey().currency(), members.stream().map(Line::id).toList(),
                    total, documents, dates.get(0), dates.get(dates.size() - 1)));
        }
        result.sort(Comparator.comparing(CrossDocument::categoryCode).thenComparing(CrossDocument::currency));
        return List.copyOf(result);
    }

    private static Workday workday(Line line, CalendarRules calendar) {
        if (calendar == null) return new Workday(line.id(), line.incurredOn(), DayStatus.NOT_PROVIDED, DayBasis.NOT_PROVIDED);
        boolean override = calendar.overrides().stream().anyMatch(day -> day.date().equals(line.incurredOn()));
        return new Workday(line.id(), line.incurredOn(), calendar.periodsOn(line.incurredOn()).isEmpty() ? DayStatus.NON_WORKING : DayStatus.WORKING,
                override ? DayBasis.DATE_OVERRIDE : DayBasis.WEEKLY);
    }

    private static List<InvoiceSequence> consecutive(List<Invoice> invoices) {
        var groups = new HashMap<NumberGroup, TreeMap<BigInteger, List<InvoiceId>>>();
        for (var invoice : invoices) {
            var key = invoice.key(); var group = new NumberGroup(key.type(), key.code(), key.number().length());
            groups.computeIfAbsent(group, ignored -> new TreeMap<>()).computeIfAbsent(new BigInteger(key.number()), ignored -> new ArrayList<>()).add(invoice.id());
        }
        var result = new ArrayList<InvoiceSequence>();
        for (var group : groups.entrySet()) {
            BigInteger previous = null; var sequence = new ArrayList<NumberReferences>();
            for (var entry : group.getValue().entrySet()) {
                if (previous != null && !entry.getKey().subtract(previous).equals(BigInteger.ONE)) {
                    appendSequence(result, group.getKey(), sequence); sequence.clear();
                }
                sequence.add(new NumberReferences(entry.getValue().stream().sorted(INVOICE_ORDER).toList())); previous = entry.getKey();
            }
            appendSequence(result, group.getKey(), sequence);
        }
        // 公共顺序由已选来源序号固定，不使用会泄露代码或完整票号的分组键排序。
        result.sort(Comparator.comparing(sequence -> sequence.numbers().get(0).invoices().get(0), INVOICE_ORDER));
        return List.copyOf(result);
    }

    private static void appendSequence(List<InvoiceSequence> target, NumberGroup group, List<NumberReferences> sequence) {
        if (sequence.size() > 1) target.add(new InvoiceSequence(group.type(), group.width(), sequence));
    }

    /**
     * 费用服务构造的完整计算输入；原身份、租户、单据版本、票据权限和日历版本由调用方另外绑定。
     * @author owlzhangfq@gmail.com
     */
    public record Input(List<Line> lines, List<Invoice> invoices, CalendarRules calendar) {
        /** 同一选中行只计算一次；查验票据必须属于输入中的行且不能超过该行实际票数。 */
        public Input {
            if (CollectionUtils.isEmpty(lines) || lines.size() > MAX_SELECTED_LINES || lines.stream().anyMatch(java.util.Objects::isNull)
                    || invoices == null || invoices.size() > MAX_SELECTED_INVOICES
                    || invoices.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
            var byId = new HashMap<LineId, Line>();
            for (var line : lines) if (byId.put(line.id(), line) != null) throw invalid();
            var ids = new HashSet<InvoiceId>(); var counts = new HashMap<LineId, Integer>();
            for (var invoice : invoices) {
                var line = byId.get(invoice.id().line());
                if (line == null || !ids.add(invoice.id()) || invoice.id().ordinal() > line.invoiceCount()
                        || counts.merge(line.id(), 1, Integer::sum) > line.invoiceCount()) throw invalid();
            }
            lines = List.copyOf(lines); invoices = List.copyOf(invoices);
        }
    }

    /**
     * 本次选择内的单据序号和原行号，不接受租户、人员或数据库标识作为解释定位。
     * @author owlzhangfq@gmail.com
     */
    public record LineId(int document, int lineNo) {
        /** 行号沿用费用明细范围，单据序号由授权后的选择映射生成。 */
        public LineId { if (document < 1 || document > MAX_DOCUMENTS || lineNo < 1 || lineNo > ExpenseContent.MAX_LINES) throw invalid(); }
    }

    /**
     * 单行仅含日期、类别、原币申报额和票数，不携带自由文本、分摊、人员、账户或附件。
     * @author owlzhangfq@gmail.com
     */
    public record Line(LineId id, String categoryCode, LocalDate incurredOn, Money claimedGross, int invoiceCount) {
        /** 只接收来源中已有的费用发生日，不把区间结束日推断成每日消费事实。 */
        public Line {
            if (id == null || StringUtils.isBlank(categoryCode) || categoryCode.length() > MAX_CATEGORY_CODE_LENGTH || incurredOn == null || claimedGross == null
                    || claimedGross.value().signum() <= 0 || invoiceCount < 0 || invoiceCount > ExpenseLine.MAX_INVOICES) throw invalid();
        }
    }

    /**
     * 发票在原行选择中的位置，空缺序号仍可表示其他票据尚无可信查验事实。
     * @author owlzhangfq@gmail.com
     */
    public record InvoiceId(LineId line, int ordinal) {
        /** 序号上界沿用单行票据限制，不是票号或票据数据库标识。 */
        public InvoiceId { if (line == null || ordinal < 1 || ordinal > ExpenseLine.MAX_INVOICES) throw invalid(); }
    }

    /**
     * 仅供本地相邻比较的已查验规范票号，不能作为模型输入整体序列化。
     * @author owlzhangfq@gmail.com
     */
    public record Invoice(InvoiceId id, InvoiceKey key) {
        /** 未查验票据由覆盖信息表达，不能用 OCR 候选填入此事实。 */
        public Invoice { if (id == null || key == null) throw invalid(); }
    }

    /**
     * 相同日期、类别和币种的已选费用行，只表达重合与原币合计，不认定重复报销或拆单。
     * @author owlzhangfq@gmail.com
     */
    public record SameDay(LocalDate incurredOn, String categoryCode, String currency, List<LineId> lines, BigDecimal claimedTotal, int documentCount) {
        /** 输出引用冻结，不能由之后修改选择列表改变。 */
        public SameDay { lines = List.copyOf(lines); }
    }

    /**
     * 已选对照单中的同类费用分布；日期范围来自所选事实，不是企业配置的拆单窗口或审批阈值。
     * @author owlzhangfq@gmail.com
     */
    public record CrossDocument(String categoryCode, String currency, List<LineId> lines, BigDecimal claimedTotal,
                                int documentCount, LocalDate firstIncurredOn, LocalDate lastIncurredOn) {
        /** 原币合计和行引用只用于人工核对，不能据此修改路由金额。 */
        public CrossDocument { lines = List.copyOf(lines); }
    }

    /**
     * 费用发生日对应的所选工作日历结果，NON_WORKING 不等同于法定节假日。
     * @author owlzhangfq@gmail.com
     */
    public record Workday(LineId line, LocalDate incurredOn, DayStatus status, DayBasis basis) { }

    /**
     * 显式区分没有日历依据和配置中的工作、休息日期。
     * @author owlzhangfq@gmail.com
     */
    public enum DayStatus { NOT_PROVIDED, WORKING, NON_WORKING }

    /**
     * 日期覆盖优先于周规则，不从星期几猜测企业节假日安排。
     * @author owlzhangfq@gmail.com
     */
    public enum DayBasis { NOT_PROVIDED, WEEKLY, DATE_OVERRIDE }

    /**
     * 同一规范票号的所有已选引用；重复出现不能增加连续号码的长度。
     * @author owlzhangfq@gmail.com
     */
    public record NumberReferences(List<InvoiceId> invoices) {
        /** 同票不同来源位置保留，供人工核对合法重用或历史关系。 */
        public NumberReferences { invoices = List.copyOf(invoices); }
    }

    /**
     * 同类型、同代码、同号码宽度下按一递增的引用组；不公开代码、起止号码或推断开票方。
     * @author owlzhangfq@gmail.com
     */
    public record InvoiceSequence(InvoiceKey.Type type, int numberWidth, List<NumberReferences> numbers) {
        /** 连续段顺序保留数学相邻关系，段内引用保留原选择位置。 */
        public InvoiceSequence { numbers = List.copyOf(numbers); }
    }

    /**
     * 只描述选中行内票据的比较覆盖，空结果不能被解释成全部票据均已查验。
     * @author owlzhangfq@gmail.com
     */
    public record InvoiceCoverage(int selectedCount, int verifiedReferences, int distinctIdentities, boolean complete) { }

    /**
     * 可核验的局部观察，既不是违规结论也不是批准或核减命令。
     * @author owlzhangfq@gmail.com
     */
    public record Evidence(List<SameDay> sameDay, List<CrossDocument> crossDocument, List<Workday> workdays,
                           List<InvoiceSequence> invoiceSequences, InvoiceCoverage invoiceCoverage) {
        /** 所有派生集合不可变，便于后续与原事实摘要绑定。 */
        public Evidence {
            sameDay = List.copyOf(sameDay); crossDocument = List.copyOf(crossDocument);
            workdays = List.copyOf(workdays); invoiceSequences = List.copyOf(invoiceSequences);
        }
    }

    /**
     * 日内合计不混用类别和币种。
     * @author owlzhangfq@gmail.com
     */
    private record DayKey(LocalDate date, String category, String currency) { }

    /**
     * 跨已选单据比较不混合币种或不同费用类别。
     * @author owlzhangfq@gmail.com
     */
    private record CategoryCurrency(String category, String currency) { }

    /**
     * 完整票号分组键仅在本地计算期间存在。
     * @author owlzhangfq@gmail.com
     */
    private record NumberGroup(InvoiceKey.Type type, String code, int width) { }

    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_RISK_FACTS", "Select bounded expense lines and matching verified invoice references"); }
}
