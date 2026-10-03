import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, reactive, ref } from 'vue'
import { renderToString } from 'vue/server-renderer'

const policy = await import(process.env.AGENTFLOW_TEST_RESPONSIBILITIES)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_RESPONSIBILITIESPANEL)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_RESPONSIBILITIESRENDERED)
const { createEditor } = await import(process.env.AGENTFLOW_TEST_RESPONSIBILITIES_EDITOR)
const { loadDesignerNodes, serializeDesignerNodes } = await import(process.env.AGENTFLOW_TEST_DESIGNER_GRAPH)
const { parsePortableTemplate, serializePortableTemplate } = await import(process.env.AGENTFLOW_TEST_PORTABLE)
const { comparisonValue, comparisonProperty } = await import(process.env.AGENTFLOW_TEST_COMPARISON)
const { simulationIssue } = await import(process.env.AGENTFLOW_TEST_SIMULATION)
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const node = (id, type = 'USER_TASK', properties = {}) => ({ id, name: '审批 ' + id, type, properties })
function graph(ids = ['start', 'first', 'review', 'end']) {
  return { nodes: ids.map(id => node(id, id === 'start' ? 'START' : id === 'end' ? 'END' : 'USER_TASK')),
    edges: ids.slice(1).map((id, i) => ({ id: 'e' + i, source: ids[i], target: id, condition: '', defaultBranch: false })) }
}
function panel(modelValue = {}, source = graph()) {
  const props = reactive({ modelValue, graph: source, nodeId: 'review', disabled: false }), events = []
  const app = renderer.createApp({ ...Panel, setup: (_, context) => Panel.setup(props, context), render: () => null }, {
    ...props, onBeforeChange: () => events.push('before'), 'onUpdate:modelValue': value => { events.push(value); props.modelValue = value }
  })
  return { props, events, state: app.mount({}).$.setupState, close: () => app.unmount() }
}
const event = value => ({ target: { value } })

test('读取、两种视图序列化和局部编辑保留未设置、显式关闭及非法原文，不改写其他配置', () => {
  for (const value of [{}, { excludeApplicant: 'false' }, { excludeApplicant: 'TRUE', differentApproverFrom: ' first,first' }]) {
    const properties = { assigneeRule: 'role:FINANCE', expenseStage: 'FINANCE', deadlineWorkingMinutes: '60', ...value }
    const original = [node('review', 'USER_TASK', properties)]
    assert.deepEqual(serializeDesignerNodes(loadDesignerNodes(original)), original)
    const edited = policy.writeResponsibilities(properties, { ...policy.readResponsibilities(properties), excludeApplicant: 'true' })
    assert.deepEqual(edited, { ...properties, excludeApplicant: 'true' })
    assert.deepEqual(properties, { assigneeRule: 'role:FINANCE', expenseStage: 'FINANCE', deadlineWorkingMinutes: '60', ...value })
    assert.notEqual(edited, properties)
    assert.deepEqual(policy.writeResponsibilities(properties, {}), { assigneeRule: 'role:FINANCE', expenseStage: 'FINANCE', deadlineWorkingMinutes: '60' })
  }
})

test('前序选择遵循有向流程，排除并行同级、后续、重复标识和回路，合流后可引用各前序分支', () => {
  const source = { nodes: [node('start', 'START'), node('first'), node('fork', 'PARALLEL_GATEWAY'), node('left'), node('right'), node('join', 'PARALLEL_GATEWAY'), node('review'), node('end', 'END')],
    edges: [['start', 'first'], ['first', 'fork'], ['fork', 'left'], ['fork', 'right'], ['left', 'join'], ['right', 'join'], ['join', 'review'], ['review', 'end']].map(([source, target]) => ({ source, target })) }
  assert.deepEqual(policy.priorApprovalNodes(source, 'left').map(n => n.id), ['first'])
  assert.deepEqual(policy.priorApprovalNodes(source, 'review').map(n => n.id), ['first', 'left', 'right'])
  source.nodes.push(node('first'))
  assert.deepEqual(policy.priorApprovalNodes(source, 'review').map(n => n.id), ['left', 'right'])
  source.edges.push({ source: 'review', target: 'fork' })
  assert.deepEqual(policy.priorApprovalNodes(source, 'review'), [])
})

test('真实组件每次明确编辑只保存一份撤销点，保留另一条规则并拒绝锁定和注入选项', () => {
  const p = panel({ differentApproverFrom: 'missing' })
  try {
    p.state.chooseApplicant(event('true'))
    assert.deepEqual(p.events, ['before', { excludeApplicant: 'true', differentApproverFrom: 'missing' }])
    p.state.chooseApplicant(event('true')); p.state.chooseApplicant(event('injected')); p.state.toggleReference('future', true)
    assert.equal(p.events.length, 2)
    p.state.toggleReference('first', true)
    assert.equal(p.props.modelValue.differentApproverFrom, 'missing,first')
    p.props.disabled = true
    p.state.chooseApplicant(event('false')); p.state.clearReferences(); p.state.toggleReference('missing', false)
    assert.equal(p.events.length, 4)
    p.props.disabled = false; p.state.toggleReference('missing', false)
    assert.deepEqual(p.props.modelValue, { excludeApplicant: 'true', differentApproverFrom: 'first' })
    p.state.chooseApplicant(event(''))
    assert.deepEqual(p.props.modelValue, { excludeApplicant: undefined, differentApproverFrom: 'first' })
  } finally { p.close() }
})

test('删除或移动前序节点保留原约束，恢复图结构后自动恢复可选项而不制造编辑', () => {
  const p = panel({ differentApproverFrom: 'first' })
  try {
    assert.equal(p.state.unavailable.length, 0)
    p.props.graph = graph(['start', 'review', 'first', 'end'])
    assert.deepEqual(p.state.unavailable.map(n => n.id), ['first'])
    p.props.graph = graph(['start', 'review', 'end'])
    assert.equal(p.state.unavailable[0].name, '步骤不存在')
    p.props.graph = graph()
    assert.equal(p.state.unavailable.length, 0)
    assert.equal(p.props.modelValue.differentApproverFrom, 'first')
    assert.deepEqual(p.events, [])
  } finally { p.close() }
})

test('非法原文不被静默修剪或去重，只能明确清除；Java 空白和非断行空格边界一致', () => {
  for (const raw of ['', 'first,first', 'first,', ' first', 'first\u3000', 'x'.repeat(129)]) {
    const p = panel({ excludeApplicant: 'TRUE', differentApproverFrom: raw })
    try {
      assert.ok(p.state.referenceIssue)
      p.state.toggleReference('first', true)
      assert.equal(p.props.modelValue.differentApproverFrom, raw)
      assert.deepEqual(p.events, [])
      p.state.clearReferences()
      assert.deepEqual(p.props.modelValue, { excludeApplicant: 'TRUE', differentApproverFrom: undefined })
    } finally { p.close() }
  }
  assert.equal(policy.responsibilityReferenceIssue('\u00a0first'), '')
})

test('最多选择二十项，达到上限后仍可移除缺失引用并选择替代步骤', () => {
  const ids = Array.from({ length: 21 }, (_, i) => 'step' + i)
  const p = panel({ differentApproverFrom: ids.slice(0, 20).join(',') }, graph(['start', ...ids, 'review', 'end']))
  try {
    p.state.toggleReference(ids[20], true)
    assert.deepEqual(p.events, [])
    p.props.graph.nodes = p.props.graph.nodes.filter(n => n.id !== ids[0])
    p.state.toggleReference(ids[0], false); p.state.toggleReference(ids[20], true)
    assert.equal(p.props.modelValue.differentApproverFrom, ids.slice(1).join(','))
    assert.equal(p.events.length, 4)
  } finally { p.close() }
})

test('真实父页面编辑、撤销和重做完整恢复快照，权限撤销后不能修改节点', () => {
  const source = graph()
  source.nodes[2].properties = { assigneeRule: 'role:FINANCE', excludeApplicant: 'false', differentApproverFrom: 'first', expenseStage: 'FINANCE' }
  const deps = { editorLocked: ref(false), canManageDefinitions: ref(true), history: ref([]), future: ref([]),
    definitionId: ref('saved'), definitionKey: ref('policy'), definitionName: ref('职责验收'), definitionRiskPolicy: ref(null),
    conditionLanguageVersion: ref(2), nodes: ref(loadDesignerNodes(source.nodes)), edges: ref(source.edges),
    definitionFormSchema: ref(null), definitionNotificationTexts: ref({ submitted: '', returned: '', approved: '已批准' }) }
  const editor = createEditor(deps), initial = editor.snapshot()
  editor.remember(); editor.patchResponsibilities('review', { excludeApplicant: 'true' })
  const edited = editor.snapshot()
  assert.deepEqual(serializeDesignerNodes(deps.nodes.value)[2].properties, { assigneeRule: 'role:FINANCE', excludeApplicant: 'true', expenseStage: 'FINANCE' })
  editor.undo(); assert.equal(editor.snapshot(), initial)
  editor.redo(); assert.equal(editor.snapshot(), edited)
  deps.editorLocked.value = true; editor.patchResponsibilities('review', {}); editor.undo()
  assert.equal(editor.snapshot(), edited)
  deps.editorLocked.value = false; deps.canManageDefinitions.value = false; editor.patchResponsibilities('review', {})
  assert.equal(editor.snapshot(), edited)
  deps.canManageDefinitions.value = true; editor.patchResponsibilities('start', {}); editor.patchResponsibilities('absent', {})
  assert.equal(editor.snapshot(), edited)
})

test('真实模板转义节点名称及非法原文，锁定时全部修改入口禁用', async () => {
  const source = graph(); source.nodes[1].name = '<img src=x onerror=alert(1)>'
  const html = await renderToString(createSSRApp(Rendered, { graph: source, nodeId: 'review', disabled: true,
    modelValue: { excludeApplicant: '<script>', differentApproverFrom: 'first,missing' } }))
  assert.ok(html.includes('&lt;img src=x onerror=alert(1)&gt;'))
  assert.ok(html.includes('&lt;script&gt;')); assert.ok(!html.includes('<script>'))
  assert.match(html, /select[^>]*disabled/); assert.match(html, /fieldset[^>]*disabled/)
  assert.match(html, /button[^>]*disabled[^>]*aria-label="移除引用 missing"/)
  assert.ok(html.includes('不再是前序人工审批'))
  const invalid = await renderToString(createSSRApp(Rendered, { graph: source, nodeId: 'review', disabled: false, modelValue: { differentApproverFrom: 'first,,first' } }))
  assert.ok(invalid.includes('first,,first')); assert.ok(invalid.includes('清除无效引用并重新选择'))
})

test('版本差异、模板核对和服务端错误使用职责含义，模板超出表示上限仍拒绝', () => {
  assert.equal(comparisonProperty('properties.differentApproverFrom'), '排除前序步骤批准人')
  assert.equal(comparisonValue(undefined, 'excludeApplicant'), '未设置')
  assert.equal(comparisonValue('false', 'properties.excludeApplicant'), '关闭')
  assert.equal(comparisonValue('bad', 'excludeApplicant'), '待修正：bad')
  assert.equal(comparisonValue('first,second', 'differentApproverFrom'), 'first、second')
  assert.match(policy.responsibilitySummary({ excludeApplicant: 'true', differentApproverFrom: 'first,missing' }, graph()).join(' '), /审批 first（first）.*missing（步骤不存在）/)
  const issue = simulationIssue('APPROVAL_RESPONSIBILITY_REFERENCE_INVALID:review')
  assert.equal(issue.target, 'review'); assert.match(issue.label, /前序/)
  const value = { key: 'policy', name: '职责验收', graph: graph(), formSchema: null }
  const json = JSON.parse(serializePortableTemplate(value))
  json.process.graph.nodes[2].properties.differentApproverFrom = 'x'.repeat(policy.MAX_RESPONSIBILITY_REFERENCE_TEXT + 1)
  assert.throws(() => parsePortableTemplate(JSON.stringify(json)))
})
