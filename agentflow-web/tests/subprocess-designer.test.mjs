import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { default: Selector } = await import(process.env.AGENTFLOW_TEST_SUBPROCESS_PANEL)
const { subprocessInputIssue, subprocessVersion, readSubprocessBinding, writeSubprocessBinding } = await import(process.env.AGENTFLOW_TEST_SUBPROCESS_DESIGNER)
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { PortableTemplateReview, serializePortableTemplate } = await import(process.env.AGENTFLOW_TEST_PORTABLE)
const originalApi = { ...api }
const settle = () => new Promise(resolve => setImmediate(resolve))
const deferred = () => { let resolve, reject; const promise = new Promise((ok, no) => { resolve = ok; reject = no }); return { promise, resolve, reject } }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const field = (key, extra = {}) => ({ key, label: key, type: 'NUMBER', required: true, ...extra })
const binding = () => ({ key: 'child', version: '1', inputs: { total: 'amount' } })
const definition = (version = 1, extra = {}) => ({ id: `child-${version}`, key: 'child', version, revision: 0, status: 'PUBLISHED', startEnabled: true, name: '固定子审批',
  graph: { nodes: [], edges: [] }, formSchema: { schemaVersion: 2, fields: [field('total')] }, ...extra })
const props = () => reactive({ modelValue: binding(), nodeId: 'call', formSchema: { schemaVersion: 2, fields: [field('amount')] }, scopeKey: 'demo:admin:draft', disabled: false })
function stub(get = async id => definition(Number(id.split('-').at(-1)))) {
  const reads = []
  api.searchDefinitions = async filters => { reads.push(filters); return { items: [{ id: `child-${filters.version}` }] } }
  api.getDefinition = get
  return reads
}
function mount(values) {
  const updates = [], events = []
  const app = renderer.createApp({ ...Selector, setup: (_, context) => Selector.setup(values, context), render: () => null }, {
    ...values, onBeforeChange: () => events.push('before'), 'onUpdate:modelValue': value => { updates.push(value); events.push('update'); values.modelValue = value }
  })
  const instance = app.mount({})
  return { state: instance.$.setupState, updates, events, close: () => { app.unmount(); Object.assign(api, originalApi) } }
}

test('原版本回显只读准确版本；刷新与同版本选择不改写字段映射', async () => {
  const reads = stub(), values = props(), panel = mount(values)
  try {
    await settle()
    assert.equal(panel.state.current.definition.version, 1)
    assert.deepEqual(reads[0], { limit: 1, status: 'PUBLISHED', processKey: 'child', version: 1 })
    panel.state.loadCurrent(); await settle()
    await panel.state.choose(definition()); await settle()
    assert.deepEqual(values.modelValue, binding())
    assert.equal(panel.updates.length, 0)
    assert.ok(reads.every(item => item.version === 1))
  } finally { panel.close() }
})

test('明确选择新版本后一起更新引用并清空旧映射，停用或非发布配置不能替换', async () => {
  let enabled = true
  stub(async id => definition(Number(id.split('-').at(-1)), { startEnabled: enabled }))
  const values = props(), panel = mount(values)
  try {
    await settle()
    await panel.state.choose(definition(2)); await settle()
    assert.deepEqual(values.modelValue, { key: 'child', version: '2', inputs: {} })
    assert.deepEqual(panel.events, ['before', 'update'])
    assert.equal(panel.state.current.definition.version, 2)
    await panel.state.choose(definition(3, { status: 'DRAFT' }))
    await panel.state.choose(definition(3, { startEnabled: false }))
    enabled = false
    await panel.state.choose(definition(3))
    assert.equal(panel.updates.length, 1)
    assert.equal(values.modelValue.version, '2')
    assert.match(panel.state.choice.error, /不符合/)
  } finally { panel.close() }
})

test('账号或引用切换、锁定和卸载后，旧选中响应均不能改写节点', async () => {
  for (const action of ['scope', 'reference', 'locked', 'unmount']) {
    const pending = deferred()
    stub(async id => id === 'child-2' ? pending.promise : definition(Number(id.split('-').at(-1))))
    const values = props(), panel = mount(values)
    let closed = false
    try {
      await settle()
      const chosen = panel.state.choose(definition(2))
      if (action === 'scope') values.scopeKey = 'other:admin:draft'
      if (action === 'reference') values.modelValue = { key: 'child', version: '3', inputs: {} }
      if (action === 'locked') values.disabled = true
      if (action === 'unmount') { panel.close(); closed = true }
      pending.resolve(definition(2)); await chosen; await settle()
      assert.equal(panel.updates.length, 0, action)
      assert.notEqual(values.modelValue.version, '2', action)
    } finally { if (!closed) panel.close() }
  }
})

test('缺失、异常和畸形原版本保留配置，不能回退新版本或启用映射编辑', async () => {
  for (const mode of ['missing', 'failure', 'wrong-version', 'invalid']) {
    const reads = stub(async () => { if (mode === 'failure') throw new Error('读取失败'); return definition(2) })
    if (mode === 'missing') api.searchDefinitions = async () => ({ items: [] })
    const values = props()
    if (mode === 'invalid') values.modelValue.version = 'latest'
    const before = structuredClone({ ...values.modelValue, inputs: { ...values.modelValue.inputs } })
    const panel = mount(values)
    try {
      await settle()
      assert.equal(panel.state.current.definition, null)
      assert.equal(panel.state.editingDisabled, true)
      assert.deepEqual(values.modelValue, before)
      assert.equal(panel.updates.length, 0)
      if (mode === 'invalid') assert.equal(reads.length, 0)
    } finally { panel.close() }
  }
})

test('映射必须显式选择可读同类型来源；源字段失效时保留原文且可明确清除', async () => {
  const reads = stub()
  const values = props(), panel = mount(values)
  try {
    await settle()
    values.formSchema.fields.push(field('secret', { sensitive: true }), field('text', { type: 'TEXT' }))
    panel.state.changeInput('total', 'secret'); panel.state.changeInput('total', 'text'); panel.state.changeInput('total', 'missing')
    assert.equal(panel.updates.length, 0)
    values.formSchema.fields.push(field('net'))
    panel.state.changeInput('total', 'net')
    assert.equal(values.modelValue.inputs.total, 'net')
    values.formSchema.fields = []
    await settle()
    assert.equal(values.modelValue.inputs.total, 'net')
    assert.match(panel.state.issue(field('total'), 'net'), /不存在/)
    panel.state.changeInput('total', '')
    assert.deepEqual(values.modelValue.inputs, {})
    values.modelValue.inputs.obsolete = 'amount'
    assert.deepEqual(panel.state.removedTargets, ['obsolete'])
    panel.state.changeInput('obsolete', '')
    assert.deepEqual(values.modelValue.inputs, {})
    assert.equal(reads.length, 1)
    values.disabled = true
    panel.state.clear(); panel.state.changeInput('total', 'amount')
    assert.equal(panel.updates.length, 3)
  } finally { panel.close() }
})

test('敏感与隐藏约束覆盖明细列，不能以改名或默认权限降级', () => {
  assert.match(subprocessInputIssue(field('source', { sensitive: true }), field('target', { sensitive: true }), 'call'), /只读权限/)
  const readable = field('source', { sensitive: true, nodeAccess: { call: 'READ_ONLY' } })
  assert.match(subprocessInputIssue(readable, field('target'), 'call'), /敏感标记/)
  assert.equal(subprocessInputIssue(readable, field('target', { sensitive: true }), 'call'), '')
  assert.match(subprocessInputIssue(field('source', { nodeAccess: { other: 'HIDDEN' } }), field('target'), 'call'), /敏感标记/)
  const source = field('rows', { type: 'TABLE', columns: [field('secret', { nodeAccess: { call: 'HIDDEN' } })] })
  const target = field('items', { type: 'TABLE', columns: [field('secret')] })
  assert.match(subprocessInputIssue(source, target, 'call'), /明细列 secret.*只读权限/)
  assert.match(subprocessInputIssue(field('rows', { type: 'TABLE', columns: [] }), target, 'call'), /明细列 secret.*不存在/)
  target.columns[0].required = false
  assert.equal(subprocessInputIssue(field('rows', { type: 'TABLE', columns: [] }), target, 'call'), '')
  assert.equal(subprocessInputIssue(field('value'), field('value'), 'toString'), '')
})

test('非法旧引用原样往返但不请求目录，清除映射不影响其他属性', () => {
  for (const version of ['latest', '01', '0', '2147483648', '${version}']) {
    const properties = { subprocessKey: 'child', subprocessVersion: version, 'subprocessInput.toString': 'amount', x: '32' }
    const value = readSubprocessBinding(properties)
    assert.equal(subprocessVersion(value), undefined)
    assert.deepEqual(writeSubprocessBinding(properties, value), properties)
    assert.deepEqual(writeSubprocessBinding(properties, { inputs: {} }), { x: '32' })
  }
})

test('模板能够预检固定子引用，但公开就绪门禁继续阻止导入保存', async () => {
  const value = { key: 'parent', name: '父流程', graph: { nodes: [{ id: 'call', name: '子审批', type: 'SUB_PROCESS', properties: {
    subprocessKey: 'child', subprocessVersion: '1', 'subprocessInput.total': 'amount' } }], edges: [] }, formSchema: null }
  const review = new PortableTemplateReview(async () => ({ errors: ['SUBPROCESS_RUNTIME_NOT_READY:call'] }))
  const text = serializePortableTemplate(value)
  await review.read({ size: text.length, text: async () => text }); await review.check()
  assert.equal(review.canImport, false)
  assert.deepEqual(review.value, value)
  review.clear()
})
