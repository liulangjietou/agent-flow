import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_COPY_RECORD)
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const original = api.copySnapshot
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const settle = () => new Promise(resolve => setImmediate(resolve))
function panel() {
  globalThis.document = { activeElement: null }
  const props = reactive({ applicationId: 'application', roundNo: 1, scopeKey: 'demo/bob' })
  const app = renderer.createApp({ ...Panel, setup: (_, context) => Panel.setup(props, context), render: () => null })
  const instance = app.mount({})
  return { state: instance.$.setupState, props, close: () => { app.unmount(); api.copySnapshot = original; delete globalThis.document } }
}
test('切换轮次或身份立即清空旧正文，迟到响应不能显示到新身份', async () => {
  const calls = []
  api.copySnapshot = (id, round, signal) => new Promise(resolve => calls.push({ id, round, signal, resolve }))
  const p = panel()
  try {
    p.props.scopeKey = 'demo/manager'
    assert.equal(p.state.value, null)
    assert.equal(calls[0].signal.aborted, true)
    calls[1].resolve({ title: '本次授权内容' }); await settle()
    calls[0].resolve({ title: '旧身份内容' }); await settle()
    assert.equal(p.state.value.title, '本次授权内容')
    p.props.roundNo = 2
    assert.equal(p.state.value, null)
    assert.equal(calls[2].round, 2)
    calls[2].resolve({ title: '第二轮' }); await settle()
    assert.equal(p.state.value.title, '第二轮')
  } finally { p.close() }
})
test('权限失效刷新不保留旧内容，超时解除加载并丢弃迟到响应', async () => {
  api.copySnapshot = async () => ({ title: '曾经可读' })
  const p = panel()
  const savedSetTimeout = globalThis.setTimeout, savedClearTimeout = globalThis.clearTimeout
  try {
    await settle(); assert.equal(p.state.value.title, '曾经可读')
    api.copySnapshot = async () => { throw { message: '抄送已不可见' } }
    await p.state.load(); assert.equal(p.state.value, null); assert.equal(p.state.error, '抄送已不可见')
    let timeout, resolve
    globalThis.setTimeout = callback => { timeout = callback; return 1 }
    globalThis.clearTimeout = () => {}
    api.copySnapshot = () => new Promise(done => { resolve = done })
    const loading = p.state.load(); timeout()
    assert.equal(p.state.loading, false); assert.match(p.state.error, /超时/)
    resolve({ title: '迟到内容' }); await loading
    assert.equal(p.state.value, null)
  } finally { globalThis.setTimeout = savedSetTimeout; globalThis.clearTimeout = savedClearTimeout; p.close() }
})
