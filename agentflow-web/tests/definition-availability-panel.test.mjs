import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_AVAILABILITY_PANEL)
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function panel(fetchHistory = async () => ({ items: [] })) {
  api.definitionAvailabilityHistory = fetchHistory
  const events = [], props = reactive({ definitionId: 'version-one', revision: 1, startEnabled: true, scopeKey: 'demo/admin', locked: false, error: '' })
  const app = renderer.createApp({ ...Panel, setup: (_, context) => Panel.setup(props, context), render: () => null }, { ...props, onChange: input => events.push(input) })
  const instance = app.mount({})
  return { state: instance.$.setupState, props, events, close: () => app.unmount() }
}

test('旧幂等响应缺少开关字段时必须刷新，不能把未知状态当停用后恢复', async () => {
  const p = panel()
  try {
    p.props.startEnabled = undefined
    await p.state.prepare()
    assert.equal(p.state.intent, null)
    p.state.reason = '不应执行'; p.state.execute(); assert.deepEqual(p.events, [])
  } finally { p.close() }
})

test('停用先填写原因，取消不提交，确认发送准确版本修订', async () => {
  const p = panel()
  try {
    await p.state.prepare(); assert.equal(p.state.intent, false); assert.deepEqual(p.events, [])
    p.state.execute(); assert.deepEqual(p.events, []); assert.match(p.state.validation, /原因/)
    p.state.reason = '说明'; p.state.cancel(); assert.equal(p.state.intent, null); assert.equal(p.state.reason, '')
    await p.state.prepare(); p.state.reason = '  制度调整\n暂停新发起。  '; p.state.execute()
    assert.deepEqual(p.events, [{ expectedRevision: 1, startEnabled: false, reason: '制度调整\n暂停新发起。' }])
  } finally { p.close() }
})

test('切换账号或版本清除旧意图，写锁阻止重新发送', async () => {
  const p = panel()
  try {
    for (const change of [() => p.props.scopeKey = 'other/admin', () => p.props.definitionId = 'version-two', () => p.props.revision++]) {
      await p.state.prepare(); p.state.reason = '旧说明'; change()
      assert.equal(p.state.intent, null); assert.equal(p.state.reason, '')
      p.state.execute(); assert.deepEqual(p.events, [])
    }
    await p.state.prepare(); p.state.reason = '停用说明'; p.props.locked = true; p.state.execute()
    assert.deepEqual(p.events, []); p.props.locked = false
    p.props.startEnabled = false; p.props.revision++
    await p.state.prepare(); assert.equal(p.state.intent, true)
  } finally { p.close() }
})

test('确认完成关闭表单后焦点返回当前版本操作按钮', async () => {
  const p = panel(); let focused = 0
  try {
    p.state.actionButton = { focus: () => focused++ }
    await p.state.prepare(); p.state.reason = '已确认'; p.state.execute()
    p.props.revision++
    await Promise.resolve()
    assert.equal(focused, 1)
  } finally { p.close() }
})

test('旧账号历史迟到不能覆盖新版本，关闭后也不写回结果', async () => {
  const pending = []
  const p = panel((id, before, signal) => new Promise(resolve => pending.push({ id, before, signal, resolve })))
  p.props.definitionId = 'version-two'
  assert.equal(pending[0].signal.aborted, true)
  pending[1].resolve({ items: [{ revision: 4, reason: '新记录' }] })
  await Promise.resolve(); await Promise.resolve()
  pending[0].resolve({ items: [{ revision: 2, reason: '旧记录' }] })
  await Promise.resolve(); await Promise.resolve()
  assert.equal(p.state.rows[0].reason, '新记录')
  const reading = p.state.load(); p.close(); pending[2].resolve({ items: [{ revision: 9 }] }); await reading
  assert.equal(p.state.rows.length, 0)
})

test('翻页失败保留原游标和记录，重试不跳页', async () => {
  const seen = []
  const p = panel(async (id, before) => {
    seen.push(before)
    if (seen.length === 1) return { items: [{ revision: 8 }], nextBeforeRevision: 8 }
    if (seen.length === 2) throw new Error('读取失败')
    return { items: [{ revision: 7 }] }
  })
  try {
    await Promise.resolve(); await Promise.resolve()
    await p.state.load(true); assert.equal(p.state.rows.length, 1); assert.equal(p.state.nextBefore, 8)
    assert.equal(p.state.historyError, '读取失败')
    await p.state.load(true); assert.deepEqual(seen, [undefined, 8, 8]); assert.equal(p.state.rows.length, 2)
  } finally { p.close() }
})
