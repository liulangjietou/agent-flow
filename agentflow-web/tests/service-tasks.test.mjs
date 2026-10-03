import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const service = await import(process.env.AGENTFLOW_TEST_SERVICE_TASKS)
const { default: Selector } = await import(process.env.AGENTFLOW_TEST_SERVICE_TASK_PANEL)
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { loadDesignerNodes, serializeDesignerNodes } = await import(process.env.AGENTFLOW_TEST_DESIGNER_GRAPH)
const { editQuickGraph, projectQuickGraph } = await import(process.env.AGENTFLOW_TEST_QUICK)
const { parsePortableTemplate, serializePortableTemplate } = await import(process.env.AGENTFLOW_TEST_PORTABLE)
const originalApi = { ...api }
const MAX_VERSION = '9223372036854775807'
const option = (version = '1', extra = {}) => ({ key: 'receipt.register', version, name: '登记凭据', contractDigest: 'a'.repeat(64), parameters: [{ name: 'memo', type: 'TEXT', required: true, sensitive: true }], enabled: true, ...extra })
const binding = () => ({ key: 'receipt.register', version: '1', digest: 'a'.repeat(64), inputs: { memo: 'reason' } })
const field = (extra = {}) => ({ key: 'reason', label: '说明', type: 'TEXT', required: true, ...extra })
const props = () => reactive({ modelValue: binding(), nodeId: 'service', formSchema: { schemaVersion: 2, fields: [field()] }, scopeKey: 'demo:admin:draft', disabled: false })
const settle = () => new Promise(resolve => setImmediate(resolve))
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function stub() {
  const reads = []
  api.serviceTaskOptions = async after => { reads.push(['directory', after]); return { items: [option('2')], nextAfterKey: null } }
  api.serviceTaskVersions = async (key, before) => { reads.push(['versions', key, before]); return { items: [option('2'), option()], nextBeforeVersion: null } }
  api.serviceTaskOption = async (key, version) => { reads.push(['current', key, version]); return option(version) }
  return reads
}
function mount(values) {
  const updates = [], events = []
  const app = renderer.createApp({ ...Selector, setup: (_, context) => Selector.setup(values, context), render: () => null }, {
    ...values, onBeforeChange: () => events.push('before'), 'onUpdate:modelValue': value => { updates.push(value); events.push('update'); values.modelValue = value }
  })
  const instance = app.mount({})
  return { state: instance.$.setupState, updates, events, close() { app.unmount(); Object.assign(api, originalApi) } }
}

test('完整 long 版本以文本校验、比较及分页，浮点版本和越界原文被拒绝', () => {
  assert.equal(service.serviceTaskVersion(MAX_VERSION), true)
  for (const value of [9223372036854775807, '9223372036854775808', '01', '0', '-1', '1e3', 'latest']) assert.equal(service.serviceTaskVersion(value), false)
  const page = { items: [option(MAX_VERSION), option('9007199254740993')], nextBeforeVersion: '9007199254740993' }
  assert.equal(service.readServiceTaskVersions(page, 'receipt.register'), page)
  assert.throws(() => service.readServiceTaskVersions({ ...page, nextBeforeVersion: 9007199254740992 }, 'receipt.register'))
  assert.throws(() => service.readServiceTaskVersions({ items: [option('2'), option('3')], nextBeforeVersion: null }, 'receipt.register'))
  assert.throws(() => service.readServiceTaskVersions(page, 'other.operation'))
  assert.throws(() => service.readServiceTaskOption(option('1', { contractDigest: 'invalid' })))
  assert.throws(() => service.readServiceTaskOption(option('1', { parameters: [option().parameters[0], option().parameters[0]] })))
})

test('目录保留停用最新版且拒绝重复键和错误游标', () => {
  const disabled = option('2', { enabled: false }), directory = { items: [disabled], nextAfterKey: disabled.key }
  assert.equal(service.readServiceTaskDirectory(directory).items[0].enabled, false)
  assert.throws(() => service.readServiceTaskDirectory({ items: [disabled, option()], nextAfterKey: null }))
  assert.throws(() => service.readServiceTaskDirectory({ ...directory, nextAfterKey: 'different' }))
  assert.throws(() => service.readServiceTaskDirectory(directory, disabled.key))
})

test('字段映射检查实际类型、节点权限和受限标记，管理员没有绕过入口', () => {
  const param = option().parameters[0], issue = source => service.serviceTaskInputIssue(source, param, 'service')
  assert.equal(issue(field()), '')
  assert.equal(issue(field({ type: 'SELECT' })), '')
  assert.match(issue(field({ type: 'TABLE' })), /类型/)
  assert.match(issue(field({ sensitive: true })), /只读权限/)
  assert.match(issue(field({ nodeAccess: { service: 'HIDDEN' } })), /只读权限/)
  assert.equal(issue(field({ sensitive: true, nodeAccess: { service: 'READ_ONLY' } })), '')
  assert.match(service.serviceTaskInputIssue(field({ nodeAccess: { review: 'HIDDEN' } }), { ...param, sensitive: false }, 'service'), /受限制/)
  assert.match(issue(undefined), /不存在|存在的字段/)
})

test('两个设计视图与模板往返保持版本、摘要及映射，清除引用不残留参数', () => {
  const properties = service.writeServiceTaskBinding({ x: '180.5', y: '220' }, { ...binding(), version: MAX_VERSION })
  const graph = { nodes: [{ id: 'start', name: '开始', type: 'START', properties: {} }, { id: 'service', name: '登记', type: 'SERVICE_TASK', properties }, { id: 'review', name: '审批', type: 'USER_TASK', properties: { assigneeRule: 'user:finance' } }, { id: 'end', name: '结束', type: 'END', properties: {} }],
    edges: [['a', 'start', 'service'], ['b', 'service', 'review'], ['c', 'review', 'end']].map(([id, source, target]) => ({ id, source, target, condition: '', defaultBranch: false })) }
  const loaded = loadDesignerNodes(graph.nodes)
  assert.deepEqual(serializeDesignerNodes(loaded), graph.nodes)
  loaded[1].serviceTask.inputs.memo = 'changed'
  assert.equal(properties['serviceInput.memo'], 'reason')
  assert.equal(serializeDesignerNodes(loaded)[1].properties['serviceInput.memo'], 'changed')
  loaded[1].serviceTask = { inputs: {} }
  assert.deepEqual(serializeDesignerNodes(loaded)[1].properties, { x: '180.5', y: '220' })
  assert.equal(projectQuickGraph(graph).reason, '')
  const source = { key: 'service-flow', name: '服务流程', graph, formSchema: { schemaVersion: 2, fields: [field()] } }
  assert.deepEqual(parsePortableTemplate(serializePortableTemplate(source)), source)
  graph.nodes[1].properties.url = 'https://untrusted.invalid'
  assert.throws(() => parsePortableTemplate(serializePortableTemplate(source)), /不支持的属性/)
})

test('快速插入、移动、删除服务节点保留人工审批与原操作引用', () => {
  const graph = { nodes: [{ id: 'start', name: '开始', type: 'START', properties: {} }, { id: 'review', name: '审批', type: 'USER_TASK', properties: { assigneeRule: 'user:finance' } }, { id: 'end', name: '结束', type: 'END', properties: {} }],
    edges: [{ id: 'a', source: 'start', target: 'review', condition: '', defaultBranch: false }, { id: 'b', source: 'review', target: 'end', condition: '', defaultBranch: false }] }
  let counter = 0
  const inserted = editQuickGraph(graph, { kind: 'insert', edgeId: 'b', type: 'SERVICE_TASK' }, () => String(++counter))
  const node = inserted.nodes.find(node => node.type === 'SERVICE_TASK')
  node.properties = service.writeServiceTaskBinding(node.properties, binding())
  const moved = editQuickGraph(inserted, { kind: 'swapTasks', firstId: 'review', secondId: node.id }, () => String(++counter))
  assert.deepEqual(moved.nodes.find(item => item.id === node.id).properties, node.properties)
  assert.deepEqual(editQuickGraph(inserted, { kind: 'removeTask', nodeId: node.id }, () => String(++counter)), graph)
})

test('组件只回显原版本，浏览和同版本选择不改写，明确选新版时清空旧映射', async () => {
  const reads = stub(), values = props(), panel = mount(values)
  try {
    await settle(); assert.equal(panel.state.matched.version, '1'); assert.equal(panel.updates.length, 0)
    assert.ok(reads.some(item => item[0] === 'current' && item[2] === '1'))
    panel.state.browse('receipt.register'); await settle()
    panel.state.choose(option()); assert.equal(panel.updates.length, 0)
    panel.state.choose(option('2')); await settle()
    assert.deepEqual(values.modelValue, { ...binding(), version: '2', inputs: {} })
    assert.deepEqual(panel.events, ['before', 'update'])
    panel.state.mapInput('memo', 'reason'); assert.equal(values.modelValue.inputs.memo, 'reason')
    panel.state.clear(); assert.deepEqual(values.modelValue, { inputs: {} })
  } finally { panel.close() }
})

test('停用、伪造目录版本、锁定和契约不一致均不能写入配置', async () => {
  stub(); const values = props(), panel = mount(values)
  try {
    await settle(); panel.state.browse('receipt.register'); await settle()
    panel.state.choose(option('3')); panel.state.choose(option('2', { enabled: false })); panel.state.choose(option('2', { contractDigest: 'b'.repeat(64) }))
    assert.equal(panel.updates.length, 0)
    values.disabled = true; panel.state.choose(option('2')); panel.state.mapInput('memo', 'reason'); panel.state.clear(); assert.equal(panel.updates.length, 0)
    values.disabled = false; values.modelValue.digest = 'b'.repeat(64); await settle()
    assert.equal(panel.state.matched, null); panel.state.mapInput('memo', 'reason'); assert.equal(panel.updates.length, 0)
    assert.equal(values.modelValue.inputs.memo, 'reason')
  } finally { panel.close() }
})

test('表单删字段或收紧权限时保留原映射并给出错误，禁止自动改到其他字段', async () => {
  stub(); const values = props(), panel = mount(values)
  try {
    await settle(); values.formSchema.fields[0].sensitive = true
    assert.match(panel.state.sourceIssue(option().parameters[0]), /只读权限/)
    panel.state.mapInput('memo', 'reason'); assert.equal(panel.updates.length, 0)
    values.formSchema.fields = []; assert.match(panel.state.sourceIssue(option().parameters[0]), /存在的字段/)
    assert.equal(values.modelValue.inputs.memo, 'reason')
    panel.state.mapInput('memo', ''); assert.deepEqual(values.modelValue.inputs, {})
  } finally { panel.close() }
})

test('账号、定义、引用切换及卸载后迟到读取不能恢复旧目录或改写配置', async () => {
  for (const action of ['scope', 'reference', 'unmount']) {
    stub(); const pending = deferred(); let call = 0
    api.serviceTaskOption = async (key, version) => ++call === 1 ? pending.promise : option(version)
    const values = props(), panel = mount(values); let closed = false
    try {
      if (action === 'scope') values.scopeKey = ''
      if (action === 'reference') values.modelValue = { ...binding(), version: '2' }
      if (action === 'unmount') { panel.close(); closed = true }
      pending.resolve(option()); await settle()
      assert.equal(panel.updates.length, 0)
      assert.equal(panel.state.current.value?.version, action === 'reference' ? '2' : undefined)
    } finally { if (!closed) panel.close() }
  }
})

test('原目录读取失败保留引用和字段，空租户范围不发出查询', async () => {
  stub(); api.serviceTaskOption = async () => { throw new Error('offline') }
  const values = props(), panel = mount(values)
  try {
    await settle(); assert.match(panel.state.current.error, /暂时不可访问/)
    assert.deepEqual(values.modelValue, binding()); assert.equal(panel.updates.length, 0)
  } finally { panel.close() }
  const reads = stub(), empty = props(); empty.scopeKey = ''; const second = mount(empty)
  try { await settle(); assert.equal(reads.length, 0) } finally { second.close() }
})

test('非法版本保留原文并提示修正，不推断最新版或请求精确目录', async () => {
  const reads = stub(), values = props(); values.modelValue.version = '9223372036854775808'
  const panel = mount(values)
  try {
    await settle(); assert.equal(panel.state.invalidReference, true)
    assert.equal(reads.filter(item => item[0] === 'current').length, 0)
    assert.equal(values.modelValue.version, '9223372036854775808'); assert.equal(panel.updates.length, 0)
  } finally { panel.close() }
})
