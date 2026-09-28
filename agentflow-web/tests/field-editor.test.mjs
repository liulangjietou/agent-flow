import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { default: Editor } = await import(process.env.AGENTFLOW_TEST_FIELD_EDITOR)
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function panel() {
  const events = [], props = reactive({ disabled: false, approvalNodes: [{ id: 'review', name: '审批' }], modelValue: { schemaVersion: 1, fields: [{ key: 'secret', label: '敏感内容', type: 'TEXT', required: false, sensitive: true, nodeAccess: { review: 'MASKED' } }] }, 'onUpdate:modelValue': value => events.push(value) })
  const app = renderer.createApp({ ...Editor, setup: (_, context) => Editor.setup(props, context), render: () => null }, props)
  const instance = app.mount({})
  return { state: instance.$.setupState, props, events, close: () => app.unmount() }
}
test('更换填写类型不应清除敏感标记和节点权限', () => {
  const p = panel()
  try {
    p.state.changeType(p.state.rows[0], { target: { value: 'TEXTAREA' } })
    assert.equal(p.events[0].fields[0].sensitive, true)
    assert.deepEqual(p.events[0].fields[0].nodeAccess, { review: 'MASKED' })
  } finally { p.close() }
})
test('权限配置保持原表单不可变、可清除失效节点且锁定时不写入', () => {
  const p = panel()
  try {
    p.state.permission(p.state.rows[0].field, 'review', { target: { value: 'HIDDEN' } })
    assert.equal(p.props.modelValue.fields[0].nodeAccess.review, 'MASKED')
    assert.equal(p.events[0].fields[0].nodeAccess.review, 'HIDDEN')
    p.state.rows[0].field.nodeAccess.removed = 'MASKED'
    assert.ok(p.state.permissionNodes(p.state.rows[0].field).some(value => value.id === 'removed'))
    p.state.permission(p.state.rows[0].field, 'removed', { target: { value: '' } })
    assert.equal(p.events.at(-1).fields[0].nodeAccess.removed, undefined)
    p.props.disabled = true; const count = p.events.length
    p.state.permission(p.state.rows[0].field, 'review', { target: { value: 'READ_ONLY' } })
    assert.equal(p.events.length, count)
  } finally { p.close() }
})
