/** 来源仅是不透明标识与内容指纹，不是可执行地址。@author owlzhangfq@gmail.com */
export interface AssistReference { sourceId: string; contentDigest: string }
/** 摘要运行状态独立于审批结论。@author owlzhangfq@gmail.com */
export type AssistStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'ADOPTED' | 'DISMISSED'
/** 目录不含模型正文，选择一条后才读取详情。@author owlzhangfq@gmail.com */
export interface AssistRunItem { id: string; applicationVersion: number; roundNo: number; status: AssistStatus; version: number; createdAt: string }
/** 单申请目录页，空游标表示没有更早记录。@author owlzhangfq@gmail.com */
export interface AssistRunPage { items: AssistRunItem[]; nextCursor: string | null }
/** 首期目录按运行记录时的轮次筛选。@author owlzhangfq@gmail.com */
export interface AssistRunFilter { roundNo?: number; limit?: number; cursor?: string }
/** 原模型陈述和人工修订分别展示，不能把置信度转成审批结论。@author owlzhangfq@gmail.com */
export interface AssistRunDetail extends AssistRunItem {
  applicationId: string; requestedBy: string; startedAt: string | null; completedAt: string | null; promptVersion: string
  inputReferences: AssistReference[]; currentApplicationVersion: number; inputCurrent: boolean
  suggestion: null | { providerId: string; modelVersion: string; promptVersion: string; confidence: number; claims: Array<{ text: string; evidence: AssistReference[] }> }
  failure: null | 'MODEL_UNAVAILABLE' | 'MODEL_TIMEOUT' | 'INVALID_MODEL_OUTPUT' | 'INPUT_UNAVAILABLE'
  review: null | { reviewer: string; reviewedAt: string; acceptedText?: string | null; comment?: string | null }
}

/**
 * 目录和详情共享资源边界，切换账号、申请或失权时同时清空，迟到结果不能回填。
 * @author owlzhangfq@gmail.com
 */
export class AssistRunsQuery {
  items: AssistRunItem[] = []
  nextCursor: string | null = null
  selectedId = ''
  detail: AssistRunDetail | null = null
  loading = false
  detailLoading = false
  error = ''
  detailError = ''
  private generation = 0
  private detailGeneration = 0
  private controller: AbortController | null = null
  private detailController: AbortController | null = null
  private context = ''
  private applicationId = ''

  constructor(
    private fetchPage: (id: string, filter: AssistRunFilter, signal: AbortSignal) => Promise<AssistRunPage>,
    private fetchDetail: (id: string, runId: string, signal: AbortSignal) => Promise<AssistRunDetail>
  ) {}

  /** 不在浏览器持久存储申请摘要；清空同时中断目录和详情请求。 */
  clear() {
    this.generation++; this.controller?.abort(); this.controller = null
    this.items = []; this.nextCursor = null; this.loading = false; this.error = ''; this.context = ''; this.applicationId = ''
    this.closeDetail()
  }

  closeDetail() {
    this.detailGeneration++; this.detailController?.abort(); this.detailController = null
    this.selectedId = ''; this.detail = null; this.detailLoading = false; this.detailError = ''
  }

  /** 首次/刷新清空旧记录，翻页网络错误允许使用原游标重试。 */
  async load(scope: string, id: string, roundNo?: number, more = false) {
    const context = JSON.stringify([scope, id, roundNo])
    if (more && (this.loading || !this.nextCursor || context !== this.context)) return
    const cursor = more ? this.nextCursor : null
    if (!more) this.clear()
    if (!scope || !id) return
    this.context = context; this.applicationId = id
    const generation = ++this.generation, controller = new AbortController()
    this.controller = controller; this.loading = true; this.error = ''
    let timedOut = false
    const timer = setTimeout(() => { timedOut = true; controller.abort() }, 12_000)
    try {
      const page = await this.fetchPage(id, { roundNo, limit: 20, ...(cursor ? { cursor } : {}) }, controller.signal)
      if (generation !== this.generation) return
      if (timedOut) { this.error = '摘要记录查询超时，请重试。'; return }
      this.items = more ? [...this.items, ...page.items] : page.items; this.nextCursor = page.nextCursor
    } catch (cause) {
      if (generation !== this.generation) return
      const failure = cause as { status?: number }
      if ([401, 403, 404].includes(failure.status ?? 0)) { this.clear(); this.error = '当前无法查看这份申请的摘要记录，请刷新申请或重新登录。' }
      else this.error = timedOut ? '摘要记录查询超时，请重试。' : '摘要记录暂时无法加载，请重试。'
    } finally {
      clearTimeout(timer)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }

  /** 详情每次独立授权；任何失权会同时清空已显示的目录和正文。 */
  async select(runId: string) {
    if (!this.applicationId || !this.items.some(item => item.id === runId)) return
    this.closeDetail(); this.selectedId = runId; this.detailLoading = true
    const generation = ++this.detailGeneration, id = this.applicationId, controller = new AbortController()
    this.detailController = controller
    let timedOut = false
    const timer = setTimeout(() => { timedOut = true; controller.abort() }, 12_000)
    try {
      const detail = await this.fetchDetail(id, runId, controller.signal)
      if (generation !== this.detailGeneration) return
      if (timedOut) { this.detailError = '摘要详情查询超时，请重试。'; return }
      if (detail.id !== runId || detail.applicationId !== id) { this.detailError = '摘要归属与当前申请不符，请刷新记录。'; return }
      this.detail = detail
    } catch (cause) {
      if (generation !== this.detailGeneration) return
      const failure = cause as { status?: number }
      if ([401, 403, 404].includes(failure.status ?? 0)) { this.clear(); this.error = '当前无法查看这份申请的摘要记录，请刷新申请或重新登录。' }
      else this.detailError = timedOut ? '摘要详情查询超时，请重试。' : '摘要详情暂时无法加载，请重试。'
    } finally {
      clearTimeout(timer)
      if (generation === this.detailGeneration) { this.detailLoading = false; this.detailController = null }
    }
  }
}
