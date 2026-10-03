import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const rules = await import(process.env.AGENTFLOW_TEST_EXPENSE_ARCHIVE)
const { default: Component } = await import(process.env.AGENTFLOW_TEST_EXPENSEARCHIVESTATUS)
const { api, bindAuthenticationActor } = await import(process.env.AGENTFLOW_TEST_API)
const originalApi = { ...api }, originalFetch = global.fetch
global.localStorage = { getItem: () => 'test-token', setItem() {}, removeItem() {} }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const settle = () => new Promise(resolve => setImmediate(resolve))
const binding = () => ({ reportId: 'report', applicationId: 'app', roundNo: 1, applicationVersion: 9, financialVersion: 3 })
const view = () => ({ ...binding(), status: 'ARCHIVED', manifestSha256: 'a'.repeat(64), archivedAt: '2026-09-28T10:00:00Z', originalCount: 1, voucherCount: 2, issue: null, canDownload: true })
let serial = 0
function mount() {
  const props = reactive({ ...binding(), scopeKey: 'archive-' + ++serial, locked: false }), events = []
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), render: () => null }, { ...props, onBusy: value => events.push(value) })
  const instance = app.mount({})
  return { props, events, state: instance.$.setupState, close() { app.unmount(); Object.assign(api, originalApi); global.fetch = originalFetch; bindAuthenticationActor(null) } }
}

test('归档绑定实际轮次，缺失清单和零件不能显示已归档', () => {
  assert.deepEqual(rules.validateArchive(view(), binding()), view())
  for (const key of Object.keys(binding())) assert.throws(() => rules.validateArchive({ ...view(), [key]: 'foreign' }, binding()))
  for (const change of [{ status: 'UNKNOWN' }, { status: 'WAITING' }, { manifestSha256: null }, { archivedAt: 'bad' }, { canDownload: false }, { originalCount: -1 }, { issue: 'raw remote error' }]) assert.throws(() => rules.validateArchive({ ...view(), ...change }, binding()))
  assert.doesNotThrow(() => rules.validateArchive({ ...view(), issue: 'ARCHIVE_PAYMENT_VOUCHER_REQUIRED' }, binding()))
  assert.doesNotThrow(() => rules.validateArchive({ ...view(), status: 'WAITING', manifestSha256: null, archivedAt: null, originalCount: 0, voucherCount: 0, canDownload: false, issue: 'ARCHIVE_CHECK_PENDING' }, binding()))
})

test('读取切换身份时丢弃旧清单，拒绝响应不保留下载按钮', async () => {
  let finish; api.expenseArchive = () => new Promise(resolve => { finish = resolve })
  const instance = mount(); const old = finish
  try {
    api.expenseArchive = async () => { throw { status: 403 } }; instance.props.scopeKey = 'other'; await settle()
    old(view()); await settle(); assert.equal(instance.state.view, null); assert.match(instance.state.error, /无权/)
  } finally { instance.close() }
})

test('下载前重读清单，清单变化后不读取原文件', async () => {
  let downloads = 0; api.expenseArchive = async () => view(); api.downloadExpenseArchive = async () => { downloads++; return new Blob(['zip']) }
  const instance = mount()
  try {
    await settle(); api.expenseArchive = async () => ({ ...view(), manifestSha256: 'b'.repeat(64) })
    await instance.state.download(); assert.equal(downloads, 0); assert.equal(instance.state.view, null); assert.match(instance.state.error, /清单已变化/)
    assert.equal(instance.events.at(-1), false)
  } finally { instance.close() }
})

test('下载中切换轮次会中止请求，迟到的档案不触发保存', async () => {
  let finish, signal; api.expenseArchive = async () => view(); api.downloadExpenseArchive = async (_id, _round, input) => { signal = input; return new Promise(resolve => { finish = resolve }) }
  const instance = mount()
  try {
    await settle(); const downloading = instance.state.download(); await settle(); assert.equal(instance.state.downloading, true)
    instance.props.roundNo = 2; api.expenseArchive = async () => { throw { status: 404 } }; await settle(); assert.equal(signal.aborted, true)
    finish(new Blob(['zip'])); await downloading; assert.equal(instance.state.downloading, false); assert.equal(instance.events.at(-1), false)
  } finally { instance.close() }
})

test('等待状态和界面锁定不启动下载', async () => {
  let reads = 0; api.expenseArchive = async () => { reads++; return view() }
  const instance = mount()
  try { await settle(); instance.props.locked = true; await instance.state.download(); assert.equal(reads, 1) }
  finally { instance.close() }
})

test('归档 API 固定轮次和无缓存读取，HTML 或截断 ZIP 不保存', async () => {
  let requested
  try {
    global.fetch = async (path, options) => { requested = { path, options }; return new Response('<html/>', { headers: { 'Content-Type': 'text/html' } }) }
    await assert.rejects(api.downloadExpenseArchive('report/x', 2, new AbortController().signal), error => error.code === 'RESPONSE_UNREADABLE')
    assert.equal(requested.path, '/api/v1/expense-reports/report%2Fx/archive/content?roundNo=2'); assert.equal(requested.options.cache, 'no-store')
    const bytes = new Uint8Array(70); bytes.set([80, 75, 3, 4]); bytes.set([80, 75, 5, 6], 48)
    global.fetch = async () => new Response(bytes, { headers: { 'Content-Type': 'application/zip' } })
    assert.equal((await api.downloadExpenseArchive('report', 1, new AbortController().signal)).size, bytes.length)
    global.fetch = async () => new Response(bytes.slice(0, 50), { headers: { 'Content-Type': 'application/zip' } })
    await assert.rejects(api.downloadExpenseArchive('report', 1, new AbortController().signal), error => error.code === 'RESPONSE_UNREADABLE')
  } finally { global.fetch = originalFetch }
})
