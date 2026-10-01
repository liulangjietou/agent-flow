import type { RiskPolicy, SubmissionRiskLevel } from './api'
import type { FormField, FormSchema } from './formSchema'

export const MAX_RISK_RULES = 10
export const riskLabels: Record<SubmissionRiskLevel, string> = {
  UNASSESSED: '未评估', UNMATCHED: '规则未命中', LOW: '低风险', MEDIUM: '中风险', HIGH: '高风险'
}

/** 风险标签公开展示，配置入口排除任何受限字段及含受限列的明细。 */
export function riskFieldRestricted(field: FormField): boolean {
  return field.sensitive === true || Object.values(field.nodeAccess ?? {}).some(access => access !== 'READ_ONLY')
    || (field.columns ?? []).some(riskFieldRestricted)
}

/** 字段权限改变后只收窄可选项，已有规则文本仍保留，交由发布校验指出问题。 */
export function riskConditionSchema(schema: FormSchema | null): FormSchema | null {
  return schema ? { ...schema, fields: schema.fields.filter(field => !riskFieldRestricted(field)) } : null
}

/** 编辑、撤销和读取使用独立副本，避免修改已保存定义。 */
export function copyRiskPolicy(policy: RiskPolicy | null | undefined): RiskPolicy | null {
  return policy ? { rules: policy.rules.map(rule => ({ ...rule })) } : null
}
