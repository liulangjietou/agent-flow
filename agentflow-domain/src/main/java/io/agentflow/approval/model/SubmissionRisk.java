package io.agentflow.approval.model;

import io.agentflow.common.DomainException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 本轮提交时的风险规则结果，只保存公开规则标签和版本来源，不保存字段取值。
 * @author owlzhangfq@gmail.com
 */
public record SubmissionRisk(Level level, UUID definitionId, long definitionVersion, List<Match> matches) {
    public static final int MAX_RULES = 10;

    /** 未评估、规则未命中和明确分级各自保留，不把缺失数据当作低风险。 */
    public SubmissionRisk {
        if (level == null || matches == null || matches.size() > MAX_RULES) throw invalid();
        matches = List.copyOf(matches);
        if (level == Level.UNASSESSED) {
            if (definitionId != null || definitionVersion != 0 || !matches.isEmpty()) throw invalid();
        } else if (definitionId == null || definitionVersion < 1 || matches.isEmpty() != (level == Level.UNMATCHED)
                || matches.stream().map(Match::ruleId).distinct().count() != matches.size()
                || matches.stream().map(Match::level).max(Comparator.comparingInt(Level::severity)).orElse(Level.UNMATCHED) != level) {
            throw invalid();
        }
    }

    /** 未配置规则和没有原始风险快照的旧轮次均没有已评估等级。 */
    public static SubmissionRisk unassessed() { return new SubmissionRisk(Level.UNASSESSED, null, 0, List.of()); }

    /** 所有命中均保留，最高明确等级决定筛选分类，规则顺序不影响结果。 */
    public static SubmissionRisk assessed(UUID definitionId, long definitionVersion, List<Match> matches) {
        Level level = matches.stream().map(Match::level).max(Comparator.comparingInt(Level::severity)).orElse(Level.UNMATCHED);
        return new SubmissionRisk(level, definitionId, definitionVersion, matches);
    }

    /**
     * 分级必须由显式规则产生，未命中只表示这组规则没有命中。
     * @author owlzhangfq@gmail.com
     */
    public enum Level {
        UNASSESSED(-1), UNMATCHED(0), LOW(1), MEDIUM(2), HIGH(3);
        private final int severity;
        Level(int severity) { this.severity = severity; }
        public int severity() { return severity; }
        public boolean classified() { return severity > 0; }
    }

    /**
     * 规则标签是流程管理员明确发布的公共说明，不包含求值时的表单数据。
     * @author owlzhangfq@gmail.com
     */
    public record Match(String ruleId, String label, Level level) {
        /** 标识和标签有界；两个未知状态不能作为命中等级。 */
        public Match {
            if (ruleId == null || !ruleId.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")
                    || StringUtils.isBlank(label) || label.length() > 120 || label.chars().anyMatch(Character::isISOControl)
                    || level == null || !level.classified()) throw invalid();
        }
    }

    private static DomainException invalid() { return new DomainException("INVALID_SUBMISSION_RISK", "Submission risk source or classification is inconsistent"); }
}
