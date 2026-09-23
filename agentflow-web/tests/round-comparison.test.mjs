import test from 'node:test'
import assert from 'node:assert/strict'
const { compareSubmissionRounds, sameSubmittedValue, selectRoundPair, RoundComparisonQuery } = await import(process.env.AGENTFLOW_TEST_ROUND_COMPARISON)
const round = (roundNo, payload = {}, formSchema = null) => ({ roundNo, title: '差旅申请', definitionVersion: 1, payload, formSchema, status: 'IN_APPROVAL', submittedAt: '2026-09-23T10:00:00Z', submittedBy: 'alice' })
const field = (key, type = 'TEXT') => ({ key, label: key, type, required: false })
const schema = fields => ({ schemaVersion: 1, fields })
const valueRows = result => result.rows.filter(row => row.category === 'field')

test('轮次对比保留精确金额和原始类型，不归一化数字写法', () => {
  const a = round(1, { amount: '99999999999999999999.123456789', fee: '1.00', flag: false })
  const b = round(2, { amount: '99999999999999999999.123456788', fee: '1', flag: 'false' })
  const old = structuredClone([a, b]), result = compareSubmissionRounds(a, b)
  assert.equal(result.valueChanges, 3)
  assert.equal(valueRows(result)[0].before.text, a.payload.amount)
  assert.equal(valueRows(result)[0].after.text, b.payload.amount)
  assert.deepEqual([a, b], old)
})

test('缺失、null、空文本、false 与零均可区分，保留字段新增与移除', () => {
  const a = round(1, { removed: 1, nil: null, empty: '', flag: false, zero: 0 })
  const b = round(2, { added: '新值', empty: null, flag: false, zero: 0 })
  const rows = new Map(valueRows(compareSubmissionRounds(a, b)).map(row => [row.key, row]))
  assert.equal(rows.get('nil').before.type, '空值'); assert.equal(rows.get('nil').after.type, '缺失')
  assert.equal(rows.get('empty').before.type, '文本'); assert.equal(rows.get('empty').after.type, '空值')
  assert.equal(rows.get('removed').after.present, false); assert.equal(rows.get('added').before.present, false)
  assert.equal(rows.get('flag').changed, false); assert.equal(rows.get('flag').before.text, '否（false）')
  assert.equal(rows.get('zero').changed, false); assert.equal(rows.get('zero').before.text, '0')
})

test('历史字段标签和选项各取自身快照，配置变化不会误报内容相同', () => {
  const first = { ...field('purpose', 'SELECT'), label: '原用途', options: [{ value: 'travel', label: '原差旅' }] }
  const second = { ...first, label: '新用途', required: true, options: [{ value: 'travel', label: '新差旅' }] }
  const result = compareSubmissionRounds(round(1, { purpose: 'travel' }, schema([first])), round(2, { purpose: 'travel' }, schema([second])))
  assert.equal(result.schemaChanged, true); assert.equal(result.definitionChanges, 1); assert.equal(result.valueChanges, 0)
  const row = valueRows(result)[0]
  assert.equal(row.beforeLabel, '原用途'); assert.equal(row.afterLabel, '新用途')
  assert.equal(row.before.text, '原差旅（travel）'); assert.equal(row.after.text, '新差旅（travel）')
  assert.equal(compareSubmissionRounds(round(1), round(2, {}, schema([]))).schemaChanged, true)
})

test('对象键顺序不算变化，数组顺序、标题和版本有明确变化', () => {
  assert.equal(sameSubmittedValue({ a: 1, b: { c: 2, d: [3, 4] } }, { b: { d: [3, 4], c: 2 }, a: 1 }), true)
  assert.equal(sameSubmittedValue([1, 2], [2, 1]), false)
  const a = round(1, { nested: { b: 2, a: 1 } }), b = round(2, { nested: { a: 1, b: 2 } })
  b.title = '补正后的申请'; b.definitionVersion = 2
  const result = compareSubmissionRounds(a, b)
  assert.equal(result.valueChanges, 2)
  assert.deepEqual(result.rows.filter(row => row.changed).map(row => row.category), ['title', 'version'])
})

test('原型同名字段只比较自身数据，文本不会解释为 HTML', () => {
  const payload = JSON.parse('{"__proto__":{"x":1},"constructor":"旧","toString":"<img src=x onerror=alert(1)>"}')
  const a = round(1, payload), b = round(2, JSON.parse(JSON.stringify(payload)))
  assert.equal(compareSubmissionRounds(a, b).valueChanges, 0)
  delete b.payload.constructor
  const removed = valueRows(compareSubmissionRounds(a, b)).find(row => row.key === 'constructor')
  assert.equal(removed.after.type, '缺失'); assert.equal(removed.changed, true)
  assert.equal(valueRows(compareSubmissionRounds(a, b)).find(row => row.key === 'toString').before.text, payload.toString)
  const s = schema([field('toString')])
  assert.equal(compareSubmissionRounds(round(1, {}, s), round(2, Object.create(null), s)).valueChanges, 0)
})

test('默认选择最近真实轮次，可保持历史对比，缺失轮次不补造', () => {
  const list = [round(5), round(1), round(3)]
  assert.deepEqual(selectRoundPair(list), { before: 3, after: 5 })
  assert.deepEqual(selectRoundPair(list, 1, 3), { before: 1, after: 3 })
  assert.deepEqual(selectRoundPair(list, 3, 3), { before: 1, after: 3 })
  assert.deepEqual(selectRoundPair(list, 2, 4), { before: 3, after: 5 })
  assert.deepEqual(selectRoundPair([round(5)]), { before: 0, after: 0 })
  assert.deepEqual(list.map(value => value.roundNo), [5, 1, 3])
})

test('切换申请或账号时取消请求，迟到成功和失败都不回填', async () => {
  const requests = [], query = new RoundComparisonQuery((id, signal) => new Promise((resolve, reject) => requests.push({ id, signal, resolve, reject })))
  const old = query.load('demo:alice', 'one'), current = query.load('demo:manager', 'two')
  assert.equal(requests[0].signal.aborted, true)
  requests[1].resolve([round(1), round(2)]); await current
  requests[0].resolve([round(3)]); await old
  assert.deepEqual(query.rounds.map(value => value.roundNo), [2, 1])
  const late = query.load('demo:manager', 'two'); query.clear()
  requests[2].reject({ message: 'old session' }); await late
  assert.equal(query.rounds, null); assert.equal(query.error, ''); assert.equal(query.loading, false)
})

test('刷新失败清除旧数据，空记录和无权限不伪造相同比较，重试可恢复', async () => {
  let calls = 0
  const query = new RoundComparisonQuery(async () => { if (++calls === 2) throw { status: 404 }; return calls === 3 ? [] : [round(1), round(2)] })
  await query.load('demo:alice', 'one'); assert.equal(query.rounds.length, 2)
  await query.load('demo:alice', 'one'); assert.equal(query.rounds, null); assert.match(query.error, /无权/)
  await query.load('demo:alice', 'one'); assert.deepEqual(query.rounds, []); assert.equal(query.error, '')
  await query.load('demo:alice', 'one'); assert.equal(query.rounds.length, 2)
  await query.load('', 'one'); assert.equal(query.rounds, null); assert.equal(calls, 4)
})

test('轮次接口传递取消信号，继续使用原只读授权路径', async () => {
  globalThis.localStorage = { getItem: () => 'test-token' }
  const { api } = await import(process.env.AGENTFLOW_TEST_API)
  let sent
  globalThis.fetch = async (url, init) => { sent = { url, ...init }; return Response.json([]) }
  const controller = new AbortController()
  await api.applicationRounds('app/id', controller.signal)
  assert.equal(sent.url, '/api/v1/applications/app%2Fid/rounds')
  assert.equal(sent.signal, controller.signal); assert.equal(sent.headers.has('Idempotency-Key'), false)
  assert.ok(sent.method === undefined || sent.method === 'GET')
})

test('明细轮次比较使用各轮列名与选项，保留空值、原始数值及行序变化', () => {
  const aSchema = { schemaVersion: 2, fields: [{ ...field('items', 'TABLE'), columns: [
    { ...field('amount', 'NUMBER'), label: '原金额' }, { ...field('kind', 'SELECT'), options: [{ value: 'a', label: '原类别' }] }
  ] }] }
  const bSchema = structuredClone(aSchema); bSchema.fields[0].columns[0].label = '新金额'; bSchema.fields[0].columns[1].options[0].label = '新类别'
  const payload = { items: [{ amount: '0001.00', kind: 'a' }, { amount: null }] }
  const result = compareSubmissionRounds(round(1, payload, aSchema), round(2, { items: [...payload.items].reverse() }, bSchema))
  const row = valueRows(result)[0]
  assert.equal(row.changed, true); assert.equal(row.definitionChanged, true)
  assert.match(row.before.text, /原金额：0001.00；kind：原类别（a）/)
  assert.match(row.after.text, /第 1 行：新金额：空值（null）/)
  assert.match(row.after.text, /新类别（a）/)
})
