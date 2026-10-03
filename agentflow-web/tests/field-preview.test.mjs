import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_FIELD_PREVIEW)
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const originalApi = api.previewFields
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const masked = { schema: { schemaVersion: 1, fields: [{ key: 'secret', label: '敏感内容', type: 'TEXT', required: false }] }, payload: { secret: '已脱敏' }, restricted: true }
function panel() {
  const props = reactive({ schema: { schemaVersion: 1, fields: [{ key: 'secret', label: '敏感内容', type: 'TEXT', required: false, sensitive: true }] },
    values: { secret: '仅用于填写测试' }, approvalNodes: [{ id: 'review', name: '主管' }, { id: 'finance', name: '财务' }], scopeKey: 'tenant/admin/design', invalid: false })
  const app = renderer.createApp({ ...Panel, setup: (_, context) => Panel.setup(props, context), render: () => null }, props)
  const instance = app.mount({})
  return { state: instance.$.setupState, props, close: () => { app.unmount(); api.previewFields = originalApi } }
}
test('展示服务端投影，管理员不携带节点，多节点以完整选择提交', async () => {
  const p = panel(), calls = []
  try {
    api.previewFields = async (body) => { calls.push(JSON.parse(JSON.stringify(body))); return masked }
    await p.state.preview()
    assert.deepEqual(p.state.result, masked); assert.deepEqual(calls[0].nodeIds, [])
    assert.equal(p.props.values.secret, '仅用于填写测试')
    p.state.mode = 'nodes'; await p.state.preview(); assert.equal(calls.length, 1)
    p.state.selected = ['review', 'finance']; await p.state.preview()
    assert.deepEqual(calls[1].nodeIds, ['review', 'finance'])
  } finally { p.close() }
})
test('账号、配置或测试内容改变后，取消旧请求并拒绝迟到结果', async () => {
  for (const change of [p => { p.props.scopeKey = 'other/admin/design' }, p => { p.props.values.secret = '新内容' }, p => { p.props.schema.fields[0].sensitive = false }]) {
    const p = panel(); let release, signal
    try {
      api.previewFields = (_, current) => { signal = current; return new Promise(resolve => { release = resolve }) }
      const pending = p.state.preview(); change(p)
      assert.equal(signal.aborted, true); assert.equal(p.state.result, null)
      release(masked); await pending
      assert.equal(p.state.result, null); assert.equal(p.state.loading, false)
    } finally { p.close() }
  }
})
test('等值节点重绘保留请求，节点删除清理选择并使原预览失效', async () => {
  const p = panel(); let release, signal
  try {
    p.state.mode = 'nodes'; p.state.selected = ['review']
    api.previewFields = (_, current) => { signal = current; return new Promise(resolve => { release = resolve }) }
    const pending = p.state.preview()
    p.props.approvalNodes = p.props.approvalNodes.map(node => ({ ...node }))
    assert.equal(signal.aborted, false)
    release(masked); await pending; assert.ok(p.state.result)
    p.props.approvalNodes = [{ id: 'finance', name: '财务' }]
    assert.deepEqual(p.state.selected, []); assert.equal(p.state.result, null); assert.equal(p.state.canPreview, false)
  } finally { p.close() }
})
test('超时解除等待且迟到成功不能回填，失败可重试并清除旧结果', async () => {
  const p = panel(), originalTimer = globalThis.setTimeout, timers = []; let release, signal
  try {
    globalThis.setTimeout = (callback, delay) => { timers.push({ callback, delay }); return 123 }
    api.previewFields = (_, current) => { signal = current; return new Promise(resolve => { release = resolve }) }
    const pending = p.state.preview(); assert.equal(timers[0].delay, 12000); timers[0].callback()
    assert.equal(signal.aborted, true); assert.equal(p.state.loading, false); assert.match(p.state.error, /超时/)
    release(masked); await pending; assert.equal(p.state.result, null)
    api.previewFields = async () => masked; await p.state.preview(); assert.ok(p.state.result)
    api.previewFields = async () => { throw { status: 403, message: '没有预览权限' } }
    await p.state.preview(); assert.equal(p.state.result, null); assert.equal(p.state.error, '没有预览权限')
  } finally { globalThis.setTimeout = originalTimer; p.close() }
})
test('无身份或配置无效时不请求，卸载后不显示响应', async () => {
  const p = panel(); let calls = 0, release, signal
  try {
    api.previewFields = (_, current) => { calls++; signal = current; return new Promise(resolve => { release = resolve }) }
    p.props.scopeKey = ''; await p.state.preview()
    p.props.scopeKey = 'tenant/admin/design'; p.props.invalid = true; await p.state.preview()
    assert.equal(calls, 0)
    p.props.invalid = false; const pending = p.state.preview(); p.close()
    assert.equal(signal.aborted, true); release(masked); await pending; assert.equal(p.state.result, null)
  } finally { api.previewFields = originalApi }
})
