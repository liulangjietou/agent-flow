import type { Graph, GraphEdge, GraphNode } from './api'

/** 快速视图仅投影同一张图，不另存流程语义。@author owlzhangfq@gmail.com */
export interface QuickSequence { steps: QuickStep[]; tailEdge: string | null }
/** 汇合点属于后续序列，分支只拥有汇合之前的独立节点。@author owlzhangfq@gmail.com */
export interface QuickStep { nodeId: string; beforeEdge: string | null; branches?: QuickBranch[]; joinId?: string | null }
/** 默认分支最后展示，条件分支保留引擎判断顺序。@author owlzhangfq@gmail.com */
export interface QuickBranch { edgeId: string; sequence: QuickSequence }
/** 不能无损表达的图留给高级画布修复，禁止自动丢弃节点。@author owlzhangfq@gmail.com */
export interface QuickProjection { sequence: QuickSequence | null; reason: string }
/** 编辑命令只作用于未发布草稿；权限和会话锁由调用层控制。@author owlzhangfq@gmail.com */
export type QuickCommand =
  | { kind: 'insert'; edgeId?: string; beforeNodeId?: string; type: 'USER_TASK' | 'COPY' | 'TIMER_WAIT' | 'EVENT_WAIT' | 'SERVICE_TASK' | 'SUB_PROCESS' | 'EXCLUSIVE_GATEWAY' | 'PARALLEL_GATEWAY' }
  | { kind: 'removeTask'; nodeId: string }
  | { kind: 'addBranch'; nodeId: string }
  | { kind: 'removeBranch'; nodeId: string; edgeId: string }
  | { kind: 'moveBranch'; nodeId: string; edgeId: string; direction: -1 | 1 }
  | { kind: 'removeGateway'; nodeId: string }
  | { kind: 'swapTasks'; firstId: string; secondId: string }

const EXIT = Symbol('exit')
const supported = new Set(['START', 'END', 'USER_TASK', 'COPY', 'TIMER_WAIT', 'EVENT_WAIT', 'SERVICE_TASK', 'SUB_PROCESS', 'EXCLUSIVE_GATEWAY', 'PARALLEL_GATEWAY'])
const isGateway = (node: GraphNode) => node.type === 'EXCLUSIVE_GATEWAY' || node.type === 'PARALLEL_GATEWAY'

/** 依据最近共同后继将无环图投影成步骤与分支，发现交叉共享即退出而非重复显示。 */
export function projectQuickGraph(graph: Graph): QuickProjection {
  try {
    const byId = new Map(graph.nodes.map(node => [node.id, node]))
    if (byId.size !== graph.nodes.length || new Set(graph.edges.map(edge => edge.id)).size !== graph.edges.length) throw new Error('图中存在重复标识，请在高级画布修复。')
    if (graph.nodes.some(node => !supported.has(node.type))) throw new Error('此图包含快速模式尚不支持的节点，请使用高级画布。')
    const starts = graph.nodes.filter(node => node.type === 'START')
    if (starts.length !== 1) throw new Error('流程需要一个开始节点，请在高级画布修复。')
    const outgoing = new Map(graph.nodes.map(node => [node.id, [] as GraphEdge[]]))
    const incoming = new Map(graph.nodes.map(node => [node.id, [] as GraphEdge[]]))
    for (const edge of graph.edges) {
      if (!byId.has(edge.source) || !byId.has(edge.target)) throw new Error('存在未连接到有效节点的连线，请在高级画布修复。')
      outgoing.get(edge.source)!.push(edge); incoming.get(edge.target)!.push(edge)
      if (byId.get(edge.source)!.type !== 'EXCLUSIVE_GATEWAY' && (edge.condition.trim() || edge.defaultBranch)) throw new Error('普通步骤的连线上存在条件，请在高级画布编辑以保留其含义。')
    }
    if (incoming.get(starts[0]!.id)!.length) throw new Error('开始节点不能有前驱，请在高级画布修复。')
    for (const node of graph.nodes) {
      const count = outgoing.get(node.id)!.length
      if (node.type === 'END' ? count !== 0 : isGateway(node) ? count < 1 || count === 1 && incoming.get(node.id)!.length < 2 : count !== 1) throw new Error('部分节点尚未连接完整，请先在高级画布补齐。')
      if (node.type === 'EXCLUSIVE_GATEWAY' && count === 1 && outgoing.get(node.id)!.some(edge => edge.condition.trim() || edge.defaultBranch)) throw new Error('条件汇合的出线不能设置判断或默认分支，请在高级画布修复。')
    }
    const degree = new Map(graph.nodes.map(node => [node.id, incoming.get(node.id)!.length]))
    const order = graph.nodes.filter(node => degree.get(node.id) === 0).map(node => node.id)
    for (let index = 0; index < order.length; index++) for (const edge of outgoing.get(order[index]!)!) {
      degree.set(edge.target, degree.get(edge.target)! - 1)
      if (degree.get(edge.target) === 0) order.push(edge.target)
    }
    if (order.length !== graph.nodes.length) throw new Error('图中存在回环，请在高级画布修复。')
    const post = new Map<string | symbol, Set<string | symbol>>([[EXIT, new Set([EXIT])]])
    for (const id of [...order].reverse()) {
      const successors: (string | symbol)[] = outgoing.get(id)!.map(edge => edge.target)
      if (!successors.length) successors.push(EXIT)
      const common = new Set(post.get(successors[0]!)!)
      for (const next of successors.slice(1)) for (const candidate of common) if (!post.get(next)!.has(candidate)) common.delete(candidate)
      common.add(id); post.set(id, common)
    }
    const owned = new Set<string>()
    const parallelJoins = new Set<string>()
    function walk(first: string | null, stop: string | null, entry: string | null, depth: number): QuickSequence {
      if (depth > 80) throw new Error('分支嵌套较深，请在高级画布查看。')
      const steps: QuickStep[] = []
      let id = first, before = entry
      while (id !== stop && id !== null) {
        if (owned.has(id)) throw new Error('分支之间存在交叉或提前共享步骤，请使用高级画布保持原有连线。')
        owned.add(id)
        const node = byId.get(id)!, exits = outgoing.get(id)!
        const step: QuickStep = { nodeId: id, beforeEdge: before }
        steps.push(step)
        if (isGateway(node) && exits.length > 1) {
          // 严格后继中最早的共同节点即汇合点；各自结束的分支没有真实汇合节点。
          const join = order.find(candidate => candidate !== node.id && post.get(node.id)!.has(candidate)) ?? null
          if (node.type === 'PARALLEL_GATEWAY') {
            if (!join || byId.get(join)!.type !== 'PARALLEL_GATEWAY' || parallelJoins.has(join)) throw new Error('并行分支必须成对汇合，不能提前结束或交叉连接。')
            parallelJoins.add(join)
          }
          step.joinId = join
          const branches = [...exits.filter(edge => !edge.defaultBranch), ...exits.filter(edge => edge.defaultBranch)]
          step.branches = branches.map(edge => ({ edgeId: edge.id, sequence: walk(edge.target, join, edge.id, depth + 1) }))
          if (node.type === 'PARALLEL_GATEWAY') {
            const arrivals = new Set(step.branches.map(branch => branch.sequence.tailEdge))
            if (arrivals.has(null) || arrivals.size !== branches.length || incoming.get(join!)!.length !== arrivals.size
                || incoming.get(join!)!.some(edge => !arrivals.has(edge.id))) throw new Error('每条并行分支必须以一条路径进入汇合点；条件路径请先汇合。')
          }
          id = join; before = null
        } else if (node.type === 'END') { id = null; before = null }
        else { before = exits[0]!.id; id = exits[0]!.target }
      }
      return { steps, tailEdge: before }
    }
    const sequence = walk(starts[0]!.id, null, null, 0)
    if (owned.size !== graph.nodes.length) throw new Error('图中存在不可达节点，请在高级画布修复。')
    if (graph.nodes.some(node => node.type === 'PARALLEL_GATEWAY' && incoming.get(node.id)!.length > 1 && !parallelJoins.has(node.id))) throw new Error('并行汇合缺少对应的拆分，请在高级画布修复。')
    return { sequence, reason: '' }
  } catch (cause) { return { sequence: null, reason: (cause as Error).message } }
}

/** 查找展示中的真实节点，避免从任意路径猜测其分支边界。 */
export function quickStep(sequence: QuickSequence, id: string): QuickStep | undefined {
  for (const step of sequence.steps) {
    if (step.nodeId === id) return step
    for (const branch of step.branches ?? []) { const found = quickStep(branch.sequence, id); if (found) return found }
  }
}
/** 分支拥有的全部节点，仅用于明确范围的删除。 */
export function quickNodeIds(sequence: QuickSequence): string[] {
  return sequence.steps.flatMap(step => [step.nodeId, ...(step.branches ?? []).flatMap(branch => quickNodeIds(branch.sequence))])
}

/** 在图副本执行一次可撤销编辑，保留存量标识、属性、条件及未涉及的顺序。 */
export function editQuickGraph(source: Graph, command: QuickCommand, newId: () => string = () => crypto.randomUUID()): Graph {
  const projection = projectQuickGraph(source)
  if (!projection.sequence) throw new Error(projection.reason)
  const graph: Graph = { ...source, nodes: source.nodes.map(node => ({ ...node, properties: { ...node.properties } })), edges: source.edges.map(edge => ({ ...edge })) }
  const find = (id: string) => {
    const node = graph.nodes.find(node => node.id === id)
    if (!node) throw new Error('步骤已变化，请重新选择。')
    return node
  }
  const edge = (source: string, target: string): GraphEdge => ({ id: `edge-${newId()}`, source, target, condition: '', defaultBranch: false })
  const node = (type: string, name: string): GraphNode => {
    const value = { id: `node-${newId()}`, name, type, properties: {} }
    graph.nodes.push(value); return value
  }
  const remove = (ids: string[]) => {
    const removed = new Set(ids)
    graph.nodes = graph.nodes.filter(item => !removed.has(item.id))
    graph.edges = graph.edges.filter(item => !removed.has(item.source) && !removed.has(item.target))
  }
  if (command.kind === 'insert') {
    if (Boolean(command.edgeId) === Boolean(command.beforeNodeId)) throw new Error('请选择唯一的插入位置。')
    const connections = command.edgeId ? graph.edges.filter(item => item.id === command.edgeId) : graph.edges.filter(item => item.target === command.beforeNodeId)
    if (!connections.length || new Set(connections.map(item => item.target)).size !== 1) throw new Error('插入位置已变化，请重新选择。')
    const target = connections[0]!.target
    if (command.beforeNodeId && isGateway(find(target)) && connections.length > 1) throw new Error('请在汇合之后或某条分支内插入步骤，不能绕过汇合。')
    const added = node(command.type, command.type === 'SERVICE_TASK' ? '服务任务' : command.type === 'SUB_PROCESS' ? '子流程' : command.type === 'EVENT_WAIT' ? '事件等待' : command.type === 'TIMER_WAIT' ? '定时等待' : command.type === 'COPY' ? '抄送' : command.type === 'USER_TASK' ? '审批步骤' : command.type === 'PARALLEL_GATEWAY' ? '并行审批' : '条件分支')
    // 原审批步骤可能同时汇合互斥路径；新并行网关只能接收汇合后的一个令牌。
    const entrance = command.type === 'PARALLEL_GATEWAY' && connections.length > 1
      ? node('EXCLUSIVE_GATEWAY', '条件汇合').id : added.id
    connections.forEach(item => { item.target = entrance })
    if (entrance !== added.id) graph.edges.push(edge(entrance, added.id))
    if (['USER_TASK', 'COPY', 'TIMER_WAIT', 'EVENT_WAIT', 'SERVICE_TASK', 'SUB_PROCESS'].includes(command.type)) graph.edges.push(edge(added.id, target))
    else if (command.type === 'PARALLEL_GATEWAY') {
      const join = node('PARALLEL_GATEWAY', '全部汇合')
      for (const name of ['分支审批 1', '分支审批 2']) {
        const review = node('USER_TASK', name)
        graph.edges.push(edge(added.id, review.id), edge(review.id, join.id))
      }
      graph.edges.push(edge(join.id, target))
    } else {
      const review = node('USER_TASK', '分支审批')
      // 条件分支不能将互斥路径直接接入并行网关，否则后者永远等不到全部入口。
      const merge = find(target).type === 'PARALLEL_GATEWAY' ? node('EXCLUSIVE_GATEWAY', '条件汇合').id : target
      graph.edges.push(edge(added.id, review.id), { ...edge(added.id, merge), defaultBranch: true }, edge(review.id, merge))
      if (merge !== target) graph.edges.push(edge(merge, target))
    }
  } else if (command.kind === 'removeTask') {
    if (!['USER_TASK', 'COPY', 'TIMER_WAIT', 'EVENT_WAIT', 'SERVICE_TASK', 'SUB_PROCESS'].includes(find(command.nodeId).type)) throw new Error('只能直接删除审批、抄送、等待、服务或子流程步骤。')
    const next = graph.edges.find(item => item.source === command.nodeId)!
    const entrances = graph.edges.filter(item => item.target === command.nodeId)
    // 删除审批行为仍须保留其互斥合流，不能让未选路径成为并行网关的独立入口。
    const target = entrances.length > 1 && find(next.target).type === 'PARALLEL_GATEWAY'
      ? node('EXCLUSIVE_GATEWAY', '条件汇合').id : next.target
    entrances.forEach(item => { item.target = target })
    if (target !== next.target) next.source = target
    remove([command.nodeId])
  } else if (command.kind === 'swapTasks') {
    if (!['USER_TASK', 'COPY', 'TIMER_WAIT', 'EVENT_WAIT', 'SERVICE_TASK', 'SUB_PROCESS'].includes(find(command.firstId).type) || !['USER_TASK', 'COPY', 'TIMER_WAIT', 'EVENT_WAIT', 'SERVICE_TASK', 'SUB_PROCESS'].includes(find(command.secondId).type)) throw new Error('只能移动相邻审批、抄送、等待、服务或子流程步骤。')
    const between = graph.edges.find(item => item.source === command.firstId && item.target === command.secondId)
    const next = graph.edges.find(item => item.source === command.secondId)
    if (!between || !next || graph.edges.filter(item => item.target === command.secondId).length !== 1) throw new Error('存在共享步骤，不能直接移动。')
    graph.edges.filter(item => item.target === command.firstId).forEach(item => { item.target = command.secondId })
    between.source = command.secondId; between.target = command.firstId; next.source = command.firstId
  } else {
    if (!isGateway(find(command.nodeId))) throw new Error('请选择分支块。')
    const step = quickStep(projection.sequence, command.nodeId)!
    if (!step.branches) throw new Error('汇合节点不能单独增删分支，请选择对应的拆分节点。')
    const branches = graph.edges.filter(item => item.source === command.nodeId)
    if (command.kind === 'addBranch') {
      const review = node('USER_TASK', '分支审批')
      const join = step.joinId ?? node('END', '结束').id
      const newEdge = edge(command.nodeId, review.id)
      // 新条件放在其他条件之后，默认分支仍按引擎兜底语义处理。
      const defaultIndex = graph.edges.findIndex(item => item.source === command.nodeId && item.defaultBranch)
      graph.edges.splice(defaultIndex < 0 ? graph.edges.length : defaultIndex, 0, newEdge)
      graph.edges.push(edge(review.id, join))
    } else if (command.kind === 'removeBranch') {
      const branch = step.branches!.find(item => item.edgeId === command.edgeId)
      if (!branch || branches.length <= 2) throw new Error('分支块至少保留两条路径；可删除整个分支块。')
      if (branches.find(item => item.id === command.edgeId)!.defaultBranch) throw new Error('请先指定另一条默认分支。')
      remove(quickNodeIds(branch.sequence))
      graph.edges = graph.edges.filter(item => item.id !== command.edgeId)
    } else if (command.kind === 'moveBranch') {
      const conditions = branches.filter(item => !item.defaultBranch)
      const index = conditions.findIndex(item => item.id === command.edgeId), target = index + command.direction
      if (index < 0 || target < 0 || target >= conditions.length) throw new Error('此分支不能继续移动。')
      const first = graph.edges.indexOf(conditions[index]!), second = graph.edges.indexOf(conditions[target]!)
      ;[graph.edges[first], graph.edges[second]] = [graph.edges[second]!, graph.edges[first]!]
    } else {
      const join = step.joinId ?? node('END', '结束').id
      const branchIds = step.branches.flatMap(branch => quickNodeIds(branch.sequence))
      const blockIds = new Set([command.nodeId, ...branchIds])
      const joinExits = graph.edges.filter(item => item.source === join)
      const ownsJoin = isGateway(find(join)) && joinExits.length === 1
        && graph.edges.filter(item => item.target === join).every(item => blockIds.has(item.source))
      const resume = ownsJoin ? joinExits[0]!.target : join
      const entrances = graph.edges.filter(item => item.target === command.nodeId)
      if (entrances.length > 1) {
        // 一个网关同时汇合和拆分时，删除后半块仍保留前半块的汇合责任。
        graph.edges = graph.edges.filter(item => item.source !== command.nodeId)
        graph.edges.push(edge(command.nodeId, resume))
      } else entrances.forEach(item => { item.target = resume })
      remove([...branchIds, ...(entrances.length > 1 ? [] : [command.nodeId]), ...(ownsJoin ? [join] : [])])
    }
  }
  const result = projectQuickGraph(graph)
  if (!result.sequence) throw new Error(result.reason)
  return graph
}
