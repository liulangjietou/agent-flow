import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'

const { default: TaskActions } = await import(process.env.AGENTFLOW_TEST_TASK_PANEL)

// 使用真实 Vue 实例执行组件 setup 和同步 watcher；DOM、焦点和实际提交由浏览器验收。
const renderer = createRenderer({
  createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null
})
function panel(task = {}) {
  const events = []
  const props = reactive({ task: { taskId: 'review', version: 2, allowedActions: ['APPROVE', 'CLAIM', 'RELEASE', 'RETURN'], ...task }, scopeKey: 'demo:manager', locked: false })
  const app = renderer.createApp({
    ...TaskActions,
    setup: (_props, context) => TaskActions.setup(props, context),
    render: () => null
  }, { ...props, onExecute: input => events.push(input) })
  const mounted = app.mount({})
  return { state: mounted.$.setupState, props, events, close: () => app.unmount() }
}

const proxy = id => ({ proxyId: id, revision: 1, definitionId: 'published', principalId: id, principal: id,
  startsAt: new Date(Date.now() - 60_000).toISOString(), endsAt: new Date(Date.now() + 60_000).toISOString() })

test('代理批准须在确认表单固定原审批人，多个依据不能默认替人选择', () => {
  const p = panel({ canActDirectly: false, proxyOptions: [proxy('first'), proxy('second')] })
  try {
    p.state.prepare('APPROVE'); p.state.execute('APPROVE')
    assert.deepEqual(p.events, []); assert.match(p.state.error, /选择.*代理/)
    p.state.selectedProxy = 'second'; p.state.execute('APPROVE')
    assert.equal(p.events[0].proxyId, 'second'); assert.equal(p.events[0].expectedVersion, 2)
    p.state.cancelForm(); assert.equal(p.state.selectedProxy, '')
    p.props.task.proxyOptions = [proxy('first')]; p.state.prepare('APPROVE')
    assert.equal(p.state.selectedProxy, 'first'); p.state.execute('APPROVE')
    assert.equal(p.events[1].proxyId, 'first')
  } finally { p.close() }
})

test('同版本的代理撤销或权限变化清除确认，纯代理不显示增减会签入口', () => {
  const p = panel({ canActDirectly: false, proxyOptions: [proxy('first')], countersign: { mode: 'ALL', completed: 0, total: 2 } })
  try {
    assert.equal(p.state.membershipAllowed, false)
    p.state.prepare('APPROVE'); p.state.comment = '旧意见'
    p.props.task.proxyOptions = []
    assert.equal(p.state.pending, null); assert.equal(p.state.comment, ''); assert.equal(p.state.selectedProxy, '')
    p.props.task.proxyOptions = [proxy('first')]; p.props.task.canActDirectly = true
    assert.equal(p.state.membershipAllowed, true)
    p.state.prepare('APPROVE'); assert.equal(p.state.selectedProxy, '')
    p.props.task.canActDirectly = false
    assert.equal(p.state.pending, null); assert.deepEqual(p.events, [])
  } finally { p.close() }
})

test('批准先打开可取消的意见表单，明确确认后才发送原任务版本和意见', () => {
  const p = panel()
  try {
    p.state.prepare('APPROVE')
    assert.deepEqual(p.events, [], '首次点击不得直接批准')
    assert.equal(p.state.pending, 'APPROVE')
    p.state.comment = ' 已核对用途\n同意本节点通过。 '
    p.state.execute('APPROVE')
    assert.deepEqual(p.events, [{ action: 'APPROVE', expectedVersion: 2, comment: '已核对用途\n同意本节点通过。', targetUser: undefined }])
  } finally { p.close() }
})

test('取消批准不发送请求且清空意见，空意见仍可确认，领取与释放保持直接操作', () => {
  const p = panel()
  try {
    p.state.prepare('APPROVE'); p.state.comment = '取消的意见'; p.state.cancelForm()
    assert.equal(p.state.pending, null); assert.equal(p.state.comment, ''); assert.deepEqual(p.events, [])
    p.state.prepare('APPROVE'); p.state.comment = '  '; p.state.execute('APPROVE')
    assert.equal(p.events[0].comment, undefined)
    p.state.prepare('CLAIM'); p.state.prepare('RELEASE')
    assert.deepEqual(p.events.map(input => input.action), ['APPROVE', 'CLAIM', 'RELEASE'])
    assert.equal(p.state.pending, null)
  } finally { p.close() }
})

test('切换任务、账号或版本后清空批准表单，防止上一张单的意见串入', () => {
  const p = panel()
  try {
    for (const change of [() => p.props.task.taskId = 'other', () => p.props.task.version++, () => p.props.scopeKey = 'demo:finance']) {
      p.state.prepare('APPROVE'); p.state.comment = '旧意见'
      change()
      assert.equal(p.state.pending, null); assert.equal(p.state.comment, ''); assert.deepEqual(p.events, [])
    }
  } finally { p.close() }
})

test('提交锁阻止再次批准，会签和已回交任务沿用原允许动作与版本', () => {
  for (const task of [{ countersign: { completed: 1, total: 2 } }, { delegationState: 'RESOLVED', owner: 'manager' }]) {
    const p = panel(task)
    try {
      p.props.locked = true; p.state.prepare('APPROVE')
      assert.equal(p.state.pending, null)
      p.props.locked = false; p.state.prepare('APPROVE'); p.state.comment = '批准说明'
      p.props.locked = true; p.state.execute('APPROVE'); assert.deepEqual(p.events, [])
      p.props.locked = false; p.state.execute('APPROVE')
      assert.deepEqual(p.events, [{ action: 'APPROVE', expectedVersion: 2, comment: '批准说明', targetUser: undefined }])
    } finally { p.close() }
  }
  const delegated = panel({ delegationState: 'PENDING', allowedActions: ['RESOLVE'] })
  try {
    delegated.state.execute('APPROVE')
    assert.deepEqual(delegated.events, []); assert.match(delegated.state.error, /不允许/)
  } finally { delegated.close() }
})
