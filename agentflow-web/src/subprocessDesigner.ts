import type { FormField } from './formSchema'
import { ownValue } from './formSchema.js'

/** 固定引用与显式字段映射，两种设计视图使用同一份配置。@author owlzhangfq@gmail.com */
export interface SubprocessBinding { key?: string; version?: string; inputs: Record<string, string> }
const INPUT_PREFIX = 'subprocessInput.'
const RESERVED_FIELDS = new Set(['constructor', 'prototype', 'tenantId', 'applicationId', 'businessNo', 'roundNo', 'formData', 'formFieldTypes', 'lastAction'])

/** 保留缺项或失效配置供用户修正，不查询目录或推断最新版本。 */
export function readSubprocessBinding(properties: Record<string, string>): SubprocessBinding | undefined {
  const inputs = Object.fromEntries(Object.entries(properties).filter(([key]) => key.startsWith(INPUT_PREFIX)).map(([key, value]) => [key.slice(INPUT_PREFIX.length), value]))
  if (properties.subprocessKey === undefined && properties.subprocessVersion === undefined && !Object.keys(inputs).length) return
  return { ...(properties.subprocessKey !== undefined ? { key: properties.subprocessKey } : {}),
    ...(properties.subprocessVersion !== undefined ? { version: properties.subprocessVersion } : {}), inputs }
}

/** 整体替换调用配置；清除引用同时清除旧输入，其他节点属性保持。 */
export function writeSubprocessBinding(properties: Record<string, string>, binding?: SubprocessBinding): Record<string, string> {
  const values = Object.fromEntries(Object.entries(properties).filter(([key]) => key !== 'subprocessKey' && key !== 'subprocessVersion' && !key.startsWith(INPUT_PREFIX)))
  if (binding?.key !== undefined) values.subprocessKey = binding.key
  if (binding?.version !== undefined) values.subprocessVersion = binding.version
  for (const [target, source] of Object.entries(binding?.inputs ?? {})) values[INPUT_PREFIX + target] = source
  return values
}

/** 文件输入只接受声明字段标识，不允许路径、表达式或平台身份字段。 */
export const subprocessFieldKey = (value: string) => /^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(value) && !RESERVED_FIELDS.has(value)

/** 非规范版本不发送目录请求，原文仍在编辑器中保留。 */
export function subprocessVersion(binding: SubprocessBinding): number | undefined {
  if (!binding.key || binding.key !== binding.key.trim() || binding.key.length > 128 || /[\u0000-\u001f\u007f-\u009f]|[$#]\{/.test(binding.key)
    || !binding.version || !/^[1-9][0-9]{0,9}$/.test(binding.version) || Number(binding.version) > 2_147_483_647) return
  return Number(binding.version)
}

/** 编辑提示复用后台的类型、可读性和敏感性语义；发布与激活仍由后台最终校验。 */
export function subprocessInputIssue(source: FormField | undefined, target: FormField | undefined, nodeId: string): string {
  if (!source) return '父表单中不存在此字段'
  if (!target) return '此子版本中不存在目标字段'
  if (source.type !== target.type) return '父子字段类型不同'
  const visibility = ownValue(source.nodeAccess ?? {}, nodeId) ?? (source.sensitive ? 'MASKED' : 'READ_ONLY')
  if (visibility !== 'READ_ONLY') return '调用节点须具有此字段的只读权限'
  if ((source.sensitive || Object.values(source.nodeAccess ?? {}).some(value => value !== 'READ_ONLY')) && !target.sensitive) return '子字段须保留敏感标记'
  if (source.type === 'TABLE') {
    for (const column of target.columns ?? []) {
      const from = source.columns?.find(field => field.key === column.key)
      if (!from && !column.required) continue
      const issue = subprocessInputIssue(from, column, nodeId)
      if (issue) return `明细列 ${column.label}：${issue}`
    }
  }
  return ''
}
