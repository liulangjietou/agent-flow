import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive, nextTick } from 'vue'
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_INITIATOR_PANEL)
const { api, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
async function flush() { for (let i = 0; i < 12; i++) await Promise.resolve(); await nextTick() }
function panel(scope = 'tenant/applicant') {
  const changes = [], props = reactive({ modelValue: '', scopeKey: scope, disabled: false, 'onUpdate:modelValue': value => changes.push(value) })
  const app = renderer.createApp({ ...Panel, setup: (_, context) => Panel.setup(props, context), render: () => null }, props)
  const instance = app.mount({})
  return { state: instance.$.setupState, props, changes, close: () => app.unmount() }
}
test('本人任职分页和账号切换不允许旧响应回填或代替用户选择', async () => {
  let release, oldSignal
  api.myAppointments = (_, signal) => { oldSignal = signal; return new Promise(resolve => { release = resolve }) }
  const p = panel()
  try {
    api.myAppointments = async after => after ? { items: [{ appointmentId: 'b2' }] } : { items: [{ appointmentId: 'b1' }], nextAfterId: 'b1' }
    p.props.scopeKey = 'tenant/other'; await flush()
    assert.equal(oldSignal.aborted, true)
    release({ items: [{ appointmentId: 'old' }] }); await flush()
    assert.deepEqual(p.state.choices.map(value => value.appointmentId), ['b1'])
    await p.state.load(true); assert.deepEqual(p.state.choices.map(value => value.appointmentId), ['b1', 'b2'])
    assert.ok(p.changes.every(value => value === ''))
    p.props.scopeKey = ''; await flush(); assert.deepEqual(p.state.choices, [])
  } finally { p.close() }
})
test('任职读取超时退出等待并可重新读取', async () => {
  const original = globalThis.setTimeout, timers = []; let signal
  globalThis.setTimeout = callback => { timers.push(callback); return 123 }
  api.myAppointments = (_, request) => new Promise((resolve, reject) => { signal = request; request.addEventListener('abort', () => reject(new Error('读取超时'))) })
  const p = panel()
  try {
    timers[0](); await flush()
    assert.equal(signal.aborted, true); assert.equal(p.state.loading, false); assert.match(p.state.error, /超时/)
    api.myAppointments = async () => ({ items: [{ appointmentId: 'recovered' }] })
    await p.state.load(); assert.equal(p.state.choices[0].appointmentId, 'recovered')
  } finally { globalThis.setTimeout = original; p.close() }
})
test('提交未知结果固定任职和原幂等请求', async () => {
  globalThis.localStorage = { getItem: () => 'synthetic' }
  writeRequests.setActor({ tenantId: 'tenant', userId: 'initiator' })
  const requests = []
  globalThis.fetch = async (url, init) => { requests.push({ url, ...init }); if (requests.length === 1) throw new TypeError('lost'); return Response.json({ version: 3 }) }
  await assert.rejects(api.submitApplication('application', 2, 'chosen-appointment'))
  await assert.rejects(api.submitApplication('application', 2, 'another-appointment'), error => error.code === 'PENDING_REQUEST_CHANGED')
  await writeRequests.recover(writeRequests.pending()[0].id)
  assert.deepEqual(JSON.parse(requests[0].body), { expectedVersion: 2, initiatorAppointmentId: 'chosen-appointment' })
  assert.equal(requests[1].body, requests[0].body)
  assert.equal(requests[1].headers.get('Idempotency-Key'), requests[0].headers.get('Idempotency-Key'))
})
