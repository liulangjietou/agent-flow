package io.agentflow.definition;

import io.agentflow.common.DomainException;

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
    public record Graph(List<Node> nodes, List<Edge> edges) {
        public Graph {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            edges = edges == null ? List.of() : List.copyOf(edges);
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

        /** 创建新草稿。 */
        public static DefinitionDraft create(UUID id, String tenantId, String key, String name, Graph graph) {
            if (id == null || tenantId == null || tenantId.isBlank() || key == null || key.isBlank()) {
                throw new DomainException("INVALID_DEFINITION", "Definition id, tenant and key are required");
            }
            return new DefinitionDraft(id, tenantId, key, name, 0, 0, DraftStatus.DRAFT, graph);
        }

        /** 从持久化状态恢复草稿。 */
        public static DefinitionDraft restore(UUID id, String tenantId, String key, String name, long version,
                                               long revision, DraftStatus status, Graph graph) {
            return new DefinitionDraft(id, tenantId, key, name, version, revision, status, graph);
        }

        private DefinitionDraft(UUID id, String tenantId, String key, String name, long version, long revision,
                                DraftStatus status, Graph graph) {
            this.id = Objects.requireNonNull(id); this.tenantId = Objects.requireNonNull(tenantId);
            this.key = Objects.requireNonNull(key); this.name = Objects.requireNonNullElse(name, key);
            this.version = version; this.revision = revision; this.status = Objects.requireNonNull(status);
            this.graph = Objects.requireNonNull(graph);
        }

        /** 更新草稿图并校验版本。 */
        public void update(String name, Graph graph, long expectedRevision) {
            ensureDraft(); ensureRevision(expectedRevision);
            this.name = Objects.requireNonNullElse(name, key); this.graph = Objects.requireNonNull(graph); this.revision++;
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
        public DraftStatus status() { return status; } public Graph graph() { return graph; }
    }

    /**
     * 条件运行上下文。
     * @author owlzhangfq@gmail.com
     */
    public record EvaluationContext(Map<String, Object> values) {
        public EvaluationContext { values = values == null ? Map.of() : Map.copyOf(values); }
        /** 读取白名单字段。 */
        public Object value(String name) { return values.get(name); }
    }

    /**
     * 条件 AST。
     * @author owlzhangfq@gmail.com
     */
    public sealed interface ConditionAst permits Comparison, Logical {
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
            Object raw = c.value(field); if (raw == null) return operator == Operator.NOT_EXISTS;
            if (operator == Operator.EXISTS) return true;
            if (operator == Operator.NOT_EXISTS) return false;
            int cmp;
            try { cmp = new BigDecimal(raw.toString()).compareTo(new BigDecimal(literal)); }
            catch (NumberFormatException ex) { cmp = raw.toString().compareTo(literal); }
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
