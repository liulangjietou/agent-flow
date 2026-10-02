import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, reactive, nextTick } from 'vue'
import { renderToString } from 'vue/server-renderer'

const helper = await import(process.env.AGENTFLOW_TEST_FORM_ASSIGNEES)
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { assigneeLabel } = await import(process.env.AGENTFLOW_TEST_ASSIGNEES)
const { default: Assignee } = await import(process.env.AGENTFLOW_TEST_DEFINITIONASSIGNEEFIELDPANEL)
const { default: AssigneeRendered } = await import(process.env.AGENTFLOW_TEST_DEFINITIONASSIGNEEFIELDRENDERED)
const { default: Options } = await import(process.env.AGENTFLOW_TEST_ORGANIZATIONFORMOPTIONSFIELDPANEL)
const { default: Editor } = await import(process.env.AGENTFLOW_TEST_FORMSCHEMAEDITORFIELDPANEL)
const { PortableTemplateReview, serializePortableTemplate, parsePortableTemplate } = await import(process.env.AGENTFLOW_TEST_PORTABLE)
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const event = value => ({ target: { value } })
const field = (key = 'person', changes = {}) => ({ key, label: '经办人', type: 'SELECT', required: true, options: [{ value: '2e705768-c327-4494-bbde-6c7fb44ac015', label: '选定人员' }], ...changes })
const schema = () => ({ schemaVersion: 1, fields: [field()] })
function mount(component, values) {
  const props = reactive(values), events = []
  const app = renderer.createApp({ ...component, setup: (_, context) => component.setup(props, context), render: () => null }, {
    ...props, onBeforeChange: () => events.push('before'), 'onUpdate:modelValue': value => { events.push(value); props.modelValue = value },
    onAdd: value => { events.push(value); props.existing.push(value) }
  })
  return { props, events, state: app.mount({}).$.setupState, close: () => app.unmount() }
}

test('字段来源只允许顶层必填单选，未知规则原文不被隐式修复', () => {
  const form = schema()
  form.fields.push(field('optional', { required: false }), field('text', { type: 'TEXT' }), { key: 'table', type: 'TABLE', columns: [field('nested')] })
  assert.deepEqual(helper.formAssigneeFields(form).map(value => value.key), ['person'])
  assert.deepEqual(helper.formAssigneeFields(null), [])
  assert.deepEqual(helper.formAssigneeParts('field:missing:bad:relation'), { fieldKey: 'missing', relation: 'bad:relation' })
  assert.equal(assigneeLabel('field:person:DEPARTMENT_HEAD'), '表单选人 · person · 所选部门负责人')
})

test('真实节点组件明确选择字段及关系，保留失效字段并拒绝锁定或注入选项', () => {
  const panel = mount(Assignee, { modelValue: 'role:FINANCE', formSchema: schema(), scopeKey: '', disabled: false })
  try {
    panel.state.chooseSource(event('field'))
    assert.equal(panel.props.modelValue, 'field::PERSON')
    panel.state.chooseField(event('person')); panel.state.chooseRelation(event('DEPARTMENT_HEAD'))
    assert.equal(panel.props.modelValue, 'field:person:DEPARTMENT_HEAD')
    const saved = [...panel.events]
    panel.state.chooseField(event('injected')); panel.state.chooseRelation(event('${admin}'))
    panel.props.formSchema = null
    assert.equal(panel.state.fields.length, 0)
    assert.equal(panel.props.modelValue, 'field:person:DEPARTMENT_HEAD')
    panel.props.disabled = true
    panel.state.chooseSource(event('directory')); panel.state.chooseRelation(event('PERSON'))
    assert.deepEqual(panel.events, saved)
    panel.props.disabled = false; panel.state.chooseSource(event('directory'))
    assert.equal(panel.props.modelValue, '')
  } finally { panel.close() }
})

test('真实模板转义已有非法原文，清楚区分本轮固定名单和节点职责约束', async () => {
  const html = await renderToString(createSSRApp(AssigneeRendered, { modelValue: 'field:<script>:<img>', formSchema: null, scopeKey: '', disabled: true, approvalMode: 'ALL' }))
  assert.ok(html.includes('&lt;script&gt;')); assert.ok(html.includes('&lt;img&gt;'))
  assert.ok(!html.includes('<script>')); assert.ok(html.includes('提交时根据所选组织固定本轮名单'))
  assert.ok(html.includes('进入节点后应用职责分离约束'))
  assert.match(html, /select[^>]*disabled/)
  assert.ok(!html.includes('已有配置当前匹配不到审批人'))
})

test('组织选择组件只添加当前名单，阻止重复、旧账号迟到响应和上限外写入', async () => {
  const original = api.formAssigneeOptions, pending = []
  api.formAssigneeOptions = signal => new Promise((resolve, reject) => pending.push({ signal, resolve, reject }))
  const panel = mount(Options, { scopeKey: 'tenant:a', disabled: false, existing: [] })
  const person = { id: 'person-a', label: '甲', kind: 'PERSON', memberCount: 1, headAvailable: false }
  try {
    panel.state.open(); assert.equal(pending.length, 1)
    pending[0].resolve([person]); await new Promise(resolve => setImmediate(resolve))
    panel.state.add(event('forged')); assert.equal(panel.events.length, 0)
    panel.state.add(event(person.id)); panel.state.add(event(person.id))
    assert.deepEqual(panel.events, [{ value: person.id, label: person.label }])
    panel.state.open(); panel.props.scopeKey = 'tenant:b'
    assert.equal(pending[1].signal.aborted, true)
    pending[1].resolve([person]); await new Promise(resolve => setImmediate(resolve))
    assert.deepEqual(panel.state.query.options, []); assert.equal(panel.state.opened, false)
    panel.state.open(); pending[2].resolve([{ ...person, id: 'person-b' }]); await new Promise(resolve => setImmediate(resolve))
    panel.props.existing = Array.from({ length: 50 }, (_, i) => ({ value: String(i), label: String(i) }))
    panel.state.add(event('person-b')); assert.equal(panel.events.length, 1)
    panel.props.existing = []; panel.props.disabled = true
    panel.state.add(event('person-b')); assert.equal(panel.events.length, 1)
  } finally { panel.close(); api.formAssigneeOptions = original }
})

test('组织目录失败不丢失表单已有选项，重试读取最新目录', async () => {
  const original = api.formAssigneeOptions; let count = 0
  api.formAssigneeOptions = async () => { if (++count === 1) throw new Error('目录暂不可用'); return [] }
  const panel = mount(Options, { scopeKey: 'tenant', disabled: false, existing: [{ value: 'saved', label: '原选项' }] })
  try {
    panel.state.open(); await new Promise(resolve => setImmediate(resolve))
    assert.equal(panel.state.query.error, '目录暂不可用'); assert.equal(panel.state.query.loaded, false)
    assert.deepEqual(panel.props.existing, [{ value: 'saved', label: '原选项' }])
    panel.state.open(); await new Promise(resolve => setImmediate(resolve))
    assert.equal(panel.state.query.loaded, true); assert.equal(panel.state.query.error, '')
    assert.deepEqual(panel.events, [])
  } finally { panel.close(); api.formAssigneeOptions = original }
})

test('实际表单编辑器加入组织选项只变更目标字段，保存独立副本并保留撤销点', async () => {
  const source = schema(); source.fields[0].sensitive = true
  const panel = mount(Editor, { modelValue: source, scopeKey: 'tenant', disabled: false })
  try {
    const option = { value: 'p2', label: '乙' }
    panel.state.addOrganizationOption(panel.state.rows[0].field, option)
    await nextTick()
    assert.equal(panel.events[0], 'before'); assert.equal(panel.events.length, 2)
    assert.equal(panel.props.modelValue.fields[0].sensitive, true)
    assert.equal(source.fields[0].options.length, 1)
    option.label = '后来修改'
    assert.equal(panel.props.modelValue.fields[0].options[1].label, '乙')
    panel.state.addOrganizationOption(panel.state.rows[0].field, option)
    panel.props.disabled = true; panel.state.addOrganizationOption(panel.state.rows[0].field, { value: 'p3', label: '丙' })
    assert.equal(panel.events.length, 2)
  } finally { panel.close() }
})

test('模板保留表单关系及选项，来源失效允许导入修复但结构错误仍阻止', async () => {
  const source = { key: 'field-process', name: '表单流程', formSchema: schema(), graph: { nodes: [
    { id: 'start', name: '开始', type: 'START', properties: {} }, { id: 'review', name: '审批', type: 'USER_TASK', properties: { assigneeRule: 'field:person:PERSON' } }, { id: 'end', name: '结束', type: 'END', properties: {} }
  ], edges: [{ id: 'a', source: 'start', target: 'review', condition: '', defaultBranch: false }, { id: 'b', source: 'review', target: 'end', condition: '', defaultBranch: false }] } }
  const text = serializePortableTemplate(source)
  assert.deepEqual(parsePortableTemplate(text), source)
  let errors = ['FORM_ASSIGNEE_OPTION_UNAVAILABLE:review']
  const review = new PortableTemplateReview(async () => ({ errors }))
  await review.read({ size: Buffer.byteLength(text), text: async () => text }); await review.check()
  assert.equal(review.canImport, true)
  errors = ['FORM_ASSIGNEE_FIELD_REQUIRED:review']; await review.check()
  assert.equal(review.canImport, false)
})

test('组织来源接口携带认证、禁止缓存及取消信号，不创建写入幂等键', async () => {
  globalThis.localStorage = { getItem: () => 'token' }
  const requests = []
  globalThis.fetch = async (url, init) => { requests.push({ url, ...init }); return Response.json([]) }
  const controller = new AbortController()
  await api.formAssigneeOptions(controller.signal)
  assert.ok(requests[0].url.endsWith('/process-definitions/form-assignee-options'))
  assert.equal(requests[0].headers.get('Authorization'), 'Bearer token')
  assert.equal(requests[0].headers.has('Idempotency-Key'), false)
  assert.equal(requests[0].cache, 'no-store'); assert.equal(requests[0].signal, controller.signal)
})
