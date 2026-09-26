import test from 'node:test'
import assert from 'node:assert/strict'
const { DefinitionSelection } = await import(process.env.AGENTFLOW_TEST_SELECTION)
const definition = (id, extra = {}) => ({ id, key: 'leave', name: '请假', status: 'PUBLISHED', version: 2,
  graph: { nodes: [{ id: 'approval', name: '经理审批' }], edges: [] }, formSchema: { fields: [{ key: 'days', type: 'NUMBER' }] }, ...extra })
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve = a; reject = b }); return { promise, resolve, reject } }

test('新申请筛选可发起版本并复核完整配置，历史比较仍可读取停用版本', async () => {
  const filters = []
  const selection = new DefinitionSelection(async query => { filters.push(query); return { items: [{ id: 'disabled' }] } }, async id => definition(id, { startEnabled: false }))
  assert.equal(await selection.load('demo/alice', '', { publishedOnly: true, startEnabledOnly: true }), null)
  assert.deepEqual(filters[0], { limit: 1, status: 'PUBLISHED', startEnabled: true })
  assert.match(selection.error, /不符合/)
  const historical = await selection.load('demo/admin', 'disabled', { publishedOnly: true })
  assert.equal(historical.id, 'disabled'); assert.equal(historical.startEnabled, false)
})

test('指定版本只读一份完整配置，图和表单来自同一版本', async () => {
  const calls = []
  const selection = new DefinitionSelection(() => { throw new Error('unexpected catalog read') }, async (id, signal) => { calls.push({ id, signal }); return definition(id) })
  const result = await selection.load('demo/alice', 'exact-v2', { publishedOnly: true })
  assert.equal(result.id, 'exact-v2'); assert.equal(result.version, 2); assert.equal(result.formSchema.fields[0].key, 'days')
  assert.equal(selection.definition, result); assert.equal(calls.length, 1); assert.equal(calls[0].signal.aborted, false)
})

test('没有指定版本时只取一条摘要；空目录不读取配置', async () => {
  const filters = [], ids = []
  const selection = new DefinitionSelection(async query => { filters.push(query); return { items: filters.length === 1 ? [{ id: 'newest' }] : [] } }, async id => { ids.push(id); return definition(id) })
  await selection.load('demo/alice', '', { publishedOnly: true })
  assert.deepEqual(filters[0], { limit: 1, status: 'PUBLISHED' }); assert.deepEqual(ids, ['newest'])
  await selection.load('demo/alice', '', { publishedOnly: true })
  assert.equal(selection.definition, null); assert.equal(selection.error, ''); assert.deepEqual(ids, ['newest'])
})

test('快速切换和关闭立即清除旧配置，迟到成功失败不能覆盖', async () => {
  const pending = []
  const selection = new DefinitionSelection(async () => ({ items: [] }), (id, signal) => { const item = { id, signal, ...deferred() }; pending.push(item); return item.promise })
  const old = selection.load('demo/alice', 'old'), current = selection.load('demo/alice', 'current')
  assert.equal(pending[0].signal.aborted, true); assert.equal(selection.definition, null)
  pending[1].resolve(definition('current')); await current
  pending[0].resolve(definition('old')); assert.equal(await old, null); assert.equal(selection.definition.id, 'current')
  const closing = selection.load('demo/alice', 'closing'); selection.clear()
  pending[2].reject(new Error('late failure')); await closing
  assert.equal(selection.definition, null); assert.equal(selection.error, ''); assert.equal(selection.loading, false)
})

test('切换账号后旧摘要不再触发完整配置读取', async () => {
  const page = deferred(), reads = []
  const selection = new DefinitionSelection(() => page.promise, async id => { reads.push(id); return definition(id) })
  const old = selection.load('old/alice'); const current = selection.load('new/bob', 'new-id')
  await current; page.resolve({ items: [{ id: 'old-id' }] }); await old
  assert.deepEqual(reads, ['new-id']); assert.equal(selection.definition.id, 'new-id')
})

test('申请拒绝草稿，比较基线拒绝其他流程；失败后没有可提交配置', async () => {
  let value = definition('draft', { status: 'DRAFT', version: 0 })
  const selection = new DefinitionSelection(async () => ({ items: [] }), async () => value)
  assert.equal(await selection.load('demo/admin', 'draft', { publishedOnly: true }), null)
  assert.match(selection.error, /不符合/); assert.equal(selection.definition, null)
  value = definition('other', { key: 'expense' })
  await selection.load('demo/admin', 'other', { publishedOnly: true, processKey: 'leave' })
  assert.equal(selection.definition, null); assert.match(selection.error, /不符合/)
})

test('只有设计器失效的 404 偏好可回退；明确版本及网络或权限失败均不换版本', async () => {
  let calls = 0, failure = { status: 404, message: 'not found' }
  const selection = new DefinitionSelection(async () => { calls++; return { items: [{ id: 'fallback' }] } }, async id => { if (id !== 'fallback') throw failure; return definition(id) })
  await selection.load('demo/admin', 'missing', { restoreMissing: true }); assert.equal(selection.definition.id, 'fallback'); assert.equal(calls, 1)
  await selection.load('demo/admin', 'missing', { publishedOnly: true }); assert.equal(selection.definition, null); assert.equal(calls, 1)
  for (const status of [0, 403, 500]) {
    failure = { status, message: 'cannot read' }; await selection.load('demo/admin', 'old', { restoreMissing: true })
    assert.equal(selection.definition, null); assert.equal(selection.error, 'cannot read'); assert.equal(calls, 1)
  }
})

test('超时结束等待且迟到配置不会重新出现', async context => {
  context.mock.timers.enable({ apis: ['setTimeout'] })
  const pending = deferred(); let signal
  const selection = new DefinitionSelection(async () => ({ items: [] }), (id, current) => { signal = current; return pending.promise })
  const request = selection.load('demo/alice', 'slow', { publishedOnly: true })
  context.mock.timers.tick(12_000); await request
  assert.equal(signal.aborted, true); assert.equal(selection.loading, false); assert.match(selection.error, /超时/)
  pending.resolve(definition('slow')); await Promise.resolve()
  assert.equal(selection.definition, null)
})
