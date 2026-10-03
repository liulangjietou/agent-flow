import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_ASSIST_ACTIONS)
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { assistInput: api.assistInput, generateAssist: api.generateAssist, reviewAssist: api.reviewAssist }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const settle = () => new Promise(resolve => setImmediate(resolve))
const input = () => ({ applicationVersion: 2, enabled: true, targetDigest: 'a'.repeat(64), providerId: 'fixture', model: 'model', destination: 'localhost',
  sources: [{ reference: { sourceId: 'form:reason', contentDigest: 'b'.repeat(64) }, label: '说明', content: '"授权内容"' }] })
function panel() {
  const props = reactive({ applicationId: 'application', scopeKey: 'demo/manager', taskId: 'task', version: 2, detail: null, locked: false })
  const events = []
  const app = renderer.createApp({ ...Panel, setup: (_, context) => Panel.setup(props, context), render: () => null }, { ...props, onChanged: id => events.push(id) })
  const instance = app.mount({})
  return { state: instance.$.setupState, props, events, close: () => { app.unmount(); Object.assign(api, originals) } }
}

test('身份变化立即清空输入与人工草稿，旧响应不能进入新身份', async () => {
  const calls = []
  api.assistInput = (_, __, signal) => new Promise(resolve => calls.push({ signal, resolve }))
  const p = panel()
  try {
    calls[0].resolve(input()); await settle()
    p.state.selected = ['form:reason']; p.state.acceptedText = '旧身份修订'; p.state.comment = '旧意见'
    p.props.scopeKey = 'demo/finance'
    assert.equal(p.state.options, null)
    assert.deepEqual(p.state.selected, [])
    assert.equal(p.state.acceptedText, ''); assert.equal(p.state.comment, '')
    assert.equal(calls[0].signal.aborted, true)
    p.props.taskId = 'new-task'
    calls[2].resolve(input()); await settle()
    calls[1].resolve({ ...input(), sources: [] }); await settle()
    assert.equal(p.state.options.sources.length, 1)
  } finally { p.close() }
})

test('输入默认不勾选，只发送明确选择的字段和已展示目标指纹', async () => {
  api.assistInput = async () => input()
  const sent = []
  api.generateAssist = async (...args) => { sent.push(args); return { id: 'run', status: 'QUEUED', version: 1 } }
  const p = panel()
  try {
    await settle(); assert.deepEqual(p.state.selected, [])
    await p.state.generate(); assert.equal(sent.length, 0)
    p.state.selected = ['form:reason']; await p.state.generate()
    assert.deepEqual(sent, [['application', { taskId: 'task', expectedVersion: 2, targetDigest: 'a'.repeat(64), sourceIds: ['form:reason'] }]])
    assert.deepEqual(p.events, ['run']); assert.deepEqual(p.state.selected, [])
    p.props.locked = true; p.state.selected = ['form:reason']; await p.state.generate()
    assert.equal(sent.length, 1)
  } finally { p.close() }
})

test('身份切换和卸载后忽略迟到写入回执，不刷新其他人的运行记录', async () => {
  api.assistInput = async () => input()
  let resolve
  api.generateAssist = () => new Promise(done => { resolve = done })
  const p = panel()
  try {
    await settle(); p.state.selected = ['form:reason']; const request = p.state.generate()
    p.props.scopeKey = 'demo/finance'; await settle()
    resolve({ id: 'old-run' }); await request
    assert.deepEqual(p.events, []); assert.equal(p.state.saving, false); assert.equal(p.state.notice, '')
    p.state.selected = ['form:reason']; const second = p.state.generate(); p.close()
    resolve({ id: 'after-unmount' }); await second; assert.deepEqual(p.events, [])
  } finally { Object.assign(api, originals) }
})

test('人工复核使用申请和运行两个版本，过期输入只允许记录未采纳', async () => {
  api.assistInput = async () => input()
  const reviews = []
  api.reviewAssist = async (...args) => { reviews.push(args); return { id: 'run', status: 'DISMISSED', version: 4 } }
  const p = panel()
  try {
    await settle()
    p.props.detail = { id: 'run', status: 'COMPLETED', version: 3, applicationVersion: 1, inputCurrent: false, suggestion: { claims: [{ text: '模型原文' }] } }
    await settle(); assert.equal(p.state.acceptedText, '模型原文')
    p.state.acceptedText = '人工修订'; p.state.comment = '已过期'
    await p.state.review('ADOPT'); assert.equal(reviews.length, 0)
    await p.state.review('DISMISS')
    assert.deepEqual(reviews, [['application', 'run', { taskId: 'task', expectedVersion: 2, expectedRunVersion: 3, action: 'DISMISS', comment: '已过期' }]])
    p.props.detail = { ...p.props.detail, applicationVersion: 2, inputCurrent: true }
    await p.state.review('ADOPT')
    assert.equal(reviews[1][2].acceptedText, '人工修订')
    assert.equal(p.props.detail.suggestion.claims[0].text, '模型原文')
  } finally { p.close() }
})

test('输入读取超时解除等待且丢弃迟到结果，目的地变化给出重新选择提示', async () => {
  const savedSetTimeout = globalThis.setTimeout, savedClearTimeout = globalThis.clearTimeout
  let timeout, resolve
  globalThis.setTimeout = callback => { timeout = callback; return 1 }
  globalThis.clearTimeout = () => {}
  api.assistInput = () => new Promise(done => { resolve = done })
  const p = panel()
  try {
    timeout(); assert.equal(p.state.loading, false); assert.match(p.state.error, /超时/)
    resolve(input()); await settle(); assert.equal(p.state.options, null)
    api.assistInput = async () => input(); await p.state.load()
    api.generateAssist = async () => { throw { code: 'AGENT_TARGET_CHANGED' } }
    p.state.selected = ['form:reason']; await p.state.generate()
    assert.match(p.state.error, /目的地已变化/); assert.equal(p.state.saving, false)
  } finally { globalThis.setTimeout = savedSetTimeout; globalThis.clearTimeout = savedClearTimeout; p.close() }
})
