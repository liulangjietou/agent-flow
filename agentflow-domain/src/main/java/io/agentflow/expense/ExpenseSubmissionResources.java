package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 提交资源的纯领域编排；预检和最终提交使用相同规则，实际持久化由应用服务控制事务。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseSubmissionResources {
    /** 在副本上计算全部版本变化，失败不改变输入资源或留下半次预留。 */
    public Plan plan(ExpenseReport prepared, Resources resources, Instant at) {
        var next = prepared.currentRound();
        if (prepared.version() != next.submittedFinancialVersion() + 1 || !next.adjustments().isEmpty()) {
            throw new DomainException("EXPENSE_PRECHECK_REQUIRED", "Resource reservations require a fresh submission snapshot");
        }
        ExpenseRound previous = prepared.rounds().size() < 2 ? null : prepared.rounds().get(prepared.rounds().size() - 2);
        var desiredInvoices = new LinkedHashMap<UUID, ExpenseUse>();
        var desiredRequests = new HashMap<UUID, List<PriorUse>>();
        for (int index = 0; index < next.approvedLines().size(); index++) {
            var approved = next.approvedLines().get(index); var original = next.originalLines().get(index).original();
            if (approved.gross().value().signum() == 0) continue;
            var use = new ExpenseUse(prepared.id(), next.roundNo(), approved.lineNo());
            for (UUID id : original.invoiceIds()) desiredInvoices.put(id, use);
            if (original.priorRequest() != null) {
                var reference = original.priorRequest();
                desiredRequests.computeIfAbsent(reference.requestId(), id -> new ArrayList<>()).add(new PriorUse(reference.lineNo(), use, approved.gross()));
            }
        }
        var invoices = invoiceChanges(prepared, previous, desiredInvoices, resources, at);
        var requests = priorChanges(prepared, previous, desiredRequests, resources);
        var advances = advanceChanges(prepared, previous, resources);
        return new Plan(invoices, requests, advances);
    }

    private List<InvoiceChange> invoiceChanges(ExpenseReport report, ExpenseRound previous, Map<UUID, ExpenseUse> desired, Resources resources, Instant at) {
        var old = new HashMap<UUID, ExpenseUse>();
        if (previous != null) for (var line : previous.content().lines()) for (UUID id : line.invoiceIds()) {
            old.put(id, new ExpenseUse(report.id(), previous.roundNo(), line.lineNo()));
        }
        var releases = new ArrayList<InvoiceChange>(); var reservations = new ArrayList<InvoiceChange>();
        var canonical = new java.util.HashSet<InvoiceKey>();
        for (UUID id : union(old, desired)) {
            var invoice = Invoice.restore(require(resources.invoices(), id));
            tenant(report, invoice.tenantId()); var target = desired.get(id); var previousUse = old.get(id);
            if (invoice.occupation() == Invoice.Occupation.CONSUMED && invoice.use().equals(previousUse)) throw consumed();
            if (target == null) {
                if (invoice.occupation() == Invoice.Occupation.OCCUPIED && previousUse.equals(invoice.use())) {
                    invoice.release(invoice.version(), previousUse); releases.add(new InvoiceChange(invoice.state(), Operation.RELEASE));
                }
                continue;
            }
            if (!invoice.ownerId().equals(report.employeeId())) throw new DomainException("INVOICE_OWNER_MISMATCH", "Invoice belongs to another employee");
            var facts = invoice.requireVerified(at);
            if (!canonical.add(facts.key())) throw new DomainException("INVOICE_OCCUPIED", "The same canonical invoice appears in multiple originals");
            Operation operation;
            if (invoice.occupation() == Invoice.Occupation.AVAILABLE) {
                invoice.occupy(invoice.version(), target, report.employeeId(), report.content().legalEntityId(), at); operation = Operation.RESERVE;
            } else if (invoice.occupation() == Invoice.Occupation.OCCUPIED && invoice.use().equals(previousUse)) {
                invoice.move(invoice.version(), previousUse, target, report.content().legalEntityId(), at); operation = Operation.MOVE;
            } else throw new DomainException("INVOICE_OCCUPIED", "Invoice has another active or consumed occupation");
            reservations.add(new InvoiceChange(invoice.state(), operation));
        }
        // 替换成同票号的另一原件时，先释放旧键，再建立新键；整个计划必须在一个事务内保存。
        releases.addAll(reservations); return List.copyOf(releases);
    }

    private List<PriorChange> priorChanges(ExpenseReport report, ExpenseRound previous, Map<UUID, List<PriorUse>> desired, Resources resources) {
        var old = new HashMap<UUID, List<PriorUse>>();
        if (previous != null) for (var line : previous.content().lines()) if (line.priorRequest() != null) {
            old.computeIfAbsent(line.priorRequest().requestId(), id -> new ArrayList<>()).add(new PriorUse(line.priorRequest().lineNo(),
                    new ExpenseUse(report.id(), previous.roundNo(), line.lineNo()), null));
        }
        var changes = new ArrayList<PriorChange>();
        for (UUID id : union(old, desired)) {
            var request = ExpenseRequest.restore(require(resources.requests(), id)); tenant(report, request.tenantId());
            var targets = desired.getOrDefault(id, List.of());
            if (!targets.isEmpty()) resourceOwner(report, request.employeeId(), request.legalEntityId());
            var carried = new HashMap<ExpenseUse, PriorUse>();
            for (var previousUse : old.getOrDefault(id, List.of())) {
                if (request.balance(previousUse.sourceLine()).consumedFor(previousUse.use())) throw consumed();
                Money reserved = request.balance(previousUse.sourceLine()).reservedFor(previousUse.use());
                if (reserved.value().signum() == 0) continue;
                var target = targets.stream().filter(value -> value.sourceLine() == previousUse.sourceLine()
                        && value.use().lineNo() == previousUse.use().lineNo()).findFirst().orElse(null);
                if (target == null) {
                    request.reserve(request.version(), previousUse.sourceLine(), previousUse.use(), Money.zero(reserved.currency()));
                    changes.add(new PriorChange(request.state(), Operation.RELEASE));
                } else carried.put(target.use(), new PriorUse(previousUse.sourceLine(), previousUse.use(), reserved));
            }
            var ordered = new ArrayList<>(targets);
            // 同一批准行先减少旧预留，再增加其他行，避免最终合法的重分配被中间状态误判超额。
            ordered.sort(Comparator.comparingInt(PriorUse::sourceLine)
                    .thenComparing(value -> value.amount().value().subtract(carried.containsKey(value.use()) ? carried.get(value.use()).amount().value() : java.math.BigDecimal.ZERO))
                    .thenComparingInt(value -> value.use().lineNo()));
            for (var target : ordered) {
                var carry = carried.get(target.use());
                if (carry == null) request.reserve(request.version(), target.sourceLine(), target.use(), target.amount());
                else request.move(request.version(), target.sourceLine(), carry.use(), target.use(), target.amount());
                changes.add(new PriorChange(request.state(), carry == null ? Operation.RESERVE : Operation.MOVE));
            }
        }
        return List.copyOf(changes);
    }

    private List<AdvanceChange> advanceChanges(ExpenseReport report, ExpenseRound previous, Resources resources) {
        var desired = new HashMap<UUID, Money>();
        for (var offset : report.currentRound().advanceOffsets()) if (offset.amount().value().signum() > 0) desired.put(offset.advanceId(), offset.amount());
        var old = new HashMap<UUID, Money>();
        if (previous != null) for (var offset : previous.advanceOffsets()) if (offset.amount().value().signum() > 0) old.put(offset.advanceId(), offset.amount());
        var changes = new ArrayList<AdvanceChange>(); var target = new ExpenseUse(report.id(), report.currentRound().roundNo(), 0);
        ExpenseUse previousUse = previous == null ? null : new ExpenseUse(report.id(), previous.roundNo(), 0);
        for (UUID id : union(old, desired)) {
            var advance = EmployeeAdvance.restore(require(resources.advances(), id)); tenant(report, advance.tenantId());
            Money amount = desired.get(id);
            if (old.containsKey(id) && advance.balance().consumedFor(previousUse)) throw consumed();
            boolean retained = old.containsKey(id) && advance.balance().reservedFor(previousUse).value().signum() > 0;
            if (amount == null) {
                if (retained) {
                    advance.reserve(advance.version(), previousUse, Money.zero(advance.balance().limit().currency()));
                    changes.add(new AdvanceChange(advance.state(), Operation.RELEASE));
                }
            } else {
                resourceOwner(report, advance.employeeId(), advance.legalEntityId());
                if (retained) advance.move(advance.version(), previousUse, target, amount);
                else advance.reserve(advance.version(), target, amount);
                changes.add(new AdvanceChange(advance.state(), retained ? Operation.MOVE : Operation.RESERVE));
            }
        }
        return List.copyOf(changes);
    }

    private static List<UUID> union(Map<UUID, ?> first, Map<UUID, ?> second) {
        return java.util.stream.Stream.concat(first.keySet().stream(), second.keySet().stream()).distinct().sorted().toList();
    }
    private static <T> T require(Map<UUID, T> values, UUID id) {
        var found = values.get(id);
        if (found == null) throw new DomainException("EXPENSE_RESOURCE_UNAVAILABLE", "A referenced financial resource is unavailable");
        return found;
    }
    private static void tenant(ExpenseReport report, String tenant) {
        if (!report.tenantId().equals(tenant)) throw new DomainException("EXPENSE_RESOURCE_UNAVAILABLE", "Financial resource tenant does not match");
    }
    private static void resourceOwner(ExpenseReport report, String employee, UUID entity) {
        if (!report.employeeId().equals(employee) || !report.content().legalEntityId().equals(entity)) {
            throw new DomainException("EXPENSE_RESOURCE_OWNER_MISMATCH", "Financial resource employee or legal entity does not match");
        }
    }
    private static DomainException consumed() { return new DomainException("RESERVATION_ALREADY_CONSUMED", "A settled previous expense round cannot reserve resources again"); }

    /**
     * 只传不可变快照，计算过程不会修改仓储刚读到的共享实例。
     * @author owlzhangfq@gmail.com
     */
    public record Resources(Map<UUID, Invoice.State> invoices, Map<UUID, ExpenseRequest.State> requests, Map<UUID, EmployeeAdvance.State> advances) {
        /** 输入集合在预检与提交之间不可由调用方改写。 */
        public Resources { invoices = Map.copyOf(invoices); requests = Map.copyOf(requests); advances = Map.copyOf(advances); }
    }
    /**
     * 按顺序保存每个中间财务版本，而不是丢掉多行预留的审计版本。
     * @author owlzhangfq@gmail.com
     */
    public record Plan(List<InvoiceChange> invoices, List<PriorChange> requests, List<AdvanceChange> advances) {
        /** 计划只有全部计算成功后才返回，应用服务仍需复核输入版本及当前授权。 */
        public Plan { invoices = List.copyOf(invoices); requests = List.copyOf(requests); advances = List.copyOf(advances); }
    }
    /**
     * 单次发票版本转换及其明确业务目的。
     * @author owlzhangfq@gmail.com
     */
    public record InvoiceChange(Invoice.State after, Operation operation) { }
    /**
     * 单次事前额度转换，保存后一个版本再执行下一个。
     * @author owlzhangfq@gmail.com
     */
    public record PriorChange(ExpenseRequest.State after, Operation operation) { }
    /**
     * 单次借款余额转换；预留仍不代表借款已经冲销。
     * @author owlzhangfq@gmail.com
     */
    public record AdvanceChange(EmployeeAdvance.State after, Operation operation) { }
    /**
     * 提交只允许预留、迁移或释放，不在此核销资金。
     * @author owlzhangfq@gmail.com
     */
    public enum Operation { RESERVE, MOVE, RELEASE }
    /**
     * 事前批准行及报销行的配对，迁移不得串到另一行的额度。
     * @author owlzhangfq@gmail.com
     */
    private record PriorUse(int sourceLine, ExpenseUse use, Money amount) { }
}
