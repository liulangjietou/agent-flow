/** 可导航的关联必须已经通过目标申请授权，内容仍由原详情入口投影。@author owlzhangfq@gmail.com */
export interface RelatedRound {
  applicationId: string; businessNo: string; processKey: string; definitionVersion: number
  roundNo: number; title: string; status: string
}
export interface SubprocessRelation {
  id: string; nodeId: string; nodeName: string; createdAt: string; target: RelatedRound | null
}
export interface SubprocessRelationsPage {
  applicationId: string; roundNo: number; observedAt: string; childApplication: boolean
  parent: RelatedRound | null; children: SubprocessRelation[]; nextAfterId: string | null
}
type Reader = (id: string, round: number, afterId: string | undefined, signal: AbortSignal) => Promise<SubprocessRelationsPage>
const READ_TIMEOUT_MS = 12_000

/** 按精确轮次逐页读取；刷新失败清除原链接，账号或申请切换隔离迟到结果。 */
export class SubprocessRelationsQuery {
  value: SubprocessRelationsPage | null = null
  loading = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null
  private source: { id: string; round: number } | null = null
  constructor(private readonly read: Reader) {}

  /** 关闭或切换上下文后，不允许旧页继续触发导航。 */
  clear() {
    this.generation++; this.controller?.abort(); this.controller = null
    this.value = null; this.source = null; this.loading = false; this.error = ''
  }

  /** 没有已提交轮次时不查询，更不能将空状态解释为读取成功。 */
  async load(scope: string, id: string, round: number) {
    this.clear()
    if (!scope || !id || !Number.isInteger(round) || round < 1) return
    this.source = { id, round }
    await this.fetchPage()
  }

  /** 游标只沿当前申请和轮次前进，分页失败也清除旧的授权链接。 */
  async more() {
    if (!this.source || this.loading || !this.value?.nextAfterId) return
    await this.fetchPage(this.value.nextAfterId)
  }

  private async fetchPage(afterId?: string) {
    const source = this.source!, generation = this.generation, controller = new AbortController()
    this.controller = controller; this.loading = true; this.error = ''
    let timeout: ReturnType<typeof setTimeout> | undefined
    try {
      const value = await Promise.race([this.read(source.id, source.round, afterId, controller.signal), new Promise<never>((_, reject) => {
        timeout = setTimeout(() => { controller.abort(); reject(new Error('父子流程读取超时，请重试。')) }, READ_TIMEOUT_MS)
      })])
      if (generation !== this.generation) return
      const previous = afterId ? this.value!.children : []
      const ids = new Set(previous.map(child => child.id))
      if (!value || value.applicationId !== source.id || value.roundNo !== source.round || !Array.isArray(value.children)
        || value.children.length > 30 || typeof value.childApplication !== 'boolean'
        || value.nextAfterId !== null && (value.nextAfterId === afterId || value.nextAfterId !== value.children[value.children.length - 1]?.id)) {
        throw new Error('父子流程记录与当前轮次不一致，请刷新。')
      }
      for (const child of value.children) {
        if (!child.id || ids.has(child.id)) throw new Error('父子调用记录重复，请刷新。')
        ids.add(child.id)
      }
      this.value = { ...value, children: [...previous, ...value.children] }
    } catch (cause) {
      if (generation !== this.generation) return
      this.value = null
      const failure = cause as { status?: number; message?: string }
      this.error = failure.status === 401 ? '登录已失效，请重新登录。'
        : failure.status === 403 || failure.status === 404 ? '本轮记录不可用或无权查看，请刷新后核对。'
        : failure.message ?? '父子流程读取失败，请重试。'
    } finally {
      clearTimeout(timeout)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
