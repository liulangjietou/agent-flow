/** 一个已发布版本的有期限直接代理；页面读取不会授予认证角色。@author owlzhangfq@gmail.com */
export const approvalProxyPath = '/organization/approval-proxies'
export const proxyStatuses = { SCHEDULED: '尚未开始', ACTIVE: '期限内', EXPIRED: '已到期', REVOKED: '已撤销' } as const
export interface ApprovalProxy {
  id: string; definitionId: string; principalId: string; substituteId: string
  startsAt: string; endsAt: string; reason: string; createdBy: string; createdAt: string; revision: number
  revocation: { actor: string; reason: string; at: string } | null
}
export interface ProxyPerson { id: string; subject: string; displayName: string; approvalEligible: boolean }
export interface ApprovalProxyView {
  proxy: ApprovalProxy; status: keyof typeof proxyStatuses; observedAt: string
  processKey: string; definitionVersion: number; definitionName: string; principal: ProxyPerson; substitute: ProxyPerson
}
export interface ApprovalProxyPage { items: ApprovalProxyView[]; nextAfterId: string | null; observedAt: string }
export interface ApprovalProxyReceipt { proxyId: string; revision: number }
export interface ApprovalProxyInput { definitionId: string; principalId: string; substituteId: string; startsAt: string; endsAt: string; reason: string }
export interface ApprovalProxyForm { definitionId: string; principalId: string; substituteId: string; startsLocal: string; endsLocal: string; reason: string }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const identifier = (value: unknown): value is string => typeof value === 'string' && uuid.test(value)
const text = (value: unknown, max = 1000): value is string => typeof value === 'string' && !!value.trim() && value.length <= max
const timestamp = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value))
export const unreadableProxy = () => ({ status: 0, code: 'RESPONSE_UNREADABLE', message: '代理响应不完整，请重新读取；操作结果未知时请先恢复上次操作。' })
function person(value: unknown): value is ProxyPerson {
  return object(value) && identifier(value.id) && text(value.subject, 128) && text(value.displayName) && typeof value.approvalEligible === 'boolean'
}
/** 当前状态来自服务端观察时刻，不能从创建回执或本机时钟推断可办理权限。 */
export function readApprovalProxy(value: unknown, expectedId?: string): ApprovalProxyView {
  if (!object(value) || !object(value.proxy)) throw unreadableProxy()
  // 服务端省略空字段；归一为 null 后，页面仍严格核对撤销状态与事实。
  const p = value.proxy, r = p.revocation ?? null
  if (!identifier(p.id) || expectedId && p.id !== expectedId || !identifier(p.definitionId) || !identifier(p.principalId)
      || !identifier(p.substituteId) || p.principalId === p.substituteId || !timestamp(p.startsAt) || !timestamp(p.endsAt)
      || !timestamp(p.createdAt) || !text(p.reason) || !text(p.createdBy, 128) || p.revision !== (r === null ? 1 : 2)
      || r !== null && (!object(r) || !text(r.actor, 128) || !text(r.reason) || !timestamp(r.at))
      || typeof value.status !== 'string' || !Object.prototype.hasOwnProperty.call(proxyStatuses, value.status)
      || (value.status === 'REVOKED') !== (r !== null) || !timestamp(value.observedAt)
      || !text(value.processKey) || !Number.isSafeInteger(value.definitionVersion) || (value.definitionVersion as number) < 1
      || !text(value.definitionName) || !person(value.principal) || !person(value.substitute)
      || value.principal.id !== p.principalId || value.substitute.id !== p.substituteId) throw unreadableProxy()
  return { ...value, proxy: { ...p, revocation: r } } as unknown as ApprovalProxyView
}
/** 每页记录共用观察时间，游标必须指向该页尾项。 */
export function readApprovalProxyPage(value: unknown): ApprovalProxyPage {
  if (!object(value) || !Array.isArray(value.items) || value.items.length > 100 || !timestamp(value.observedAt)
      || value.nextAfterId != null && !identifier(value.nextAfterId)) throw unreadableProxy()
  const items = value.items.map(item => readApprovalProxy(item)), nextAfterId = value.nextAfterId ?? null
  if (new Set(items.map(item => item.proxy.id)).size !== items.length || items.some(item => item.observedAt !== value.observedAt)
      || nextAfterId !== null && items[items.length - 1]?.proxy.id !== nextAfterId) throw unreadableProxy()
  return { items, nextAfterId: nextAfterId as string | null, observedAt: value.observedAt }
}
/** 损坏的成功响应不能解除原请求；撤销回执必须对应原编号和原修订。 */
export function validateApprovalProxyReceipt(value: unknown, path: string, body: string): ApprovalProxyReceipt {
  const revoke = /^\/organization\/approval-proxies\/([^/?]+)\/revoke$/.exec(path)
  if (!object(value) || Object.keys(value).sort().join(',') !== 'proxyId,revision' || !identifier(value.proxyId)
      || value.revision !== (revoke ? (JSON.parse(body) as { expectedRevision: number }).expectedRevision + 1 : 1)
      || revoke && value.proxyId !== decodeURIComponent(revoke[1]!)) throw unreadableProxy()
  return value as unknown as ApprovalProxyReceipt
}
export function emptyProxyForm(): ApprovalProxyForm { return { definitionId: '', principalId: '', substituteId: '', startsLocal: '', endsLocal: '', reason: '' } }
/** 本地输入采用浏览器时区；往返核对拒绝不存在的日期及夏令时跳过的时间。 */
export function proxyLocalTime(value: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value) || value.startsWith('0000')) return null
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return null
  const pad = (n: number) => String(n).padStart(2, '0')
  const roundTrip = `${String(date.getFullYear()).padStart(4, '0')}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
  return roundTrip === value ? date.toISOString() : null
}
/** 创建仅提交实际选择的版本、人员、有限期限和单行原因。 */
export function proxyCreateInput(form: ApprovalProxyForm, now = Date.now()): ApprovalProxyInput {
  if (![form.definitionId, form.principalId, form.substituteId].every(identifier)) throw new Error('请选择已发布版本、原审批人和代理人。')
  if (form.principalId === form.substituteId) throw new Error('原审批人和代理人必须是不同人员。')
  const startsAt = proxyLocalTime(form.startsLocal), endsAt = proxyLocalTime(form.endsLocal)
  if (!startsAt || !endsAt) throw new Error('请填写存在的开始和结束时间，并核对显示的时区。')
  if (Date.parse(startsAt) >= Date.parse(endsAt) || Date.parse(endsAt) <= now) throw new Error('结束时间必须晚于开始时间和当前时间。')
  const reason = form.reason.trim()
  if (!reason || form.reason.length > 1000 || /[\u0000-\u001f\u007f-\u009f]/.test(form.reason)) throw new Error('请填写不超过 1000 字的单行原因。')
  return { definitionId: form.definitionId, principalId: form.principalId, substituteId: form.substituteId, startsAt, endsAt, reason }
}
/** 当前页面内按身份保留创建草稿和最近已确认的原编号；不保存确认勾选。@author owlzhangfq@gmail.com */
export class ApprovalProxyDrafts {
  private drafts = new Map<string, { form: ApprovalProxyForm; lastProxyId: string }>()
  get(scope: string) { const saved = this.drafts.get(scope); return { form: { ...(saved?.form ?? emptyProxyForm()) }, lastProxyId: saved?.lastProxyId ?? '' } }
  put(scope: string, form: ApprovalProxyForm) { if (scope) this.drafts.set(scope, { ...this.get(scope), form: { ...form } }) }
  forget(scope: string) { this.drafts.delete(scope) }
  hasDrafts() { return [...this.drafts.values()].some(value => Object.values(value.form).some(Boolean)) }
  /** 只清除与原写入完全匹配的草稿；回执编号保留供详情读取失败后恢复。 */
  acknowledge(scope: string, path: string, body: string, receipt: ApprovalProxyReceipt) {
    const saved = this.get(scope)
    if (path === approvalProxyPath) {
      try { if (JSON.stringify(proxyCreateInput(saved.form, 0)) === body) saved.form = emptyProxyForm() } catch { /* 发送后另有修改时保留本地草稿。 */ }
    }
    saved.lastProxyId = receipt.proxyId
    this.drafts.set(scope, saved)
  }
}
export const approvalProxyDrafts = new ApprovalProxyDrafts()
