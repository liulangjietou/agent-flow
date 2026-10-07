import test from 'node:test'
import assert from 'node:assert/strict'
import { createSSRApp, ref } from 'vue'
import { renderToString } from 'vue/server-renderer'
const { render: taskRender } = await import(process.env.AGENTFLOW_TEST_TASKTABS)
const { render: recordRender } = await import(process.env.AGENTFLOW_TEST_RECORDTABS)
const { action } = await import(process.env.AGENTFLOW_TEST_ACTION_FOCUS)
const { default: WorkspaceTabs } = await import(process.env.AGENTFLOW_TEST_WORKSPACETABSRENDERED)
const child = { render: () => null }
async function html(render, data) {
  const app = createSSRApp({ setup: () => data, render })
  for (const name of ['RoundComparison', 'ApplicationHistory', 'AssistRunRecords', 'ApplicationComments', 'FormFields', 'ExpenseDetail', 'ExpensePlanDetail', 'AdvanceRequestDetail', 'ProcurementPaymentDetail', 'BudgetAdjustmentDetail', 'SubmissionRiskStatus', 'SubprocessRelations', 'RoundDiagram', 'ServiceTaskRuntimePanel', 'SignaturePanel']) app.component(name, child)
  app.component('WorkspaceTabs', WorkspaceTabs)
  return renderToString(app)
}
test('实际待办详情页签包含角色和选中面板关系', async () => {
  const value = await html(taskRender, { taskTab: 'timeline', activeTask: { taskId: 'task', applicationId: 'app', version: 1 }, activeApplication: null, actorScope: 'tenant:alice' })
  assert.match(value, /role="tablist"/); assert.match(value, /role="tab"/); assert.match(value, /aria-controls=/); assert.match(value, /role="tabpanel"/)
})
test('实际历史页签包含角色和选中面板关系', async () => {
  const value = await html(recordRender, { historyTab: 'timeline', signatureBusy: false, signatureDirty: false, application: { id: 'app', version: 1, roundNo: 1 } })
  assert.match(value, /role="tablist"/); assert.match(value, /role="tab"/); assert.match(value, /aria-selected="true"/); assert.match(value, /role="tabpanel"/)
})
function actionFixture() {
  let focusCount = 0
  const state = { activeTask: ref({ taskId: 'task' }), activeApplication: ref({ id: 'app' }), busy: ref(false), writesBlocked: ref(false), actorScope: ref('tenant:alice'), page: ref('workbench'), api: { taskAction: async () => ({ applicationStatus: 'APPROVED' }) }, refreshWorkspace: async () => {}, notice: ref(''), taskActionLabels: { APPROVE: '批准' }, statusLabel: s => s, errorMessage: e => e.message, nextTick: async () => {}, operationStatus: ref({ focus() { focusCount++ } }) }
  return { state, run: action(state), focusCount: () => focusCount }
}
test('审批完成卸载原操作后，焦点到结果提示', async () => {
  const p = actionFixture(); await p.run({ action: 'APPROVE' })
  assert.equal(p.state.activeTask.value, null); assert.match(p.state.notice.value, /批准已完成/); assert.equal(p.focusCount(), 1)
})

test('审批失败聚焦原任务结果，迟到回执不能清空新任务或覆盖新身份', async () => {
  const failed = actionFixture(); failed.state.api.taskAction = async () => { throw new Error('版本已变化，请刷新') }
  await failed.run({ action: 'APPROVE' }); assert.equal(failed.focusCount(), 1); assert.equal(failed.state.activeTask.value.taskId, 'task')
  for (const change of ['actor', 'task', 'page']) {
    const p = actionFixture(); let release
    p.state.api.taskAction = () => new Promise(resolve => { release = resolve })
    const pending = p.run({ action: 'APPROVE' })
    if (change === 'actor') p.state.actorScope.value = 'tenant:bob'
    if (change === 'task') p.state.activeTask.value = { taskId: 'new' }
    if (change === 'page') p.state.page.value = 'designer'
    release({ applicationStatus: 'APPROVED' }); await pending
    assert.equal(p.focusCount(), 0, change)
    if (change !== 'page') assert.equal(p.state.notice.value, '', change)
    if (change === 'task') assert.equal(p.state.activeTask.value.taskId, 'new')
  }
})

const { createRenderer, reactive, nextTick, h, onMounted, onUnmounted } = await import('vue')
const descendants = element => [element, ...(element.children ?? []).flatMap(descendants)]
function mountTabs() {
  let focus = null
  const element = tag => ({ tag, children: [], props: {}, parent: null, text: '', focus() { focus = this; this.props.onFocus?.() },
    querySelectorAll: () => descendants(root).filter(el => el.props.role === 'tab'), contains(node) { return descendants(this).includes(node) } })
  const remove = el => { if (el.parent) { const i = el.parent.children.indexOf(el); if (i >= 0) el.parent.children.splice(i, 1); el.parent = null } }
  const renderer = createRenderer({ createElement: element, createText: text => ({ ...element('#text'), text }), createComment: () => element('#comment'),
    insert(el, parent, anchor) { remove(el); el.parent = parent; const i = anchor ? parent.children.indexOf(anchor) : -1; parent.children.splice(i < 0 ? parent.children.length : i, 0, el) },
    remove, parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null,
    patchProp: (el, key, old, value) => { el.props[key] = value }, setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] } })
  const props = reactive({ idBase: 'sample', label: '详情', modelValue: 'a', tabs: [{ key: 'a', label: '甲' }, { key: 'b', label: '乙' }, { key: 'c', label: '丙' }], disabled: false })
  const mounted = [], unmounted = [], updates = []
  const Content = { props: ['value'], setup: value => { const key = value.value; onMounted(() => mounted.push(key)); onUnmounted(() => unmounted.push(key)); return () => h('p', '内容 ' + key) } }
  const root = element('root'), app = renderer.createApp({ setup: () => () => h(WorkspaceTabs, { ...props, 'onUpdate:modelValue': value => { updates.push(value); props.modelValue = value } }, { default: () => h(Content, { value: props.modelValue }) }) })
  app.mount(root)
  return { root, props, mounted, unmounted, updates, focus: () => focus, tabs: () => descendants(root).filter(el => el.props.role === 'tab'),
    panels: () => descendants(root).filter(el => el.props.role === 'tabpanel'), list: () => descendants(root).find(el => el.props.role === 'tablist'), close: () => app.unmount() }
}
function keyEvent(key, extra = {}) { return { key, prevented: false, preventDefault() { this.prevented = true }, ...extra } }
test('页签 ID 双向匹配，始终只有一个可 Tab 进入的标签和一个有内容的面板', async () => {
  const p = mountTabs()
  try {
    assert.equal(p.tabs().filter(el => el.props.tabindex === 0).length, 1)
    assert.equal(p.panels().filter(el => !el.props.hidden).length, 1)
    for (const tab of p.tabs()) {
      const panel = p.panels().find(el => el.props.id === tab.props['aria-controls'])
      assert.ok(panel); assert.equal(panel.props['aria-labelledby'], tab.props.id)
    }
    assert.deepEqual(p.mounted, ['a']); assert.equal(p.tabs()[0].props['aria-selected'], true)
    p.tabs()[1].props.onClick(); await nextTick()
    assert.deepEqual(p.mounted, ['a', 'b']); assert.deepEqual(p.unmounted, ['a'])
    assert.equal(p.tabs()[1].props['aria-selected'], true)
    assert.equal(p.panels().find(el => !el.props.hidden).props.id, 'sample-panel-b')
  } finally { p.close() }
})
test('方向键、Home、End 只移焦点，明确激活前不加载其他面板，离开后入口回到当前页签', async () => {
  const p = mountTabs()
  try {
    for (const [index, key, expected] of [[0, 'ArrowLeft', 2], [2, 'ArrowRight', 0], [0, 'End', 2], [2, 'Home', 0]]) {
      const event = keyEvent(key); await p.tabs()[index].props.onKeydown(event); await nextTick()
      assert.equal(event.prevented, true); assert.equal(p.focus().props.id, p.tabs()[expected].props.id)
      assert.deepEqual(p.updates, []); assert.deepEqual(p.mounted, ['a'])
    }
    await p.tabs()[0].props.onKeydown(keyEvent('ArrowRight')); await nextTick()
    p.list().props.onFocusout({ relatedTarget: null }); await nextTick()
    assert.equal(p.tabs()[0].props.tabindex, 0); assert.equal(p.tabs()[1].props.tabindex, -1)
    for (const event of [keyEvent('ArrowDown'), keyEvent('ArrowUp'), keyEvent('ArrowRight', { ctrlKey: true })]) {
      await p.tabs()[0].props.onKeydown(event); assert.equal(event.prevented, false)
    }
  } finally { p.close() }
})
test('电子签期间禁用全部切换，外部换页和身份前缀变更不会被迟到键盘焦点覆盖', async () => {
  const p = mountTabs()
  try {
    p.props.disabled = true; await nextTick(); p.tabs()[1].props.onClick()
    await p.tabs()[0].props.onKeydown(keyEvent('ArrowRight')); assert.deepEqual(p.updates, [])
    assert.ok(p.tabs().every(el => el.props.disabled && el.props.tabindex === -1))
    p.props.disabled = false; p.props.modelValue = 'c'; await nextTick(); assert.equal(p.tabs()[2].props.tabindex, 0)
    const pending = p.tabs()[2].props.onKeydown(keyEvent('Home')); p.props.idBase = 'other-application'; await pending; await nextTick()
    assert.equal(p.focus(), null)
    assert.ok(p.tabs().every(el => el.props.id.startsWith('other-application-')))
    assert.deepEqual(p.mounted, ['a', 'c'])
  } finally { p.close() }
})
