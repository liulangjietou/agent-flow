import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { api, writeRequests, bindAuthenticationActor } = await import(process.env.AGENTFLOW_TEST_API)
const { readNotificationDelivery, readDeliveryPage, readDeliveryDetail, readDeliveryHistory, validateDeliveryRetryReceipt, DeliveryPageQuery } = await import(process.env.AGENTFLOW_TEST_NOTIFICATION_DELIVERIES)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_NOTIFICATION_DELIVERY_PANEL)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_NOTIFICATION_DELIVERY_RENDERED)
const { createRecovery } = await import(process.env.AGENTFLOW_TEST_TIMER_RECOVERY)
const id = '12345678-1234-1234-1234-123456789001', otherId = '12345678-1234-1234-1234-123456789002'
const when = '2026-10-01T12:00:00Z', originalApi = { ...api }, originalFetch = globalThis.fetch
const settle = () => new Promise(resolve => setImmediate(resolve))
globalThis.localStorage = { getItem: () => null }

test('企业 IM 失败分类可读取，未知投递继续要求重复确认', () => {
  for (const errorCode of ['IM_TOKEN_UNAVAILABLE', 'IM_AUTH_FAILED', 'IM_RECIPIENT_REJECTED', 'IM_TEMPORARY_REJECTION', 'IM_PERMANENT_REJECTION', 'IM_RESULT_UNKNOWN']) {
    const value = { ...row(errorCode === 'IM_RESULT_UNKNOWN' ? 'UNKNOWN' : 'FAILED'), channel: 'ENTERPRISE_IM', errorCode }
    assert.equal(readNotificationDelivery(value).errorCode, errorCode)
    const result = readDeliveryDetail(detail(value), id)
    assert.equal(result.retry.requiresDuplicateAcknowledgement, errorCode === 'IM_RESULT_UNKNOWN')
  }
})
function row(status = 'FAILED', version = 3, identity = id) {
  return { id: identity, inboxId: '12345678-1234-1234-1234-123456789099', channel: 'EMAIL', status, version, attempts: 1,
    cycleAttempts: status === 'PENDING' ? 0 : 1, errorCode: ['PENDING', 'IN_FLIGHT', 'ACCEPTED'].includes(status) ? null : status === 'UNKNOWN' ? 'SMTP_RESULT_UNKNOWN' : 'SMTP_PERMANENT_REJECTION',
    createdAt: when, updatedAt: when, nextAttemptAt: ['PENDING', 'RETRY_WAIT'].includes(status) ? when : null, leaseUntil: status === 'IN_FLIGHT' ? when : null }
}
function detail(value = row()) {
  return { delivery: value, retry: { allowed: ['FAILED', 'UNKNOWN'].includes(value.status), requiresDuplicateAcknowledgement: value.status === 'UNKNOWN', blockedCode: ['FAILED', 'UNKNOWN'].includes(value.status) ? null : 'STATE_NOT_RETRYABLE' },
    history: { items: [{ version: value.version, status: value.status, attempts: value.attempts, cycleAttempts: value.cycleAttempts, errorCode: value.errorCode, actor: null, reason: null, occurredAt: when }], nextCursor: null } }
}

test('拒绝缺字段、伪受理、混合身份详情和错误恢复回执', () => {
  assert.equal(readNotificationDelivery(row()).status, 'FAILED')
  for (const invalid of [{}, null, { ...row(), emailAddress: 'secret@example.invalid' }, { ...row(), status: 'DELIVERED' },
    { ...row(), nextAttemptAt: when }, { ...row('UNKNOWN'), attempts: 0 }, { ...row(), version: 1.5 }, { ...row('ACCEPTED'), errorCode: 'SMTP_RESULT_UNKNOWN' }])
    assert.throws(() => readNotificationDelivery(invalid), error => error.code === 'RESPONSE_UNREADABLE')
  assert.throws(() => readDeliveryPage({ items: [row(), row()], nextCursor: null }))
  assert.throws(() => readDeliveryPage({ items: [], nextCursor: 'bad' }))
  assert.throws(() => readDeliveryDetail(detail(), otherId))
  assert.throws(() => readDeliveryDetail({ ...detail(), retry: { ...detail().retry, requiresDuplicateAcknowledgement: true } }, id))
  assert.throws(() => readDeliveryHistory({ items: [detail().history.items[0], { ...detail().history.items[0], version: 4 }], nextCursor: null }))
  const input = { expectedVersion: 3, acknowledgePossibleDuplicate: false, reason: '核实' }
  assert.equal(validateDeliveryRetryReceipt(row('PENDING', 4), id, input).version, 4)
  for (const receipt of [{}, row('ACCEPTED', 4), row('PENDING', 5), row('PENDING', 4, otherId)]) assert.throws(() => validateDeliveryRetryReceipt(receipt, id, input))
})

test('无效成功响应保留原请求，恢复保持原键与原正文', async () => {
  bindAuthenticationActor({ tenantId: 'delivery-wire', userId: 'alice', roles: [] })
  const sent = []; let valid = false
  globalThis.fetch = async (path, options) => { sent.push({ path, body: options.body, key: options.headers.get('Idempotency-Key'), method: options.method }); return new Response(JSON.stringify(valid ? row('PENDING', 4) : {}), { status: 200, headers: { 'Content-Type': 'application/json' } }) }
  try {
    const input = { expectedVersion: 3, acknowledgePossibleDuplicate: true, reason: '接受可能重复后恢复' }
    await assert.rejects(api.retryNotificationDelivery(id, input), error => error.code === 'RESPONSE_UNREADABLE')
    assert.equal(writeRequests.pending().length, 1)
    await assert.rejects(api.retryNotificationDelivery(id, { ...input, reason: '不能替换原文' }), error => error.code === 'PENDING_REQUEST_CHANGED')
    valid = true; await writeRequests.recover(writeRequests.pending()[0].id)
    assert.equal(sent.length, 2); assert.match(sent[0].key, /^[0-9a-f-]{36}$/); assert.equal(sent[0].key, sent[1].key); assert.equal(sent[0].body, sent[1].body); assert.equal(sent[0].method, 'POST')
    assert.equal(writeRequests.pending().length, 0)
  } finally { globalThis.fetch = originalFetch; bindAuthenticationActor(null) }
})

test('分页失败保留原游标，重复游标和跨身份迟到响应不能污染列表', async () => {
  const reads = [], query = new DeliveryPageQuery((cursor, signal) => new Promise((resolve, reject) => reads.push({ cursor, signal, resolve, reject })), item => item.id)
  const first = query.load('alice'); reads[0].resolve({ items: [row()], nextCursor: 'next' }); await first
  const more = query.more(); const repeated = query.more(); assert.equal(reads.length, 2); await repeated
  reads[1].reject({ message: '暂时失败' }); await more; assert.equal(query.nextCursor, 'next'); assert.equal(query.items.length, 1)
  const corrupt = query.more(); reads[2].resolve({ items: [row('FAILED', 3, otherId)], nextCursor: 'next' }); await corrupt
  assert.equal(query.items.length, 1); assert.match(query.error, /不完整/)
  const old = query.more(), current = query.load('bob'); assert.equal(reads[3].signal.aborted, true)
  reads[4].resolve({ items: [], nextCursor: null }); await current
  reads[3].resolve({ items: [row('FAILED', 3, otherId)], nextCursor: null }); await old; assert.equal(query.items.length, 0)
})

test('真实组件仅在展开时查询，未知重试需要原因及两项确认，成功后重读当前状态', async () => {
  const p = panel(); let reads = 0, writes = 0, input
  api.notificationDeliveries = async () => { reads++; return { items: [row('UNKNOWN')], nextCursor: null } }
  api.notificationDelivery = async () => writes ? detail(row('ACCEPTED', 6)) : detail(row('UNKNOWN'))
  api.retryNotificationDelivery = async (_id, value) => { writes++; input = value; return row('PENDING', 4) }
  try {
    await settle(); assert.equal(reads, 0); p.state.toggle({ target: { open: true } }); await settle(); assert.equal(reads, 1)
    await p.state.inspect(id); await p.state.retry(); assert.equal(writes, 0)
    p.state.reason = '  核实后接受重复  '; p.state.confirmed = true; assert.equal(p.state.canRetry, false)
    p.state.acknowledgeDuplicate = true; assert.equal(p.state.canRetry, true)
    p.props.locked = true; await p.state.retry(); assert.equal(writes, 0); p.props.locked = false
    await p.state.retry(); assert.equal(writes, 1); assert.equal(input.reason, '核实后接受重复'); assert.equal(input.acknowledgePossibleDuplicate, true)
    assert.equal(p.state.detail.delivery.status, 'ACCEPTED'); assert.equal(p.state.canRetry, false); assert.match(p.state.notice, /重新读取/)
  } finally { p.close() }
})

test('未确认写入保留原因并禁止使用旧详情再次提交', async () => {
  const p = panel(); api.retryNotificationDelivery = async () => { throw { status: 0, message: '恢复原操作' } }
  try {
    await p.state.inspect(id); p.state.reason = '已核实'; p.state.confirmed = true
    await p.state.retry(); assert.equal(p.state.reason, '已核实'); assert.equal(p.state.stale, true); assert.equal(p.state.canRetry, false); assert.match(p.state.writeError, /恢复/)
    api.notificationDelivery = async () => detail(row('PENDING', 4)); p.props.refreshVersion++; await settle()
    assert.equal(p.state.detail, null)
  } finally { p.close() }
})

test('前一次重试的迟到列表刷新不能解除下一次写入的锁定', async () => {
  const p = panel(); let finishList, rejectWrite, writes = 0
  api.notificationDeliveries = () => new Promise(resolve => { finishList = resolve })
  api.notificationDelivery = async () => detail(row('FAILED', writes ? 6 : 3))
  api.retryNotificationDelivery = async () => {
    writes++
    if (writes === 1) return row('PENDING', 4)
    return new Promise((_resolve, reject) => { rejectWrite = reject })
  }
  try {
    await p.state.inspect(id); p.state.reason = '第一次核实'; p.state.confirmed = true
    const first = p.state.retry(); await settle()
    assert.equal(p.state.detail.delivery.version, 6)
    p.state.reason = '第二次核实'; p.state.confirmed = true
    const second = p.state.retry(); await settle(); assert.equal(writes, 2)
    finishList({ items: [row('FAILED', 6)], nextCursor: null }); await first
    assert.equal(p.state.sending, true)
    rejectWrite({ message: '第二次结果未知' }); await second
    assert.equal(p.state.sending, false); assert.equal(p.state.stale, true)
  } finally { rejectWrite?.({ message: '结束夹具' }); p.close() }
})

test('切换投递或账号隔离迟到详情和历史，读取超时保持不可写', async t => {
  const p = panel(), reads = []
  api.notificationDelivery = (_id, signal) => new Promise(resolve => reads.push({ resolve, signal }))
  try {
    const old = p.state.inspect(id), current = p.state.inspect(otherId); assert.equal(reads[0].signal.aborted, true)
    reads[1].resolve(detail(row('UNKNOWN', 3, otherId))); await current
    reads[0].resolve(detail()); await old; assert.equal(p.state.detail.delivery.id, otherId)
    const late = p.state.inspect(id); p.props.scopeKey = 'demo:bob'; assert.equal(reads[2].signal.aborted, true)
    reads[2].resolve(detail()); await late; assert.equal(p.state.detail, null); assert.equal(p.state.history.items.length, 0)
    t.mock.timers.enable({ apis: ['setTimeout'] }); const slow = p.state.inspect(id); t.mock.timers.tick(12_000); await slow
    assert.equal(p.state.loading, false); assert.equal(p.state.canRetry, false); assert.match(p.state.detailError, /超时/)
    reads[3].resolve(detail()); await settle(); assert.equal(p.state.detail, null)
  } finally { p.close() }
})

test('页面原请求恢复只确认原回执并刷新当前投递，不执行额外业务操作', async () => {
  const box = value => ({ value }), env = { pendingWrites: box([{ id: 'original', path: `/notifications/deliveries/${id}/retry` }]), draftScope: box('scope'),
    confirmReplaceDefinition: async (_label, action) => action(), busy: box(false), recoveryError: box(''), templateRefresh: box(3), notice: box(''),
    writeRequests: { recover: async () => ({ request: { path: `/notifications/deliveries/${id}/retry` }, result: row('PENDING', 4) }) },
    errorMessage: error => error.message, nextTick: async () => {}, refreshWorkspace: async () => {}, workspace: box(null) }
  const old = globalThis.document; globalThis.document = { querySelector: () => null }
  try { await createRecovery(env)('original'); assert.equal(env.templateRefresh.value, 4); assert.match(env.notice.value, /重新读取当前投递状态/); assert.equal(env.busy.value, false) }
  finally { globalThis.document = old }
})

test('实际模板区分受理与未知，未知恢复显示重复风险并默认禁止提交', async () => {
  for (const channel of ['EMAIL', 'ENTERPRISE_IM']) {
  const node = (tag, text = '') => ({ tag, tagName: tag.toUpperCase(), text, props: {}, children: [], parent: null, value: '', multiple: false,
    get options() { return this.children.filter(child => child.tag === 'option') }, addEventListener() {}, removeEventListener() {}, getRootNode: () => ({}), focus() {}, scrollIntoView() {} })
  const remove = el => { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1); el.parent = null }
  const host = createRenderer({ createElement: tag => node(tag), createText: text => node('#text', text), createComment: () => node('#comment'),
    setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] }, patchProp: (el, key, _old, value) => { el.props[key] = value; if (key === 'value') el.value = value }, remove,
    insert: (el, parent, anchor = null) => { remove(el); el.parent = parent; parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el) }, parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null })
  const all = el => [el, ...el.children.flatMap(all)], root = node('root'), oldDocument = globalThis.Document, oldShadow = globalThis.ShadowRoot
  globalThis.Document = class {}; globalThis.ShadowRoot = class {}
  const unknown = { ...row('UNKNOWN'), channel, errorCode: channel === 'ENTERPRISE_IM' ? 'IM_RESULT_UNKNOWN' : 'SMTP_RESULT_UNKNOWN' }
  api.notificationDeliveries = async () => ({ items: [unknown], nextCursor: null }); api.notificationDelivery = async () => detail(unknown)
  const app = host.createApp(Rendered, { scopeKey: 'demo:alice', refreshVersion: 0, locked: false })
  try {
    app.mount(root); await all(root).find(el => el.tag === 'details').props.onToggle({ target: { open: true } }); await settle()
    assert.match(all(root).map(el => el.text).join(' '), /不代表最终送达/)
    await all(root).find(el => el.tag === 'button' && el.text.includes('查看投递详情')).props.onClick(); await settle()
    assert.equal(all(root).filter(el => el.tag === 'input' && el.props.type === 'checkbox').length, 2)
    assert.match(all(root).map(el => el.text).join(' '), /接受重复提醒/); assert.equal(all(root).find(el => el.props.type === 'submit').props.disabled, true)
    if (channel === 'ENTERPRISE_IM') assert.match(all(root).map(el => el.text).join(' '), /企业 IM 发送中断或回执不完整/)
  } finally { app.unmount(); Object.assign(api, originalApi); globalThis.Document = oldDocument; globalThis.ShadowRoot = oldShadow }
  }
})

const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function panel() {
  api.notificationDeliveries = async () => ({ items: [], nextCursor: null }); api.notificationDelivery = async () => detail(); api.notificationDeliveryHistory = async () => ({ items: [], nextCursor: null })
  const props = reactive({ scopeKey: 'demo:alice', refreshVersion: 0, locked: false })
  const app = renderer.createApp({ ...Panel, setup: (_props, context) => Panel.setup(props, context), render: () => null }, props), mounted = app.mount({})
  return { state: mounted.$.setupState, props, close() { app.unmount(); Object.assign(api, originalApi) } }
}
