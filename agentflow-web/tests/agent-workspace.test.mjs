import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive, nextTick } from 'vue'

const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { default: Workspace } = await import(process.env.AGENTFLOW_TEST_AGENTWORKSPACEPANEL)
const originals = { ...api }
afterEach(() => Object.assign(api, originals))
const settle = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }
const task = id => ({ taskId: id, applicationId: `app-${id}`, title: `申请 ${id}`, businessNo: `TEST-${id}` })
const page = (items = [], nextCursor = null, total = items.length) => ({ items, nextCursor, total })

function mount() {
  const props = reactive({ scopeKey: 'demo:alice', refreshVersion: 0, locked: false }), events = []
  const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
  const app = renderer.createApp({ ...Workspace, setup: (_, context) => Workspace.setup(props, context), render: () => null }, {
    ...props, onSelect: value => events.push(['select', value]), onNavigate: value => events.push(['navigate', value])
  })
  const state = app.mount({}).$.setupState
  let mounted = true
  return { props, state, events, close: () => { if (mounted) { mounted = false; app.unmount() } } }
}

test('助理入口读取真实待办，选择仅交给工作台核验，不自动生成或办理', async () => {
  const reads = [], writes = []
  api.taskPage = async filters => { reads.push(filters); return page([task('first')]) }
  for (const key of ['taskAction', 'startExpenseAgent', 'generateAssist']) api[key] = async () => writes.push(key)
  const view = mount()
  try {
    await settle(); assert.equal(reads.length, 1); assert.equal(view.state.query.total, 1)
    view.state.open(view.state.query.items[0]); view.state.navigate('expense')
    assert.deepEqual(view.events, [['select', task('first')], ['navigate', 'expense']]); assert.deepEqual(writes, [])
    view.props.locked = true; view.state.open(task('first')); view.state.navigate('applications'); view.state.search()
    assert.equal(view.events.length, 2); assert.equal(reads.length, 1)
  } finally { view.close() }
})

test('搜索明确应用筛选，分页沿用已提交条件，清空后恢复全部范围', async () => {
  const reads = []
  api.taskPage = async filters => {
    reads.push({ ...filters })
    return filters.cursor ? page([task('second')], null, 2) : page([task('first')], 'next-page', 2)
  }
  const view = mount()
  try {
    await settle(); view.state.searchText = '  交接  '; view.state.assignment = 'assigned'; view.state.search(); await settle()
    assert.equal(reads[1].q, '交接'); assert.equal(reads[1].assignment, 'assigned')
    view.state.searchText = '尚未查询的输入'; await view.state.query.more()
    assert.equal(reads[2].q, '交接'); assert.equal(reads[2].cursor, 'next-page'); assert.equal(view.state.query.items.length, 2)
    view.state.reset(); await settle(); assert.equal(reads[3].q, ''); assert.equal(reads[3].assignment, 'all')
  } finally { view.close() }
})

test('会话变化取消旧待办查询，迟到响应不能恢复原账号任务', async () => {
  let finish, signal, calls = 0
  api.taskPage = async (_, requestSignal) => ++calls === 1 ? new Promise(resolve => { finish = resolve; signal = requestSignal }) : page([task('bob')])
  const view = mount()
  try {
    view.props.scopeKey = 'demo:bob'; await settle(); assert.equal(signal.aborted, true)
    finish(page([task('alice')])); await settle()
    assert.deepEqual(view.state.query.items, [task('bob')]); assert.deepEqual(view.events, [])
  } finally { view.close() }
})

test('读取失败保留错误，重试成功才形成可用空态，卸载取消未完成请求', async () => {
  let failed = true, hanging = false, signal, finish
  api.taskPage = async (_, requestSignal) => { signal = requestSignal; if (failed) throw new Error('待办暂不可读'); return hanging ? new Promise(resolve => { finish = resolve }) : page() }
  const view = mount()
  try {
    await settle(); assert.equal(view.state.query.loaded, false); assert.equal(view.state.query.error, '待办暂不可读')
    failed = false; await view.state.refresh(); assert.equal(view.state.query.loaded, true); assert.equal(view.state.query.error, '')
    hanging = true; const pending = view.state.refresh(); view.close(); assert.equal(signal.aborted, true)
    finish(page([task('late')])); await pending; assert.deepEqual(view.state.query.items, [])
  } finally { view.close() }
})
