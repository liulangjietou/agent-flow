import test from 'node:test'
import assert from 'node:assert/strict'
const { api, writeRequests, bindAuthenticationActor } = await import(process.env.AGENTFLOW_TEST_API)
const originalFetch = globalThis.fetch
globalThis.localStorage = { getItem: () => 'synthetic-comment-token' }

test('评论提醒的空壳或错名单成功回执保留原键，不得误报已经通知', async () => {
  const sent = { content: '原评论', expectedVersion: 2, mentions: ['finance'] }
  const valid = { id: 'comment', applicationId: 'app', author: 'alice', content: '原评论', applicationVersion: 2, roundNo: 1, applicationStatus: 'IN_APPROVAL', createdAt: '2026-10-01T12:00:00Z', mentions: ['finance'] }
  for (const [index, invalid] of [{}, { ...valid, mentions: [] }, { ...valid, applicationId: 'other' }, { ...valid, applicationVersion: 3 }, { ...valid, content: 'other' }].entries()) {
    bindAuthenticationActor({ tenantId: 'demo', userId: 'alice' + index }); const calls = []
    try {
      globalThis.fetch = async (_url, init) => { calls.push(init); return Response.json(calls.length === 1 ? invalid : valid, { status: 201 }) }
      await assert.rejects(api.addApplicationComment('app', sent), e => e.code === 'RESPONSE_UNREADABLE')
      assert.equal(writeRequests.pending().length, 1)
      await writeRequests.recover(writeRequests.pending()[0].id)
      assert.equal(calls[0].body, calls[1].body)
      assert.equal(calls[0].headers.get('Idempotency-Key'), calls[1].headers.get('Idempotency-Key'))
    } finally { globalThis.fetch = originalFetch; bindAuthenticationActor(null) }
  }
})

const { CommentMentionQuery, readCommentMentionPage } = await import(process.env.AGENTFLOW_TEST_COMMENT_MENTIONS)
const { CommentDrafts, commentDrafts } = await import(process.env.AGENTFLOW_TEST_COMMENTS)
const { createRenderer, reactive } = await import('vue')
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_COMMENT_PANEL)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_COMMENT_RENDERED)
const page = (items, extra = {}) => ({ applicationId: 'app', roundNo: 1, applicationVersion: 2, items, ...extra })
const originalApi = { ...api }, settle = () => new Promise(resolve => setImmediate(resolve))

test('提醒草稿复制名单，原回执不清除不同选择，明确更新上下文清掉旧名单', () => {
  const drafts = new CommentDrafts(), users = ['finance']
  drafts.put('demo:alice', 'app', '正文', 2, users); users.push('injected')
  drafts.get('demo:alice', 'app').mentions.push('outside')
  assert.deepEqual(drafts.get('demo:alice', 'app').mentions, ['finance'])
  assert.equal(drafts.get('demo:bob', 'app'), null)
  drafts.acknowledge('demo:alice', 'app', { content: '正文', expectedVersion: 2, mentions: ['manager'] })
  assert.ok(drafts.get('demo:alice', 'app'))
  drafts.adopt('demo:alice', 'app', 3)
  assert.deepEqual(drafts.get('demo:alice', 'app'), { content: '正文', expectedVersion: 3 })
})

test('分页与不同搜索保留本次已核对选择，同一搜索刷新撤销旧资格', async () => {
  const calls = []; let refresh = false
  const query = new CommentMentionQuery(async (_id, filter) => {
    calls.push(filter)
    if (filter.q === 'manager') return page(['manager'])
    if (refresh) return page([])
    return filter.afterUser ? page(['manager']) : page(['finance'], { nextAfter: 'finance' })
  })
  await query.load('alice', 'app', 2)
  await query.load('alice', 'app', 2, 'other', true); assert.equal(calls.length, 1)
  await query.load('alice', 'app', 2, '', true)
  assert.deepEqual(query.items, ['finance', 'manager']); assert.equal(calls[1].afterUser, 'finance')
  await query.load('alice', 'app', 2, 'manager'); assert.ok(query.verified.has('finance'))
  refresh = true; await query.load('alice', 'app', 2, ''); await query.load('alice', 'app', 2, '')
  assert.equal(query.verified.size, 0)
})

test('版本变化或失权清空选人资格，旧账号迟到读取不能回填', async () => {
  const pending = [], query = new CommentMentionQuery((_id, _query, signal) => new Promise((resolve, reject) => pending.push({ signal, resolve, reject })))
  const old = query.load('alice', 'app', 2), current = query.load('bob', 'app', 2)
  assert.equal(pending[0].signal.aborted, true)
  pending[1].resolve(page(['alice'])); await current
  pending[0].resolve(page(['secret'])); await old
  assert.deepEqual(query.items, ['alice']); assert.equal(query.verified.has('secret'), false)
  const stale = query.load('bob', 'app', 2); pending[2].resolve(page(['finance'], { applicationVersion: 3 })); await stale
  assert.equal(query.applicationVersion, null); assert.equal(query.verified.size, 0); assert.match(query.error, /申请已更新/)
  const failed = query.load('bob', 'app', 2); pending[3].reject({ status: 404, message: '不可见' }); await failed
  assert.deepEqual(query.items, []); assert.equal(query.verified.size, 0)
})

test('提醒目录读取超时结束等待，迟到结果和退出不恢复资格', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let finish, signal
  const query = new CommentMentionQuery((_id, _filter, current) => { signal = current; return new Promise(resolve => { finish = resolve }) })
  const loading = query.load('alice', 'app', 2); t.mock.timers.tick(12_000); await loading
  assert.equal(signal.aborted, true); assert.equal(query.loading, false); assert.match(query.error, /超时/)
  finish(page(['finance'])); await Promise.resolve(); assert.equal(query.verified.size, 0)
  query.clear(); assert.equal(query.error, '')
})

test('真实选人 API 编码查询且不带幂等写入键，拒绝乱序重复串申请与非法分页回执', async () => {
  const calls = [], controller = new AbortController()
  try {
    globalThis.fetch = async (url, init) => { calls.push({ url, ...init }); return Response.json(page(['a+/='], { applicationId: 'app/id' })) }
    await api.commentMentionOptions('app/id', { q: 'a+/=', afterUser: 'a', limit: 2 }, controller.signal)
    assert.match(calls[0].url, /app%2Fid\/comments\/mention-options/)
    assert.equal(new URL(calls[0].url, 'http://local').searchParams.get('q'), 'a+/=')
    assert.equal(calls[0].headers.has('Idempotency-Key'), false); assert.equal(calls[0].signal, controller.signal)
    for (const invalid of [page(['b', 'a']), page(['a', 'a']), page(['a'], { applicationId: 'other' }), page(['a'], { nextAfter: 'unknown' }), page(['a'], { applicationVersion: 0 })]) {
      assert.throws(() => readCommentMentionPage(invalid, 'app', {}))
    }
  } finally { globalThis.fetch = originalFetch }
})

const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function panel() {
  const props = reactive({ applicationId: 'app', scopeKey: 'demo:alice', version: 2, roundNo: 1, status: 'IN_APPROVAL', locked: false, refreshVersion: 0 }), events = []
  commentDrafts.discard('demo:alice', 'app'); commentDrafts.discard('demo:bob', 'app')
  api.applicationComments = async () => ({ items: [] }); api.commentMentionOptions = async () => page(['finance'])
  const app = renderer.createApp({ ...Panel, setup: (_props, context) => Panel.setup(props, context), render: () => null },
    { ...props, onPosted: () => events.push('posted') })
  const mounted = app.mount({})
  return { state: mounted.$.setupState, props, events, close() { app.unmount(); Object.assign(api, originalApi); commentDrafts.discard('demo:alice', 'app'); commentDrafts.discard('demo:bob', 'app') } }
}

test('实际组件选人只读取，伪造选择和写锁不发送，明确提交固定正文与名单', async () => {
  const p = panel(), sent = []
  api.addApplicationComment = async (id, body) => { sent.push({ id, body }) }
  try {
    p.state.edit({ target: { value: ' 正文 @bob ' } })
    p.state.selectMention('bob', true); assert.deepEqual(p.state.mentions, [])
    await p.state.loadMentions(); p.state.selectMention('finance', true); assert.equal(sent.length, 0)
    p.props.locked = true; await p.state.submit(); assert.equal(sent.length, 0)
    p.props.locked = false; await p.state.submit()
    assert.deepEqual(sent, [{ id: 'app', body: { content: '正文 @bob', expectedVersion: 2, mentions: ['finance'] } }])
    assert.equal(p.state.content, ''); assert.deepEqual(p.state.mentions, []); assert.deepEqual(p.events, ['posted'])
  } finally { p.close() }
})

test('实际组件保留未知结果原草稿与名单，明确换版本只保留正文', async () => {
  const p = panel()
  api.addApplicationComment = async () => { throw { status: 0, code: 'RESPONSE_UNREADABLE', message: '恢复原操作' } }
  try {
    p.state.edit({ target: { value: '原正文' } }); await p.state.loadMentions(); p.state.selectMention('finance', true)
    await p.state.submit(); assert.deepEqual(p.state.mentions, ['finance']); assert.equal(p.state.content, '原正文'); assert.deepEqual(p.events, [])
    p.props.version = 3; assert.equal(p.state.staleDraft, true); assert.equal(p.state.mentionsVerified, false)
    p.state.adopt(); assert.deepEqual(p.state.mentions, []); assert.equal(p.state.expectedVersion, 3); assert.equal(p.state.content, '原正文')
    p.props.scopeKey = 'demo:bob'; assert.deepEqual(p.state.mentions, []); assert.equal(p.state.content, '')
  } finally { p.close() }
})

test('实际组件停用接收人失败后撤销资格，重新核对前禁止再次发送', async () => {
  const p = panel(); let writes = 0
  api.addApplicationComment = async () => { writes++; throw { status: 409, code: 'COMMENT_MENTION_UNAVAILABLE', message: '人员不可用' } }
  try {
    p.state.edit({ target: { value: '正文' } }); await p.state.loadMentions(); p.state.selectMention('finance', true)
    await p.state.submit(); await p.state.submit()
    assert.equal(writes, 1); assert.equal(p.state.mentionsVerified, false); assert.deepEqual(p.state.mentions, ['finance'])
  } finally { p.close() }
})

test('实际模板通过复选框明确提醒，普通 @ 正文不代替选择，不嵌套表单', async () => {
  const node = (tag, text = '') => ({ tag, text, props: {}, children: [], parent: null, value: '', addEventListener() {}, removeEventListener() {}, getRootNode: () => ({}) })
  const remove = el => { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1); el.parent = null }
  const host = createRenderer({ createElement: tag => node(tag), createText: text => node('#text', text), createComment: () => node('#comment'),
    setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] },
    patchProp: (el, key, _old, value) => { el.props[key] = value; if (key === 'value') el.value = value }, remove,
    insert: (el, parent, anchor = null) => { remove(el); el.parent = parent; parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el) },
    parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null })
  const all = el => [el, ...el.children.flatMap(all)], root = node('root'), sent = []
  const previousDocument = globalThis.Document, previousShadowRoot = globalThis.ShadowRoot
  globalThis.Document = class {}; globalThis.ShadowRoot = class {}
  commentDrafts.discard('demo:alice', 'app'); api.applicationComments = async () => ({ items: [] })
  api.commentMentionOptions = async () => page(['finance']); api.addApplicationComment = async (id, body) => sent.push({ id, body })
  const app = host.createApp(Rendered, { applicationId: 'app', scopeKey: 'demo:alice', version: 2, roundNo: 1, status: 'IN_APPROVAL', locked: false, refreshVersion: 0 })
  try {
    app.mount(root)
    all(root).find(el => el.tag === 'textarea').props.onInput({ target: { value: '请核对 @bob' } }); await settle()
    await all(root).find(el => el.tag === 'button' && el.text.includes('选择或核对')).props.onClick(); await settle()
    assert.equal(sent.length, 0)
    all(root).find(el => el.props.type === 'checkbox').props.onChange({ target: { checked: true } }); await settle()
    for (const form of all(root).filter(el => el.tag === 'form')) assert.equal(all(form).filter(el => el.tag === 'form').length, 1)
    await all(root).find(el => el.tag === 'form').props.onSubmit({ preventDefault() {} }); await settle()
    assert.deepEqual(sent, [{ id: 'app', body: { content: '请核对 @bob', expectedVersion: 2, mentions: ['finance'] } }])
    assert.ok(all(root).some(el => el.props.role === 'status' && el.text.includes('评论与提醒已保存')))
  } finally { app.unmount(); Object.assign(api, originalApi); globalThis.Document = previousDocument; globalThis.ShadowRoot = previousShadowRoot; commentDrafts.discard('demo:alice', 'app') }
})
