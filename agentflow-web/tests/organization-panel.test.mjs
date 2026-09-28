import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive, nextTick } from 'vue'
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_ORGANIZATION_PANEL)
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
async function flush() { for (let i = 0; i < 12; i++) await Promise.resolve(); await nextTick() }
async function panel() {
  api.organizationStatus = async () => ({ initialized: true })
  api.organizationUnits = async () => ({ items: [] })
  api.organizationPeople = async () => ({ items: [] })
  api.organizationAppointments = async () => ({ items: [] })
  const props = reactive({ scopeKey: 'panel/' + Math.random(), refreshVersion: 0, locked: false })
  const app = renderer.createApp({ ...Panel, setup: (_, context) => Panel.setup(props, context), render: () => null }, props)
  const instance = app.mount({}); await flush()
  return { state: instance.$.setupState, props, close: () => app.unmount() }
}
for (const kind of ['history', 'reference']) test(`${kind} 请求超时解除等待，保留原记录及游标供重试`, async () => {
  const p = await panel(), timers = [], original = globalThis.setTimeout
  let signal, job
  try {
    p.state.history = [{ revision: 5 }]; p.state.before = 5
    p.state.options.PERSON = { items: [{ id: 'p1', displayName: '甲' }], next: 'p1' }
    const blocked = (...args) => new Promise((resolve, reject) => {
      signal = args.at(-1); signal.addEventListener('abort', () => reject(new Error('读取超时')), { once: true })
    })
    api.organizationChanges = blocked; api.organizationPeople = blocked
    globalThis.setTimeout = (callback, delay) => { timers.push({ callback, delay }); return 123 }
    job = kind === 'history' ? p.state.loadHistory(true) : p.state.moreReference('PERSON')
    assert.equal(timers.length, 1, '独立读取也必须设置超时')
    assert.equal(timers[0].delay, 12000); timers[0].callback(); await job
    assert.equal(signal.aborted, true); assert.equal(p.state.loading, false)
    assert.equal(p.state.before, 5); assert.equal(p.state.history[0].revision, 5)
    assert.equal(p.state.options.PERSON.next, 'p1'); assert.equal(p.state.options.PERSON.items.length, 1)
  } finally { globalThis.setTimeout = original; p.close(); await job }
})

test('旧账号的迟到响应不能写回当前目录，权限拒绝清除可见引用', async () => {
  const p = await panel(); let release
  try {
    api.organizationStatus = () => new Promise(resolve => { release = resolve })
    const old = p.state.load()
    api.organizationStatus = async () => { throw { status: 403, message: '无权限' } }
    p.props.scopeKey = 'other/admin'; await flush()
    release({ initialized: true }); await old
    assert.equal(p.state.denied, true); assert.deepEqual(p.state.rows, [])
    assert.ok(Object.values(p.state.options).every(value => value.items.length === 0))
  } finally { p.close() }
})
