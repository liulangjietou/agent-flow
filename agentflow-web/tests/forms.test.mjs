import test from 'node:test'
import assert from 'node:assert/strict'

const { validatePayload, compareDecimal, displayFields, updatePayloadField, validDecimal, cloneSchema, ownValue, validateFormSchema } = await import(process.env.AGENTFLOW_TEST_FORMS)
const schema = { schemaVersion: 1, fields: [{ key: 'amount', label: '申请金额', type: 'NUMBER', required: true, minimum: '0' }] }

test('金额保留十进制精度，草稿允许未填写，提交检查必填', () => {
  assert.deepEqual(validatePayload(schema, {}, false), {})
  assert.deepEqual(validatePayload(schema, {}, true), { amount: 'REQUIRED' })
  assert.deepEqual(validatePayload(schema, { amount: '9007199254740993.01' }, true), {})
  assert.equal(compareDecimal('9007199254740993.01', '9007199254740993.00'), 1)
})

test('合法字段标识与对象原型同名时只读取自身业务值', () => {
  const prototypeName = { schemaVersion: 1, fields: [{ key: 'toString', label: '说明', type: 'TEXT', required: true }] }
  assert.deepEqual(validatePayload(prototypeName, {}, false), {})
  assert.deepEqual(validatePayload(prototypeName, {}, true), { toString: 'REQUIRED' })
  assert.equal(displayFields(prototypeName, {})[0].value, '未填写')
  assert.equal(ownValue({}, 'toString'), undefined)
  assert.equal(ownValue({ toString: '客户填写' }, 'toString'), '客户填写')
})

test('十进制语法、精度、小数位和长度边界不经过浮点转换', () => {
  for (const value of ['001.20', '-0.00', '0', '-1', '9'.repeat(38), '0.' + '0'.repeat(17) + '1']) assert.equal(validDecimal(value), true, value)
  for (const value of [0, null, '+1', ' 1', '1 ', '1e2', '.5', '1.', '9'.repeat(39), '0.' + '0'.repeat(18) + '1', '0'.repeat(81)]) assert.equal(validDecimal(value), false, String(value))
  assert.equal(compareDecimal('-0.00', '000'), 0)
  assert.equal(compareDecimal('-1.000000000000000001', '-1'), -1)
  assert.equal(compareDecimal('0.000000000000000001', '0'), 1)
})

test('数值范围在小数最后一位也能准确判断', () => {
  const precise = { schemaVersion: 1, fields: [{ key: 'total', label: '总额', type: 'NUMBER', required: false, minimum: '9007199254740993.01', maximum: '9007199254740993.02' }] }
  assert.deepEqual(validatePayload(precise, { total: '9007199254740993.00' }, false), { total: 'BELOW_MINIMUM' })
  assert.deepEqual(validatePayload(precise, { total: '9007199254740993.03' }, false), { total: 'ABOVE_MAXIMUM' })
  assert.deepEqual(validatePayload(precise, { total: '9007199254740993.02' }, true), {})
  assert.deepEqual(validatePayload(precise, { total: 100 }, false), { total: 'INVALID_TYPE' })
})

test('日期检查实际日历，包括世纪闰年和年零', () => {
  const date = { schemaVersion: 1, fields: [{ key: 'day', label: '日期', type: 'DATE', required: true }] }
  for (const day of ['2000-02-29', '2024-02-29', '0001-01-01', '9999-12-31']) assert.deepEqual(validatePayload(date, { day }, true), {})
  for (const day of ['1900-02-29', '2025-02-29', '2024-04-31', '2024-13-01', '2024-00-01', '2024-01-00', '0000-01-01', '2024-1-01', ' 2024-01-01']) assert.deepEqual(validatePayload(date, { day }, true), { day: 'INVALID_DATE' }, day)
})

test('必填布尔值false有效，未填写与字符串false不能混用', () => {
  const boolean = { schemaVersion: 1, fields: [{ key: 'approved', label: '是否确认', type: 'BOOLEAN', required: true }] }
  assert.deepEqual(validatePayload(boolean, { approved: false }, true), {})
  assert.deepEqual(validatePayload(boolean, { approved: true }, true), {})
  for (const value of [undefined, null, '']) {
    assert.deepEqual(validatePayload(boolean, { approved: value }, false), {})
    assert.deepEqual(validatePayload(boolean, { approved: value }, true), { approved: 'REQUIRED' })
  }
  assert.deepEqual(validatePayload(boolean, { approved: 'false' }, false), { approved: 'INVALID_TYPE' })
  assert.equal(displayFields(boolean, { approved: false })[0].value, '否')
})

test('空白文本视为未填，数字日期选项空白值必须报错', () => {
  const fields = { schemaVersion: 1, fields: [
    { key: 'name', label: '姓名', type: 'TEXT', required: true, maxLength: 2 },
    { key: 'memo', label: '说明', type: 'TEXTAREA', required: false, maxLength: 2 },
    { key: 'amount', label: '金额', type: 'NUMBER', required: false },
    { key: 'day', label: '日期', type: 'DATE', required: false },
    { key: 'choice', label: '选择', type: 'SELECT', required: false, options: [{ value: 'a', label: '一' }] }
  ] }
  assert.deepEqual(validatePayload(fields, { name: '  ', memo: '😀中' }, false), {})
  assert.deepEqual(validatePayload(fields, { name: '  ', memo: '😀中a', amount: ' ', day: ' ', choice: ' ' }, true), {
    name: 'REQUIRED', memo: 'TOO_LONG', amount: 'INVALID_NUMBER', day: 'INVALID_DATE', choice: 'INVALID_OPTION'
  })
})

test('版本化选项按本轮文案展示，复制编辑不改变历史schema', () => {
  const original = { schemaVersion: 1, fields: [{ key: 'kind', label: '费用类别 v1', type: 'SELECT', required: true, options: [{ value: 'trip', label: '差旅 v1' }] }] }
  const copied = cloneSchema(original)
  copied.fields[0].label = '费用类别 v2'; copied.fields[0].options[0].label = '差旅 v2'
  assert.deepEqual(displayFields(original, { kind: 'trip' })[0], { key: 'kind', label: '费用类别 v1', value: '差旅 v1', extra: false })
  assert.equal(displayFields(copied, { kind: 'trip' })[0].value, '差旅 v2')
  assert.deepEqual(validatePayload(original, { kind: 'other' }, false), { kind: 'INVALID_OPTION' })
  assert.equal(displayFields(original, { kind: 'other' })[0].value, 'other')
  assert.equal(cloneSchema(null), null)
})

test('修改单字段保留其他已保存字段及原类型，动态未知字段报错但不删除', () => {
  const original = { amount: '001.20', legacy: { tags: ['a', null] }, flag: false, count: 0, empty: null }
  const revised = updatePayloadField(original, 'amount', '3.000')
  assert.deepEqual(revised, { ...original, amount: '3.000' })
  assert.equal(original.amount, '001.20')
  assert.deepEqual(validatePayload(null, revised, true), {})
  assert.deepEqual(validatePayload(schema, revised, false), { legacy: 'UNKNOWN_FIELD', flag: 'UNKNOWN_FIELD', count: 'UNKNOWN_FIELD', empty: 'UNKNOWN_FIELD' })
  assert.equal(displayFields(schema, revised).find(field => field.key === 'legacy').extra, true)
  assert.equal(displayFields(null, revised)[0].label, '申请金额')
})

test('未知__proto__字段仍被明确识别，不能写入错误对象的原型', () => {
  const payload = JSON.parse('{"__proto__":{"polluted":true}}')
  const errors = validatePayload({ schemaVersion: 1, fields: [] }, payload, false)
  assert.deepEqual(Object.keys(errors), ['__proto__'])
  assert.equal(ownValue(errors, '__proto__'), 'UNKNOWN_FIELD')
  assert.equal(Object.getPrototypeOf(errors), Object.prototype)
  assert.equal({}.polluted, undefined)
})

test('升级前幂等响应缺少schema时按legacy展示，不补造其他字段标记', () => {
  assert.deepEqual(displayFields(undefined, { amount: 0 }), [{ key: 'amount', label: '申请金额', value: '0', extra: false }])
})

test('表单配置错误定位到字段属性，修正后全部清除', () => {
  const configured = { schemaVersion: 1, fields: [
    { key: 'same', label: '', type: 'TEXT', required: true, maxLength: 0 },
    { key: 'same', label: '金额', type: 'NUMBER', required: false, minimum: '2', maximum: '1' },
    { key: 'tenantId', label: '租户', type: 'SELECT', required: false, options: [{ value: '', label: '' }, { value: 'a', label: '一' }, { value: 'a', label: '二' }] }
  ] }
  const errors = validateFormSchema(configured)
  assert.ok(errors.fields[0].key)
  assert.ok(errors.fields[1].key)
  assert.ok(errors.fields[0].label)
  assert.ok(errors.fields[0].maxLength)
  assert.ok(errors.fields[1].maximum)
  assert.ok(errors.fields[2].key)
  assert.ok(errors.fields[2]['options.0.value'])
  assert.ok(errors.fields[2]['options.0.label'])
  assert.ok(errors.fields[2]['options.1.value'])
  assert.ok(errors.fields[2]['options.2.value'])
  configured.fields[0] = { key: 'reason', label: '理由', type: 'TEXT', required: true, maxLength: 30 }
  configured.fields[1] = { key: 'amount', label: '金额', type: 'NUMBER', required: false, minimum: '1', maximum: '2' }
  configured.fields[2] = { key: 'kind', label: '类型', type: 'SELECT', required: false, options: [{ value: 'a', label: '一' }] }
  assert.deepEqual(validateFormSchema(configured), { schema: [], fields: [{}, {}, {}] })
})

test('配置校验覆盖标识边界、保留字和数值约束，不误伤原型同名业务标识', () => {
  const configured = { schemaVersion: 1, fields: [
    { key: '1field', label: '错误标识', type: 'TEXT', required: false, maxLength: 1.5 },
    { key: 'constructor', label: '保留标识', type: 'TEXT', required: false, maxLength: 10001 },
    { key: 'amount', label: '数字', type: 'NUMBER', required: false, minimum: '1e2', maximum: ' 3' },
    { key: 'toString', label: '业务说明', type: 'TEXT', required: false, maxLength: 10000 }
  ] }
  const errors = validateFormSchema(configured).fields
  assert.deepEqual(Object.keys(errors[0]).sort(), ['key', 'maxLength'])
  assert.deepEqual(Object.keys(errors[1]).sort(), ['key', 'maxLength'])
  assert.deepEqual(Object.keys(errors[2]).sort(), ['maximum', 'minimum'])
  assert.deepEqual(errors[3], {})
  configured.fields[2].minimum = '9007199254740993.01'; configured.fields[2].maximum = '9007199254740993.00'
  assert.ok(validateFormSchema(configured).fields[2].maximum)
})

test('空选项和超量字段有位置明确的提示，legacy与明确空表单均合法', () => {
  assert.deepEqual(validateFormSchema(null), { schema: [], fields: [] })
  assert.deepEqual(validateFormSchema({ schemaVersion: 1, fields: [] }), { schema: [], fields: [] })
  const configured = { schemaVersion: 1, fields: [{ key: 'kind', label: '类型', type: 'SELECT', required: false, options: [] }] }
  assert.ok(validateFormSchema(configured).fields[0].options)
  const crowded = { schemaVersion: 1, fields: Array.from({ length: 51 }, (_, index) => ({ key: `field${index}`, label: '字段', type: 'TEXT', required: false })) }
  assert.equal(validateFormSchema(crowded).schema.length, 1)
})

const detailSchema = () => ({ schemaVersion: 2, fields: [{ key: 'items', label: '设备明细', type: 'TABLE', required: true, maxRows: 2, columns: [
  { key: 'quantity', label: '数量', type: 'NUMBER', required: true, minimum: '0' },
  { key: 'confirmed', label: '确认', type: 'BOOLEAN', required: false }
] }] })
test('重复明细逐行校验必填、原始类型、未知列和行数，不改变填写内容', () => {
  const schema = detailSchema(), payload = { items: [{ quantity: '0009007199254740993.00', confirmed: false }] }, before = structuredClone(payload)
  assert.deepEqual(validatePayload(schema, payload, true), {})
  assert.deepEqual(payload, before)
  assert.deepEqual(validatePayload(schema, { items: [{}] }, false), {})
  assert.deepEqual(validatePayload(schema, { items: [{}, { quantity: 1, extra: 'secret' }] }, true), {
    'items[0].quantity': 'REQUIRED', 'items[1].extra': 'UNKNOWN_FIELD', 'items[1].quantity': 'INVALID_TYPE'
  })
  for (const value of ['', {}, [null], [[], 'row']]) assert.ok(Object.values(validatePayload(schema, { items: value }, true)).includes('INVALID_TYPE'))
  assert.deepEqual(validatePayload(schema, { items: [] }, true), { items: 'REQUIRED' })
  assert.deepEqual(validatePayload(schema, { items: [{}, {}, {}] }, false), { items: 'TOO_MANY_ROWS' })
  assert.match(displayFields(schema, payload)[0].value, /第 1 行：数量：0009007199254740993.00；确认：否/)
})
test('明细配置拒绝旧格式、嵌套、重复列和非法行数，单元格总量有界', () => {
  const schema = detailSchema()
  assert.deepEqual(validateFormSchema(schema), { schema: [], fields: [{}] })
  assert.ok(validateFormSchema({ ...schema, schemaVersion: 1 }).fields[0].type)
  schema.fields[0].columns.push({ ...schema.fields[0] }); schema.fields[0].maxRows = 101
  const errors = validateFormSchema(schema).fields[0]
  assert.ok(errors['columns.2.type']); assert.ok(errors.maxRows)
  const columns = Array.from({ length: 20 }, (_, i) => ({ key: `c${i}`, label: '列', type: 'TEXT', required: false }))
  const table = { key: 'items', label: '明细', type: 'TABLE', required: false, maxRows: 100, columns }
  assert.deepEqual(validatePayload({ schemaVersion: 2, fields: [table, { ...table, key: 'more' }] }, { items: Array.from({ length: 100 }, () => ({})), more: [{}] }, true), { more: 'TOO_MANY_CELLS' })
})
