import { mappingFingerprint, type MappingDefinition, type MappingDraft, type MappingDraftInput } from './accountMappings.js'

export interface MappingEdit { key: string; baseline: MappingDraft | null; definition: MappingDefinition; comment: string }
export const copyMapping = <T>(value: T): T => JSON.parse(JSON.stringify(value))
export function mappingInput(edit: MappingEdit): MappingDraftInput { return { expectedRevision: edit.baseline?.revision ?? 0, definition: copyMapping(edit.definition), comment: edit.comment.trim() } }
export function mappingChanged(edit: MappingEdit) { return !edit.baseline || JSON.stringify(edit.definition) !== JSON.stringify(edit.baseline.definition) || !!edit.comment }
export function sameMappingDraft(edit: MappingEdit, latest: MappingDraft): boolean {
  if (edit.key !== latest.key || edit.baseline?.id !== latest.id || edit.baseline.revision !== latest.revision || edit.comment) return false
  try { return mappingFingerprint(edit.definition) === mappingFingerprint(latest.definition) } catch { return false }
}
/** 企业科目正文只存于内存，并按登录主体与原配置隔离。 */
export class MappingDrafts {
  private values = new Map<string, Map<string, MappingEdit>>()
  get(scope: string, key: string) { const v = this.values.get(scope)?.get(key); return v ? copyMapping(v) : null }
  put(scope: string, edit: MappingEdit) {
    if (!scope) return
    if (!this.values.has(scope)) this.values.set(scope, new Map())
    const slot = edit.baseline ? edit.key : ''
    if (mappingChanged(edit)) this.values.get(scope)!.set(slot, copyMapping(edit)); else this.discard(scope, slot)
  }
  discard(scope: string, key: string) { this.values.get(scope)?.delete(key) }
  hasDrafts() { return [...this.values.values()].some(v => v.size > 0) }
  /** 只清理与原发送正文一致的编辑，迟到确认不能删除后续修改。 */
  acknowledge(scope: string, path: string, body: string) {
    const match = /^\/admin\/account-mappings\/([^/?]+)\/draft$/.exec(path)
    if (!match) return
    const key = decodeURIComponent(match[1]!), values = this.values.get(scope), slot = values?.has(key) ? key : '', edit = values?.get(slot)
    if (edit?.key === key && JSON.stringify(mappingInput(edit)) === body) this.discard(scope, slot)
  }
}
export const mappingDrafts = new MappingDrafts()
