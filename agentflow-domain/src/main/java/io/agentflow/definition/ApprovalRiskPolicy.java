package io.agentflow.definition;

import io.agentflow.approval.model.SubmissionRisk;
import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import io.agentflow.definition.DefinitionModels.Comparison;
import io.agentflow.definition.DefinitionModels.ConditionAst;
import io.agentflow.definition.DefinitionModels.EvaluationContext;
import io.agentflow.definition.DefinitionModels.Logical;
import io.agentflow.definition.DefinitionModels.Membership;
import io.agentflow.definition.DefinitionModels.Negation;

/**
 * 与流程版本共同发布的风险规则，复用条件语义，只引用可公开读取的表单字段。
 * @author owlzhangfq@gmail.com
 */
public record ApprovalRiskPolicy(List<Rule> rules) {
    public static final int MAX_CONDITION_LENGTH = 4000;
    /** 无风险策略使用 null 表达；显式策略必须有一组有界且标识唯一的规则。 */
    public ApprovalRiskPolicy {
        if (CollectionUtils.isEmpty(rules) || rules.size() > SubmissionRisk.MAX_RULES
                || rules.stream().anyMatch(java.util.Objects::isNull)
                || rules.stream().map(Rule::id).distinct().count() != rules.size()) throw invalid("Rules must be nonempty, bounded and unique");
        rules = List.copyOf(rules);
    }

    /** 发布校验检查全部引用，逻辑短路也不能掩盖敏感或未声明字段。 */
    public List<String> validate(FormSchema schema, int languageVersion) {
        var errors = new ArrayList<String>();
        if (schema == null) return List.of("RISK_REQUIRES_FORM_SCHEMA");
        for (Rule rule : rules) {
            try {
                var ast = new ConditionParser().parse(rule.condition(), languageVersion);
                schema.validateCondition(ast);
                requirePublicFields(ast, schema);
            } catch (DomainException invalid) { errors.add(invalid.code() + ":risk:" + rule.id()); }
        }
        return List.copyOf(errors);
    }

    /** 输入已通过提交表单校验，等级只描述本次提交，不随后续规则或核定金额变化。 */
    public SubmissionRisk assess(UUID definitionId, long version, FormSchema schema, int languageVersion, Map<String, Object> values) {
        var context = new EvaluationContext(values, schema.fieldTypes());
        var matches = rules.stream().filter(rule -> new ConditionParser().parse(rule.condition(), languageVersion).evaluate(context))
                .map(rule -> new SubmissionRisk.Match(rule.id(), rule.label(), rule.level())).toList();
        return SubmissionRisk.assessed(definitionId, version, matches);
    }

    private void requirePublicFields(ConditionAst ast, FormSchema schema) {
        if (ast instanceof Logical logical) { logical.terms().forEach(term -> requirePublicFields(term, schema)); return; }
        if (ast instanceof Negation negation) { requirePublicFields(negation.term(), schema); return; }
        String key = ast instanceof Membership membership ? membership.field() : ((Comparison) ast).field();
        if (schema.fields().stream().filter(field -> field.key().equals(key)).anyMatch(FormSchema.Field::restricted)) {
            throw new DomainException("RISK_FIELD_RESTRICTED", "Risk rules cannot expose a restricted field through a public classification");
        }
    }

    /**
     * 条件与普通路由共用流程的语言版本，标签用于向审批人解释命中依据。
     * @author owlzhangfq@gmail.com
     */
    public record Rule(String id, String label, SubmissionRisk.Level level, String condition) {
        /** 不提供隐式等级或隐式命中条件，管理员必须明确配置。 */
        public Rule {
            new SubmissionRisk.Match(id, label, level);
            if (StringUtils.isBlank(condition) || condition.length() > MAX_CONDITION_LENGTH) throw invalid("Risk condition must be explicit and bounded");
        }
    }

    private static DomainException invalid(String message) { return new DomainException("INVALID_RISK_POLICY", message); }
}
