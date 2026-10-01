import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_ESCALATION_PANEL)
const { loadDesignerNodes, serializeDesignerNodes, readDesignerDeadline } = await import(process.env.AGENTFLOW_TEST_DESIGNER_GRAPH)
const { isTaskNotification, notificationLabels } = await import(process.env.AGENTFLOW_TEST_NOTIFICATIONS)
const originalApi = { ...api }, settle = () => new Promise(resolve => setImmediate(resolve))

test('两种视图保留升级原文，关闭升级与关闭整个期限分别清理对应属性', () => {
  const properties = { assigneeRule: 'user:manager', deadlineCalendarId: 'e7251050-b46b-40c3-9c4c-cc5d90f85688', deadlineCalendarRevision: '1', deadlineWorkingMinutes: '60', escalationWorkingMinutes: ' 30', escalationRecipientRule: 'role:ORG_SUPERVISOR_2' }
  const node = loadDesignerNodes([{ id: 'review', name: '审核', type: 'USER_TASK', properties }])[0]
  assert.deepEqual(node.deadline, readDesignerDeadline(properties))
  assert.deepEqual(serializeDesignerNodes([node])[0].properties, properties)
  delete node.deadline.escalationWorkingMinutes; delete node.deadline.escalationRecipientRule
  const noEscalation = serializeDesignerNodes([node])[0].properties
  assert.equal(noEscalation.deadlineWorkingMinutes, '60'); assert.equal(noEscalation.escalationWorkingMinutes, undefined); assert.equal(noEscalation.escalationRecipientRule, undefined)
  node.deadline = undefined
  assert.deepEqual(serializeDesignerNodes([node])[0].properties, { assigneeRule: 'user:manager' })
  assert.equal(properties.escalationWorkingMinutes, ' 30')
})

test('升级消息不会被当作当前收件人的待办', () => {
  assert.equal(isTaskNotification({ kind: 'TASK_ESCALATED' }), false)
  assert.equal(notificationLabels.TASK_ESCALATED, '审批超时升级提醒')
})

test('真实组件显式启用后查询目录，输入和选择沿原撤销边界同步', async () => {
  let reads = 0
  api.definitionCopyRecipients = async () => { reads++; return [{ rule: 'user:finance', label: '财务协调', memberCount: 1 }] }
  const p = panel()
  try {
    await settle(); assert.equal(reads, 0)
    p.state.toggle({ target: { checked: true } }); await settle(); assert.equal(reads, 1)
    assert.deepEqual(p.props.modelValue, { workingMinutes: '', recipientRule: '' })
    p.state.rememberMinutes(); p.state.changeMinutes({ target: { value: '30' } }); p.state.changeMinutes({ target: { value: '35' } })
    p.state.choose({ target: { value: 'user:finance' } })
    assert.deepEqual(p.props.modelValue, { workingMinutes: '35', recipientRule: 'user:finance' }); assert.equal(p.events.filter(e => e === 'beforeChange').length, 3)
    p.state.choose({ target: { value: 'user:injected' } }); assert.equal(p.props.modelValue.recipientRule, 'user:finance')
    p.props.disabled = true; p.state.toggle({ target: { checked: false } }); p.state.changeMinutes({ target: { value: '999' } }); p.state.choose({ target: { value: '' } })
    assert.deepEqual(p.props.modelValue, { workingMinutes: '35', recipientRule: 'user:finance' })
    p.props.disabled = false; p.state.toggle({ target: { checked: false } }); assert.equal(p.props.modelValue, undefined); assert.equal(p.state.query.options.length, 0)
  } finally { p.close() }
})

test('身份切换和迟到目录不会覆盖已有升级规则，当前读取失败保持不可选择', async () => {
  const pending = []; api.definitionCopyRecipients = signal => new Promise((resolve, reject) => pending.push({ signal, resolve, reject }))
  const p = panel({ workingMinutes: '30', recipientRule: 'user:old' })
  try {
    assert.equal(pending.length, 1); p.props.scopeKey = 'other:admin'; assert.equal(pending[0].signal.aborted, true)
    pending[1].reject({ message: '当前目录不可用' }); await settle()
    pending[0].resolve([{ rule: 'user:old', label: '旧目录', memberCount: 1 }]); await settle()
    assert.equal(p.state.query.options.length, 0); assert.match(p.state.query.error, /当前目录不可用/)
    p.state.choose({ target: { value: '' } }); assert.deepEqual(p.props.modelValue, { workingMinutes: '30', recipientRule: 'user:old' })
  } finally { p.close() }
})

const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function panel(modelValue) {
  const props = reactive({ modelValue, scopeKey: 'demo:admin:review', disabled: false }), events = []
  const app = renderer.createApp({ ...Panel, setup: (_props, context) => Panel.setup(props, context), render: () => null }, {
    ...props, onBeforeChange: () => events.push('beforeChange'), 'onUpdate:modelValue': value => { events.push('update'); props.modelValue = value }
  })
  const mounted = app.mount({})
  return { props, events, state: mounted.$.setupState, close() { app.unmount(); Object.assign(api, originalApi) } }
}
