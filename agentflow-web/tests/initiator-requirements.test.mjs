import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer } from 'vue'
const { default: Record } = await import(process.env.AGENTFLOW_TEST_APPLICATION_RECORD)
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { InitiatorRequirements } = await import(process.env.AGENTFLOW_TEST_INITIATOR_REQUIREMENTS)
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const settle = () => new Promise(resolve => setImmediate(resolve))
const requirement = (required = true) => ({ processKey: 'parent', definitionVersion: 1, appointmentRequired: required })
const application = status => ({ id: 'app', processKey: 'parent', definitionVersion: 1, createdBy: 'alice', status, title: '父流程申请', payload: {}, version: 1, roundNo: 0 })
const target = (id = 'definition') => ({ kind: 'definition', id, processKey: 'parent', definitionVersion: 1 })
const deferred = () => { let resolve, reject; const promise = new Promise((ok, no) => { resolve = ok; reject = no }); return { resolve, reject, promise } }

test('要求只来自准确版本的布尔响应；缺失、失效和读取失败均不当作任职可选', async () => {
  let result = requirement(false)
  const state = new InitiatorRequirements(async () => result, async () => requirement())
  assert.match(state.submissionError(''), /读取/)
  await state.load('demo/alice', target()); assert.equal(state.required, false); assert.equal(state.submissionError(''), '')
  for (const invalid of [null, {}, { ...requirement(), processKey: 'other' }, { ...requirement(), definitionVersion: 2 },
    { ...requirement(), appointmentRequired: 'false' }, { ...requirement(), graph: {} }]) {
    result = invalid; await state.load('demo/alice', target())
    assert.equal(state.required, null); assert.match(state.submissionError('chosen'), /无法确认/)
  }
  await state.load('demo/alice', { ...target('app'), kind: 'application' })
  assert.equal(state.required, true); assert.match(state.submissionError(''), /选择/); assert.equal(state.submissionError('chosen'), '')
  state.clear(); assert.equal(state.required, null)
})

test('换账号、换版本、关闭与晚到错误不能覆盖当前要求，重试读取超时可以恢复', async () => {
  const pending = [], state = new InitiatorRequirements((id, signal) => { const request = { id, signal, ...deferred() }; pending.push(request); return request.promise }, async () => requirement())
  const old = state.load('old/alice', target('old'))
  const fresh = state.load('new/bob', target('current'))
  assert.equal(pending[0].signal.aborted, true)
  pending[1].resolve(requirement(false)); await fresh
  pending[0].resolve(requirement(true)); await old
  assert.equal(state.required, false)
  const closing = state.load('new/bob', target('closing')); state.clear()
  pending[2].reject(new Error('late failure')); await closing
  assert.equal(state.required, null); assert.equal(state.error, '')
  const timers = [], originalTimeout = globalThis.setTimeout
  globalThis.setTimeout = callback => { timers.push(callback); return 456 }
  try {
    const stalled = state.load('new/bob', target('timeout'))
    timers[0](); await stalled
    assert.equal(pending[3].signal.aborted, true); assert.equal(state.loading, false); assert.equal(state.required, null)
    pending[3].resolve(requirement(false)); await settle(); assert.equal(state.required, null)
    await state.load('new/bob', { ...target('app'), kind: 'application' }); assert.equal(state.required, true)
  } finally { globalThis.setTimeout = originalTimeout; state.clear() }
})

test('查询使用无缓存 GET 和编码标识，不携带表单、任职或写入幂等键', async () => {
  const originalFetch = globalThis.fetch, originalStorage = globalThis.localStorage, calls = []
  globalThis.localStorage = { getItem: () => 'synthetic' }
  globalThis.fetch = async (url, options) => { calls.push({ url, options }); return Response.json(requirement()) }
  try {
    await api.definitionInitiatorRequirements('d/1', new AbortController().signal)
    await api.applicationInitiatorRequirements('a/1', new AbortController().signal)
    assert.ok(calls[0].url.endsWith('/process-definitions/d%2F1/initiator-requirements'))
    assert.ok(calls[1].url.endsWith('/applications/a%2F1/initiator-requirements'))
    for (const { options } of calls) {
      assert.equal(options.body, undefined); assert.equal(options.method ?? 'GET', 'GET'); assert.equal(options.cache, 'no-store')
      assert.equal(new Headers(options.headers).has('Idempotency-Key'), false)
    }
  } finally { globalThis.fetch = originalFetch; globalThis.localStorage = originalStorage }
})

test('草稿、退回和撤回重提在缺少必需任职时不发送保存或提交；普通草稿仍可保存', async () => {
  const original = { ...api }, priorDocument = globalThis.document
  globalThis.document = { activeElement: null }
  try {
    for (const status of ['DRAFT', 'RETURNED', 'WITHDRAWN']) {
      let submits = 0, saves = 0
      api.application = async () => application(status)
      api.applicationRounds = async () => []
      api.applicationInitiatorRequirements = async () => requirement()
      api.updateApplication = async (_, body) => { saves++; return { ...application(status), title: body.title, version: 2 } }
      api.submitApplication = async () => { submits++; return { ...application('IN_APPROVAL'), roundNo: 1 } }
      const app = renderer.createApp({ ...Record, render: () => null }, { applicationId: 'app', userId: 'alice', scopeKey: 'demo/alice', commentRefreshVersion: 0, pendingWrites: [], recoveryError: '' })
      const state = app.mount({}).$.setupState
      try {
        await settle()
        state.title = '修改后的标题'
        await state.save(true)
        assert.equal(submits, 0, status)
        assert.equal(saves, 0, status)
        assert.match(state.error, /任职/)
        await state.save(false)
        assert.equal(saves, 1, status)
        state.initiatorAppointmentId = 'chosen'
        await state.save(true)
        assert.equal(submits, 1, status)
      } finally { app.unmount() }
    }
  } finally { Object.assign(api, original); globalThis.document = priorDocument }
})
