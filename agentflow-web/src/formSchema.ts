export type FieldVisibility = 'READ_ONLY' | 'MASKED' | 'HIDDEN'
export type FieldType = 'TEXT' | 'TEXTAREA' | 'NUMBER' | 'DATE' | 'SELECT' | 'BOOLEAN' | 'TABLE'
export interface FormOption { value: string; label: string }
export interface FormField {
  key: string; label: string; type: FieldType; required: boolean; helpText?: string
  maxLength?: number; minimum?: string; maximum?: string; options?: FormOption[]; columns?: FormField[]; maxRows?: number; sensitive?: boolean | null; nodeAccess?: Record<string, FieldVisibility> | null
}
export interface FormSchema { schemaVersion: 1 | 2; fields: FormField[] }
export type FieldErrors = Record<string, string>
export interface SchemaErrors { schema: string[]; fields: Array<Record<string, string>> }
export const fieldTypes: Array<{ value: FieldType; label: string }> = [
  { value: 'TEXT', label: '单行文本' }, { value: 'TEXTAREA', label: '多行文本' },
  { value: 'NUMBER', label: '数字' }, { value: 'DATE', label: '日期' },
  { value: 'SELECT', label: '单选' }, { value: 'BOOLEAN', label: '是 / 否' }, { value: 'TABLE', label: '重复明细' }
]
const errorMessages: Record<string, string> = {
  TOO_MANY_ROWS: '明细行数超过此表单的上限。', TOO_MANY_CELLS: '明细总单元格数超过 2000，请减少行数。',
  REQUIRED: '请填写此项。', INVALID_TYPE: '保存的值类型不匹配，请重新填写。',
  INVALID_NUMBER: '请输入十进制数字，最多 38 位有效数字、18 位小数，不含空格或指数。',
  INVALID_DATE: '请输入有效日期，格式为 YYYY-MM-DD。', INVALID_OPTION: '请选择此版本提供的选项。',
  TOO_LONG: '内容超过允许的长度。', BELOW_MINIMUM: '数值低于允许的最小值。',
  ABOVE_MAXIMUM: '数值超过允许的最大值。', UNKNOWN_FIELD: '此字段不在当前表单中，请核对已保存的其他字段。'
}
/** 合法业务标识可能与 toString 等原型成员同名，只读取真实保存的字段。 */
export const ownValue = <T>(values: Record<string, T>, key: string): T | undefined => Object.prototype.hasOwnProperty.call(values, key) ? values[key] : undefined
export const fieldErrorMessage = (code: string) => ownValue(errorMessages, code) ?? '此字段未通过校验，请核对后重试。'
export const cloneSchema = (schema: FormSchema | null | undefined): FormSchema | null => schema == null ? null : JSON.parse(JSON.stringify(schema)) as FormSchema
export function defaultFormSchema(): FormSchema {
  return { schemaVersion: 1, fields: [
    { key: 'amount', label: '申请金额', type: 'NUMBER', required: true, minimum: '0' },
    { key: 'description', label: '申请说明', type: 'TEXTAREA', required: false, maxLength: 10000 }
  ] }
}
export function validDecimal(value: unknown): value is string {
  if (typeof value !== 'string' || value.length > 80 || !/^-?\d+(\.\d+)?$/.test(value)) return false
  const unsigned = value.replace('-', '')
  const digits = unsigned.replace('.', '').replace(/^0+/, '')
  return (digits.length || 1) <= 38 && (unsigned.split('.')[1]?.length ?? 0) <= 18
}
/** 配置提示按字段位置和属性定位，重复标识不会覆盖另一字段的错误。 */
export function validateFormSchema(schema: FormSchema | null, columnsOnly = false): SchemaErrors {
  if (!schema) return { schema: [], fields: [] }
  const result: SchemaErrors = { schema: [], fields: [] }
  const reserved = new Set(['constructor', 'prototype', 'tenantId', 'applicationId', 'businessNo', 'roundNo', 'formData', 'formFieldTypes', 'lastAction'])
  if (![1, 2].includes(schema.schemaVersion)) result.schema.push('表单格式版本不受支持。')
  if (schema.fields.length > 50) result.schema.push('最多配置 50 个字段。')
  const keyCounts = new Map<string, number>()
  for (const field of schema.fields) keyCounts.set(field.key, (keyCounts.get(field.key) ?? 0) + 1)
  result.fields = schema.fields.map(field => {
    const errors: Record<string, string> = {}
    if (field.sensitive != null && typeof field.sensitive !== 'boolean') errors.sensitive = '敏感字段配置必须是布尔值。'
    if (field.nodeAccess != null && (typeof field.nodeAccess !== 'object' || Array.isArray(field.nodeAccess) || Object.keys(field.nodeAccess).length > 200 || Object.entries(field.nodeAccess).some(([key, value]) => !key.trim() || key.length > 128 || !['READ_ONLY','MASKED','HIDDEN'].includes(value)))) errors.nodeAccess = '节点字段权限只支持只读、脱敏和隐藏。'

    if (!/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(field.key)) errors.key = '以字母开头，仅用字母、数字和下划线，最多 64 字符。'
    else if (reserved.has(field.key)) errors.key = '这是系统保留标识，请更换。'
    else if ((keyCounts.get(field.key) ?? 0) > 1) errors.key = '字段标识重复，请为每个字段使用不同标识。'
    if (!field.label.trim()) errors.label = '请填写字段名称。'
    else if (field.label.length > 128) errors.label = '字段名称最多 128 字符。'
    if (field.helpText != null && field.helpText.length > 1000) errors.helpText = '填写提示最多 1000 字符。'
    if (!fieldTypes.some(type => type.value === field.type)) errors.type = '请选择支持的填写类型。'
    if (field.maxLength != null && (!['TEXT', 'TEXTAREA'].includes(field.type) || !Number.isInteger(field.maxLength) || field.maxLength < 1 || field.maxLength > 10000)) errors.maxLength = '最多字符数须为 1 至 10000 之间的整数，且仅用于文本字段。'
    for (const property of ['minimum', 'maximum'] as const) {
      const value = field[property]
      if (value != null && (field.type !== 'NUMBER' || !validDecimal(value))) errors[property] = '请输入有效十进制数字，不含空格、指数或加号；最多 38 位有效数字、18 位小数。'
    }
    if (field.type === 'NUMBER' && field.minimum != null && field.maximum != null && validDecimal(field.minimum) && validDecimal(field.maximum) && compareDecimal(field.minimum, field.maximum) > 0) errors.maximum = '最大值不能小于最小值。'
    if (field.type === 'SELECT') {
      const options = field.options ?? []
      if (!options.length || options.length > 50) errors.options = '请配置 1 至 50 个选项。'
      const values = new Map<string, number>()
      for (const option of options) values.set(option.value, (values.get(option.value) ?? 0) + 1)
      options.forEach((option, index) => {
        if (!option.value.trim() || option.value.length > 128) errors[`options.${index}.value`] = '保存值不能为空，最多 128 字符。'
        else if ((values.get(option.value) ?? 0) > 1) errors[`options.${index}.value`] = '选项保存值重复，请更换。'
        if (!option.label.trim() || option.label.length > 128) errors[`options.${index}.label`] = '显示名称不能为空，最多 128 字符。'
      })
    }
    if (field.type === 'TABLE') {
      if (columnsOnly || schema.schemaVersion !== 2) errors.type = '重复明细仅支持格式版本 2，不能嵌套明细。'
      if (!Array.isArray(field.columns) || !field.columns.length || field.columns.length > 20) errors.columns = '请配置 1 至 20 个明细列。'
      else if (!columnsOnly) validateFormSchema({ schemaVersion: 1, fields: field.columns }, true).fields.forEach((issues, index) => {
        for (const [key, message] of Object.entries(issues)) errors[`columns.${index}.${key}`] = message
      })
      if (field.maxRows != null && (!Number.isInteger(field.maxRows) || field.maxRows < 1 || field.maxRows > 100)) errors.maxRows = '最多行数须为 1 至 100 之间的整数。'
    } else {
      if (field.columns != null) errors.columns = '只有重复明细可以配置明细列。'
      if (field.maxRows != null) errors.maxRows = '只有重复明细可以配置行数。'
    }
    return errors
  })
  return result
}
/** 用整数对齐小数位进行比较，避免审批金额经过浮点数后失真。 */
export function compareDecimal(left: string, right: string): number {
  const scale = Math.max(left.split('.')[1]?.length ?? 0, right.split('.')[1]?.length ?? 0)
  const units = (value: string) => {
    const negative = value.startsWith('-')
    const [whole, fraction = ''] = value.replace('-', '').split('.')
    const amount = BigInt(whole + fraction.padEnd(scale, '0'))
    return negative ? -amount : amount
  }
  const a = units(left), b = units(right)
  return a === b ? 0 : a < b ? -1 : 1
}
function validDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const [year, month, day] = value.split('-').map(Number)
  if (year < 1 || month < 1 || month > 12 || day < 1) return false
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  return day <= [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1]
}
/** 草稿允许未填字段；真正提交时才检查必填，始终保留调用者的原始值。 */
export function validatePayload(schema: FormSchema | null, payload: Record<string, unknown>, submit: boolean): FieldErrors {
  const errors: FieldErrors = {}
  if (!schema) return errors
  const allowed = new Set(schema.fields.map(field => field.key))
  // 未知字段也可能叫 __proto__，定义自有属性，不能触发对象原型赋值。
  for (const key of Object.keys(payload)) if (!allowed.has(key)) Object.defineProperty(errors, key, { value: 'UNKNOWN_FIELD', enumerable: true, configurable: true, writable: true })
  let tableCells = 0
  for (const field of schema.fields) {
    const value = ownValue(payload, field.key)
    if (field.type === 'TABLE') {
      if (Array.isArray(value)) tableCells += value.length * (field.columns?.length ?? 0)
      if (Array.isArray(value) && tableCells > 2000) errors[field.key] = 'TOO_MANY_CELLS'
      else if (value == null || Array.isArray(value) && !value.length) { if (submit && field.required) errors[field.key] = 'REQUIRED' }
      else if (!Array.isArray(value)) errors[field.key] = 'INVALID_TYPE'
      else if (value.length > (field.maxRows ?? 50)) errors[field.key] = 'TOO_MANY_ROWS'
      else value.forEach((row, index) => {
        const path = `${field.key}[${index}]`
        if (!isDetailRow(row)) errors[path] = 'INVALID_TYPE'
        else for (const [key, code] of Object.entries(validatePayload({ schemaVersion: 1, fields: (field.columns ?? []).filter(column => column.type !== 'TABLE') }, row, submit))) errors[`${path}.${key}`] = code
      })
      continue
    }
    const empty = value == null || value === '' || ((field.type === 'TEXT' || field.type === 'TEXTAREA') && typeof value === 'string' && !value.trim())
    if (empty) { if (submit && field.required) errors[field.key] = 'REQUIRED'; continue }
    if (field.type === 'BOOLEAN') { if (typeof value !== 'boolean') errors[field.key] = 'INVALID_TYPE'; continue }
    if (typeof value !== 'string') { errors[field.key] = 'INVALID_TYPE'; continue }
    if (field.type === 'TEXT' || field.type === 'TEXTAREA') {
      if ([...value].length > (field.maxLength ?? 10000)) errors[field.key] = 'TOO_LONG'
    } else if (field.type === 'NUMBER') {
      if (!validDecimal(value)) errors[field.key] = 'INVALID_NUMBER'
      else if (field.minimum != null && validDecimal(field.minimum) && compareDecimal(value, field.minimum) < 0) errors[field.key] = 'BELOW_MINIMUM'
      else if (field.maximum != null && validDecimal(field.maximum) && compareDecimal(value, field.maximum) > 0) errors[field.key] = 'ABOVE_MAXIMUM'
    } else if (field.type === 'DATE' && !validDate(value)) errors[field.key] = 'INVALID_DATE'
    else if (field.type === 'SELECT' && !field.options?.some(option => option.value === value)) errors[field.key] = 'INVALID_OPTION'
  }
  return errors
}
/** 只更新一个被用户编辑的字段，未知字段、空值以及未编辑字段的类型均保持原样。 */
export function updatePayloadField(payload: Record<string, unknown>, key: string, value: unknown): Record<string, unknown> {
  return { ...payload, [key]: value }
}
/** 明细行只接受普通 JSON 对象，不把数组或空值改写成空行。 */
export const isDetailRow = (value: unknown): value is Record<string, unknown> => value != null && typeof value === 'object' && !Array.isArray(value)
/** 按当前快照列名展示，保留行序和未知字段，避免隐藏旧数据。 */
export function detailValueLabel(field: FormField, value: unknown): string {
  if (!Array.isArray(value)) return rawValueLabel(value)
  if (!value.length) return '未填写'
  return value.map((row, index) => `第 ${index + 1} 行：` + (isDetailRow(row)
    ? displayFields({ schemaVersion: 1, fields: (field.columns ?? []).filter(column => column.type !== 'TABLE') }, row).map(cell => `${cell.label}：${cell.value}`).join('；')
    : rawValueLabel(row))).join('\n')
}
export const rawValueLabel = (value: unknown) => value == null ? '未填写' : typeof value === 'object' ? JSON.stringify(value, null, 2) : value === '' ? '未填写' : String(value)
export function displayFields(schema: FormSchema | null | undefined, payload: Record<string, unknown>) {
  const known = new Set(schema?.fields.map(field => field.key) ?? [])
  const configured = (schema?.fields ?? []).map(field => {
    const value = ownValue(payload, field.key)
    const label = field.type === 'TABLE' ? detailValueLabel(field, value) : field.type === 'BOOLEAN' && typeof value === 'boolean' ? value ? '是' : '否'
      : field.type === 'SELECT' ? field.options?.find(option => option.value === value)?.label ?? rawValueLabel(value) : rawValueLabel(value)
    return { key: field.key, label: field.label, value: label, extra: false }
  })
  const extra = Object.entries(payload).filter(([key]) => !known.has(key)).map(([key, value]) => ({
    key, label: !schema && key === 'amount' ? '申请金额' : !schema && key === 'description' ? '申请说明' : key,
    value: rawValueLabel(value), extra: schema != null
  }))
  return [...configured, ...extra]
}
