import { ownValue, type FormField } from './formSchema.js'

/** 设计器使用精确版本文本，避免浏览器浮点数改变已安装的 long 版本。@author owlzhangfq@gmail.com */
export interface ServiceTaskParameter { name: string; type: 'TEXT' | 'NUMBER' | 'BOOLEAN' | 'DATE'; required: boolean; sensitive: boolean }
/** @author owlzhangfq@gmail.com */
export interface ServiceTaskOption { key: string; version: string; name: string; contractDigest: string; parameters: ServiceTaskParameter[]; enabled: boolean }
/** @author owlzhangfq@gmail.com */
export interface ServiceTaskDirectory { items: ServiceTaskOption[]; nextAfterKey?: string | null }
/** @author owlzhangfq@gmail.com */
export interface ServiceTaskVersions { items: ServiceTaskOption[]; nextBeforeVersion?: string | null }
/** 原引用、摘要和映射整体保存，浏览目录不产生执行请求。@author owlzhangfq@gmail.com */
export interface ServiceTaskBinding { key?: string; version?: string; digest?: string; inputs: Record<string, string> }
const INPUT_PREFIX = 'serviceInput.'
const reference = ['serviceOperationKey', 'serviceOperationVersion', 'serviceContractDigest']
export const serviceTaskKey = (value: unknown): value is string => typeof value === 'string' && /^[a-z][a-z0-9._-]{0,63}$/.test(value)
export const serviceTaskVersion = (value: unknown): value is string => typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n
const digest = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
const fieldKey = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(value)
function check(valid: unknown): asserts valid { if (!valid) throw new Error('服务任务目录未能核实，请刷新后重试。') }

/** 原文即使缺项也保留，校验或用户明确修改后才替换。 */
export function readServiceTaskBinding(properties: Record<string, string>): ServiceTaskBinding | undefined {
  const inputs = Object.fromEntries(Object.entries(properties).filter(([key]) => key.startsWith(INPUT_PREFIX)).map(([key, value]) => [key.slice(INPUT_PREFIX.length), value]))
  if (!reference.some(key => properties[key] !== undefined) && !Object.keys(inputs).length) return
  return { key: properties.serviceOperationKey, version: properties.serviceOperationVersion, digest: properties.serviceContractDigest, inputs }
}
/** 切换或清除引用同时清除原映射；未知属性仍交由服务器拒绝。 */
export function writeServiceTaskBinding(properties: Record<string, string>, binding?: ServiceTaskBinding): Record<string, string> {
  const values = Object.fromEntries(Object.entries(properties).filter(([key]) => !reference.includes(key) && !key.startsWith(INPUT_PREFIX)))
  if (binding?.key !== undefined) values.serviceOperationKey = binding.key
  if (binding?.version !== undefined) values.serviceOperationVersion = binding.version
  if (binding?.digest !== undefined) values.serviceContractDigest = binding.digest
  for (const [target, source] of Object.entries(binding?.inputs ?? {})) values[INPUT_PREFIX + target] = source
  return values
}
export function readServiceTaskOption(value: ServiceTaskOption, key?: string, version?: string) {
  check(value && serviceTaskKey(value.key) && serviceTaskVersion(value.version) && digest(value.contractDigest)
    && typeof value.name === 'string' && value.name.trim() && value.name.length <= 200 && typeof value.enabled === 'boolean'
    && (key === undefined || value.key === key) && (version === undefined || value.version === version)
    && Array.isArray(value.parameters) && value.parameters.length <= 16)
  value.parameters.forEach(parameter => check(parameter && fieldKey(parameter.name) && ['TEXT', 'NUMBER', 'BOOLEAN', 'DATE'].includes(parameter.type)
    && typeof parameter.required === 'boolean' && typeof parameter.sensitive === 'boolean'))
  check(new Set(value.parameters.map(parameter => parameter.name)).size === value.parameters.length)
  return value
}
export function readServiceTaskDirectory(value: ServiceTaskDirectory, after?: string) {
  check(value && Array.isArray(value.items) && value.items.length <= 100)
  value.items.forEach((item, index) => { readServiceTaskOption(item); check(item.key > (index ? value.items[index - 1]!.key : after ?? '')) })
  check(value.nextAfterKey == null || value.items.length && value.nextAfterKey === value.items[value.items.length - 1]!.key)
  return value
}
export function readServiceTaskVersions(value: ServiceTaskVersions, key: string, before?: string) {
  check(value && Array.isArray(value.items) && value.items.length <= 100 && (before === undefined || serviceTaskVersion(before)))
  value.items.forEach((item, index) => {
    readServiceTaskOption(item, key)
    const previous = index ? value.items[index - 1]!.version : before
    check(previous === undefined || BigInt(item.version) < BigInt(previous))
  })
  check(value.nextBeforeVersion == null || value.items.length && value.nextBeforeVersion === value.items[value.items.length - 1]!.version)
  return value
}

/** 编辑提示与后端映射规则一致，最终权限和实际输入仍由服务端检查。 */
export function serviceTaskInputIssue(source: FormField | undefined, parameter: ServiceTaskParameter, nodeId: string): string {
  if (!source) return '请选择表单中存在的字段'
  if (!(parameter.type === 'TEXT' ? ['TEXT', 'TEXTAREA', 'SELECT'].includes(source.type) : parameter.type === source.type)) return '字段类型与参数不同'
  const visibility = ownValue(source.nodeAccess ?? {}, nodeId) ?? (source.sensitive ? 'MASKED' : 'READ_ONLY')
  if (visibility !== 'READ_ONLY') return '此节点须具有字段的只读权限，隐藏或脱敏字段不能外发'
  if ((source.sensitive || Object.values(source.nodeAccess ?? {}).some(value => value !== 'READ_ONLY')) && !parameter.sensitive) return '此参数未声明敏感性，不能接收受限制字段'
  return ''
}

/** 当前账号、定义或引用变化时取消旧读取，迟到响应不能重建已清除的目录。@author owlzhangfq@gmail.com */
export class ServiceTaskRead<T> {
  constructor(private readonly failureMessage = '服务任务目录暂时不可访问，请刷新后核对原引用。') {}
  value: T | null = null
  loading = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null
  clear() { this.generation++; this.controller?.abort(); this.controller = null; this.value = null; this.loading = false; this.error = '' }
  async load(scope: string, fetch: (signal: AbortSignal) => Promise<T>) {
    this.clear(); if (!scope) return
    const generation = this.generation, controller = new AbortController(); this.controller = controller; this.loading = true
    let timer: ReturnType<typeof setTimeout> | undefined
    try {
      const result = await Promise.race([fetch(controller.signal), new Promise<never>((_, reject) => {
        timer = setTimeout(() => { controller.abort(); reject(new Error('服务任务目录读取超时，请重试。')) }, 12_000)
      })])
      if (generation === this.generation) { this.value = result; return result }
    } catch { if (generation === this.generation) this.error = this.failureMessage }
    finally { clearTimeout(timer); if (generation === this.generation) { this.loading = false; this.controller = null } }
  }
}
