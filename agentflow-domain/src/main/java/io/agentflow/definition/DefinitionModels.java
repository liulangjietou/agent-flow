package io.agentflow.definition;

import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import io.agentflow.notification.NotificationTexts;

import java.math.BigDecimal;
import java.util.*;

/**
 * 流程定义上下文的领域模型，禁止依赖 Spring、Flowable 或脚本引擎。
 * @author owlzhangfq@gmail.com
 */
public final class DefinitionModels {
    private DefinitionModels() { }

    /**
     * 草稿状态。
     * @author owlzhangfq@gmail.com
     */
    public enum DraftStatus { DRAFT, PUBLISHED, ARCHIVED }
    /**
     * 节点类型。
     * @author owlzhangfq@gmail.com
     */
    public enum NodeType { START, END, USER_TASK, SERVICE_TASK, EXCLUSIVE_GATEWAY, PARALLEL_GATEWAY }

    /**
     * 单人办理或全员会签；未配置的历史节点保持单人办理。
     * @author owlzhangfq@gmail.com
     */
    public enum ApprovalMode { SINGLE, ALL }

    /**
     * 流程节点。
     * @author owlzhangfq@gmail.com
     */
    public record Node(String id, String name, NodeType type, Map<String, String> properties) {
        public Node {
            if (id == null || id.isBlank() || name == null || name.isBlank() || type == null) {
                throw new DomainException("INVALID_NODE", "Node id, name and type are required");
            }
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }

        /** 入口已校验模式合法性，发布适配器直接读取领域语义。 */
        public ApprovalMode approvalMode() {
            return ApprovalMode.valueOf(properties.getOrDefault("approvalMode", ApprovalMode.SINGLE.name()));
        }
    }

    /**
     * 流程边。
     * @author owlzhangfq@gmail.com
     */
    public record Edge(String id, String source, String target, String condition, boolean defaultBranch) {
        public Edge(String id, String source, String target, String condition) {
            this(id, source, target, condition, false);
        }

        public Edge {
            if (id == null || id.isBlank() || source == null || source.isBlank() || target == null || target.isBlank()) {
                throw new DomainException("INVALID_EDGE", "Edge id, source and target are required");
            }
            condition = condition == null ? "" : condition.trim();
        }
    }

    /**
     * 不可变的流程图。
     * @author owlzhangfq@gmail.com
     */
    public record Graph(List<Node> nodes, List<Edge> edges, Integer conditionLanguageVersion) {
        /** 历史调用方使用 v1，不能因服务升级而改变文本含义。 */
        public Graph(List<Node> nodes, List<Edge> edges) { this(nodes, edges, 1); }
        public Graph {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            edges = edges == null ? List.of() : List.copyOf(edges);
            conditionLanguageVersion = conditionLanguageVersion == null ? 1 : conditionLanguageVersion;
            if (conditionLanguageVersion != 1 && conditionLanguageVersion != 2) {
                throw new DomainException("INVALID_CONDITION_VERSION", "Unsupported condition language version");
            }
        }
        /** 按标识查找节点。 */
        public Node node(String id) { return nodes.stream().filter(n -> n.id().equals(id)).findFirst().orElse(null); }
    }

    /**
     * 流程定义草稿聚合。
     * @author owlzhangfq@gmail.com
     */
    public static final class DefinitionDraft {
        private final UUID id;
        private final String tenantId;
        private final String key;
        private String name;
        private long version;
        private long revision;
        private DraftStatus status;
        private Graph graph;
        private FormSchema formSchema;
        private NotificationTexts notificationTexts;

        /** 创建新草稿。 */
        public static DefinitionDraft create(UUID id, String tenantId, String key, String name, Graph graph) {
            return create(id, tenantId, key, name, graph, null);
        }

        /** 新建携带表单契约的草稿，表单与流程图共同发布。 */
        public static DefinitionDraft create(UUID id, String tenantId, String key, String name, Graph graph, FormSchema formSchema) {
            return create(id, tenantId, key, name, graph, formSchema, NotificationTexts.EMPTY);
        }

        /** 通知配置与表单、流程图共同绑定到本草稿版本。 */
        public static DefinitionDraft create(UUID id, String tenantId, String key, String name, Graph graph,
                                             FormSchema formSchema, NotificationTexts notificationTexts) {
            if (id == null || tenantId == null || tenantId.isBlank() || key == null || key.isBlank()) {
                throw new DomainException("INVALID_DEFINITION", "Definition id, tenant and key are required");
            }
            return new DefinitionDraft(id, tenantId, key, name, 0, 0, DraftStatus.DRAFT, graph, formSchema, notificationTexts);
        }

        /** 从持久化状态恢复草稿。 */
        public static DefinitionDraft restore(UUID id, String tenantId, String key, String name, long version,
                                               long revision, DraftStatus status, Graph graph) {
            return restore(id, tenantId, key, name, version, revision, status, graph, null);
        }

        /** 恢复当前记录自己的表单契约；旧记录保持无 schema 状态。 */
        public static DefinitionDraft restore(UUID id, String tenantId, String key, String name, long version,
                                              long revision, DraftStatus status, Graph graph, FormSchema formSchema) {
            return restore(id, tenantId, key, name, version, revision, status, graph, formSchema, NotificationTexts.EMPTY);
        }

        /** 恢复该版本自身文案；旧记录不读取后来变更的模板。 */
        public static DefinitionDraft restore(UUID id, String tenantId, String key, String name, long version,
                                              long revision, DraftStatus status, Graph graph, FormSchema formSchema,
                                              NotificationTexts notificationTexts) {
            return new DefinitionDraft(id, tenantId, key, name, version, revision, status, graph, formSchema, notificationTexts);
        }

        private DefinitionDraft(UUID id, String tenantId, String key, String name, long version, long revision,
                                DraftStatus status, Graph graph, FormSchema formSchema, NotificationTexts notificationTexts) {
            this.id = Objects.requireNonNull(id); this.tenantId = Objects.requireNonNull(tenantId);
            this.key = Objects.requireNonNull(key); this.name = Objects.requireNonNullElse(name, key);
            this.version = version; this.revision = revision; this.status = Objects.requireNonNull(status);
            this.graph = Objects.requireNonNull(graph);
            this.formSchema = formSchema;
            this.notificationTexts = Objects.requireNonNullElse(notificationTexts, NotificationTexts.EMPTY);
        }

        /** 更新草稿图并校验版本。 */
        public void update(String name, Graph graph, long expectedRevision) {
            update(name, graph, null, expectedRevision);
        }

        /** null 保留已有表单；显式空字段列表才将表单清空。 */
        public void update(String name, Graph graph, FormSchema formSchema, long expectedRevision) {
            update(name, graph, formSchema, null, expectedRevision);
        }

        /** 缺省配置保留旧值；显式空文案恢复平台提示，与草稿 revision 原子更新。 */
        public void update(String name, Graph graph, FormSchema formSchema, NotificationTexts notificationTexts, long expectedRevision) {
            ensureDraft(); ensureRevision(expectedRevision);
            Graph updatedGraph = Objects.requireNonNull(graph);
            if (formSchema != null) this.formSchema = formSchema;
            if (notificationTexts != null) this.notificationTexts = notificationTexts;
            this.name = Objects.requireNonNullElse(name, key); this.graph = updatedGraph; this.revision++;
        }

        /** 标记为已发布，发布后不可修改。 */
        public void publish(long expectedRevision, long nextVersion) {
            ensureDraft(); ensureRevision(expectedRevision);
            if (nextVersion <= version) {
                throw new DomainException("INVALID_DEFINITION_VERSION", "Published version must increase");
            }
            this.status = DraftStatus.PUBLISHED; this.version = nextVersion; this.revision++;
        }

        private void ensureDraft() { if (status != DraftStatus.DRAFT) throw new DomainException("DEFINITION_IMMUTABLE", "Published definition cannot be changed"); }
        private void ensureRevision(long expected) { if (revision != expected) throw new DomainException("CONCURRENCY_CONFLICT", "Definition revision has changed"); }

        public UUID id() { return id; } public String tenantId() { return tenantId; } public String key() { return key; }
        public String name() { return name; } public long version() { return version; } public long revision() { return revision; }
        public FormSchema formSchema() { return formSchema; }
        public NotificationTexts notificationTexts() { return notificationTexts; }
        public DraftStatus status() { return status; } public Graph graph() { return graph; }
    }

    /**
     * 条件运行上下文。
     * @author owlzhangfq@gmail.com
     */
    public record EvaluationContext(Map<String, Object> values, Map<String, String> fieldTypes) {
        public EvaluationContext(Map<String, Object> values) { this(values, null); }

        public EvaluationContext {
            // 未填写或已清空的表单值仍参与 EXISTS/NOT_EXISTS 判断，不能在构造上下文时拒绝 null。
            values = values == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(values));
            fieldTypes = fieldTypes == null ? null : Map.copyOf(fieldTypes);
        }
        /** 读取白名单字段。 */
        public Object value(String name) { return values.get(name); }
    }

    /**
     * 条件 AST。
     * @author owlzhangfq@gmail.com
     */
    public sealed interface ConditionAst permits Comparison, Logical, Negation, Membership {
        /** 在受限上下文中求值。 */
        boolean evaluate(EvaluationContext context);
    }
    /**
     * 比较表达式。
     * @author owlzhangfq@gmail.com
     */
    public record Comparison(String field, Operator operator, String literal) implements ConditionAst {
        public Comparison { if (field == null || !field.matches("[a-zA-Z][a-zA-Z0-9_.]{0,63}")) throw new DomainException("INVALID_CONDITION", "Field is not allowed"); }
        public boolean evaluate(EvaluationContext c) {
            Object raw = c.value(field);
            String declaredType = c.fieldTypes() == null ? null : c.fieldTypes().get(field);
            if (c.fieldTypes() != null && declaredType == null) throw new DomainException("INVALID_CONDITION", "Condition field is not declared");
            boolean absent = c.fieldTypes() == null ? raw == null : FormSchema.empty(raw, FormSchema.FieldType.valueOf(declaredType));
            if (absent) return operator == Operator.NOT_EXISTS;
            if (operator == Operator.EXISTS) return true;
            if (operator == Operator.NOT_EXISTS) return false;
            int cmp;
            if (c.fieldTypes() != null) {
                cmp = switch (FormSchema.FieldType.valueOf(declaredType)) {
                    case NUMBER -> FormSchema.decimal(raw.toString()).compareTo(FormSchema.decimal(literal));
                    case BOOLEAN -> Boolean.compare((Boolean) raw, Boolean.parseBoolean(literal));
                    case DATE, TEXT, TEXTAREA, SELECT -> raw.toString().compareTo(literal);
                    case TABLE -> throw new DomainException("INVALID_CONDITION", "Detail tables only support presence conditions");
                };
            } else {
                try { cmp = new BigDecimal(raw.toString()).compareTo(new BigDecimal(literal)); }
                catch (NumberFormatException ex) { cmp = raw.toString().compareTo(literal); }
            }
            return switch (operator) { case EQ -> cmp == 0; case NE -> cmp != 0; case GT -> cmp > 0; case GE -> cmp >= 0; case LT -> cmp < 0; case LE -> cmp <= 0; default -> false; };
        }
    }
    /**
     * 逻辑表达式。
     * @author owlzhangfq@gmail.com
     */
    public record Logical(Kind kind, List<ConditionAst> terms) implements ConditionAst {
        public Logical { terms = List.copyOf(terms == null ? List.of() : terms); }
        public boolean evaluate(EvaluationContext c) { return kind == Kind.AND ? terms.stream().allMatch(x -> x.evaluate(c)) : terms.stream().anyMatch(x -> x.evaluate(c)); }
    }
    /**
     * 对完整条件结果取反；未填写的比较原本为 false，取反后为 true。
     * @author owlzhangfq@gmail.com
     */
    public record Negation(ConditionAst term) implements ConditionAst {
        /** 使用与比较表达式一致的缺失值语义。 */
        public boolean evaluate(EvaluationContext context) { return !term.evaluate(context); }
    }
    /**
     * 枚举集合判断，仅支持表单单选字段的字面量选项。
     * @author owlzhangfq@gmail.com
     */
    public record Membership(String field, List<String> literals) implements ConditionAst {
        public Membership { literals = List.copyOf(literals); }
        /** 未填写不属于任何集合；值按枚举原文精确匹配。 */
        public boolean evaluate(EvaluationContext context) {
            if (context.fieldTypes() == null || !"SELECT".equals(context.fieldTypes().get(field))) {
                throw new DomainException("INVALID_CONDITION", "Membership requires a declared select field");
            }
            Object value = context.value(field);
            return !FormSchema.empty(value, FormSchema.FieldType.SELECT) && literals.contains(value.toString());
        }
    }
    /**
     * 比较运算符。
     * @author owlzhangfq@gmail.com
     */
    public enum Operator { EQ, NE, GT, GE, LT, LE, EXISTS, NOT_EXISTS }
    /**
     * 逻辑运算符。
     * @author owlzhangfq@gmail.com
     */
    public enum Kind { AND, OR }
}
