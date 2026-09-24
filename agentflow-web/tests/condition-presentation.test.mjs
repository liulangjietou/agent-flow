import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'
const { describeCondition, describeBranch, branchTooltip, insertConditionField } = await import(process.env.AGENTFLOW_TEST_PRESENTATION)
const { parseConditionExpression } = await import(new URL('./conditionSyntax.js', pathToFileURL(process.env.AGENTFLOW_TEST_PRESENTATION)))
const fields = [
  {key:'amount',type:'NUMBER',label:'金额'}, {key:'flag',type:'BOOLEAN',label:'紧急'}, {key:'memo',type:'TEXT',label:'事由'},
  {key:'category',type:'SELECT',label:'类型',options:[{value:'A',label:'差旅'},{value:'B',label:'日常'}]},
  {key:'items',type:'TABLE',label:'明细'}, {key:'date',type:'DATE',label:'日期'}
]
function semantic(tree) {
  if (!tree) return null
  if (tree.kind === 'group') return semantic(tree.term)
  if (tree.kind === 'not') return {kind:'not',term:semantic(tree.term)}
  if (tree.terms) return {...tree,terms:tree.terms.map(semantic)}
  return tree
}

test('共享样例的前端语义树与 Java 领域契约相同，包括转义、优先级及拒绝项', () => {
  const fixtures = JSON.parse(readFileSync(new URL('../../agentflow-domain/src/test/resources/condition-presentation-cases.json', import.meta.url), 'utf8'))
  for (const fixture of fixtures) assert.deepEqual(semantic(parseConditionExpression(fixture.source)), fixture.tree, fixture.source)
})

test('条件说明使用字段和选项标签，数字保持十进制原文', () => {
  assert.equal(describeCondition('amount >= 9007199254740993.123456789012345678',fields,2),'金额 大于等于 9007199254740993.123456789012345678')
  assert.equal(describeCondition('category IN ["B", "A"] && flag == false',fields,2),'类型 属于【"日常"、"差旅"】 且 紧急 等于 否')
  assert.equal(describeCondition('items NOT_EXISTS AND date >= "2026-09-23"',fields,2),'明细 未填写 且 日期 大于等于 2026-09-23')
  assert.equal(describeCondition('memo == "a\\nb"',fields,2),'事由 等于 "a\\nb"')
})

test('括号、混合且或和取反说明不丢失分组，不把不满足改写成不等于', () => {
  assert.equal(describeCondition('amount > 2 OR flag == true AND memo EXISTS',fields,2),'金额 大于 2 或 （紧急 等于 是 且 事由 已填写）')
  assert.equal(describeCondition('!(amount > 2 OR flag == true) AND memo EXISTS',fields,2),'不满足：（金额 大于 2 或 紧急 等于 是） 且 事由 已填写')
  assert.equal(describeCondition('amount > 2 AND flag == true OR memo EXISTS',fields,1),'（金额 大于 2 且 紧急 等于 是） 或 事由 已填写')
})

test('历史语言保持无引号特殊文本语义，未知和不合法输入全部回显原文', () => {
  assert.equal(describeCondition('memo == a && b',fields,1),'事由 等于 "a && b"')
  assert.equal(describeCondition("memo == O'Reilly AND flag EXISTS OR amount > 2",fields,1),'（事由 等于 "O\'Reilly" 且 紧急 已填写） 或 金额 大于 2')
  const invalid=['memo EXISTSAND flag EXISTS','category IN []','category IN ["missing"]','unknown > 1','memo > "abc"','amount > "NaN"','items == "[]"','date == "2026-02-30"','(amount >','memo == "\\u003b"']
  for (const source of invalid) assert.equal(describeCondition(source,fields,2),source)
  assert.equal(describeCondition('amount > 2',[],2),'amount > 2')
  assert.equal(describeCondition('amount > 2',fields,99),'amount > 2')
  assert.equal(describeCondition('  ',fields,2),'  ')
})

test('分支只读描述不改写对象，默认分支与长说明保留原文核对入口', () => {
  const edge={id:'e',source:'a',target:'b',condition:'flag == true',defaultBranch:false},before=structuredClone(edge)
  assert.equal(describeBranch(edge,fields,2),'紧急 等于 是')
  assert.equal(branchTooltip(edge,fields,2),'紧急 等于 是\n原始表达式：flag == true')
  assert.equal(branchTooltip({...edge,defaultBranch:true},fields,2),'其他条件均不满足')
  assert.deepEqual(edge,before)
  assert.equal(describeCondition('flag == true',[...fields.filter(x=>x.key!=='flag'),{...fields[1],label:'加急申请'}],2),'加急申请 等于 是')
})

test('展示解析有同样的长度、嵌套、比较和成员预算，超限不伪造说明', () => {
  for (const source of ['!'.repeat(17)+'flag EXISTS', Array(101).fill('flag EXISTS').join(' AND '), 'category IN ['+Array(51).fill('"A"').join(',')+']', 'memo == "'+'a'.repeat(257)+'"', ' '.repeat(4000)+'flag EXISTS']) {
    assert.equal(parseConditionExpression(source),null)
    assert.equal(describeCondition(source,fields,2),source)
  }
  assert.notEqual(parseConditionExpression('!'.repeat(16)+'flag EXISTS'),null)
})

test('字段插入保留前后原文、精度和 UTF-16 选区，并给出还原光标位置', () => {
  assert.deepEqual(insertConditionField('', 'amount', 0, 0, 2),{value:'amount',caret:6})
  assert.deepEqual(insertConditionField('foo > 1', 'amount', 0, 3, 2),{value:'amount > 1',caret:6})
  assert.deepEqual(insertConditionField('( > 1)', 'amount', 1, 1, 2),{value:'(amount > 1)',caret:7})
  assert.deepEqual(insertConditionField('! EXISTS', 'memo', 1, 1, 2),{value:'!memo EXISTS',caret:5})
  const prefix='memo == "🙂" AND ', source=prefix+'old >= 9007199254740993.123456789'
  const inserted=insertConditionField(source,'amount',prefix.length,prefix.length+3,2)
  assert.equal(inserted.value,prefix+'amount >= 9007199254740993.123456789')
  assert.equal(inserted.caret,prefix.length+6)
  assert.deepEqual(insertConditionField('amount>2','flag',0,6,2),{value:'flag >2',caret:5})
})

test('拒绝向引号内和超限表达式插入字段；转义引号不会提前结束字面量', () => {
  assert.throws(()=>insertConditionField('memo == "abc"','amount',10,10,2),/引号外/)
  const source='memo == "a\\"b" AND '
  assert.throws(()=>insertConditionField(source,'amount',source.indexOf('b'),source.indexOf('b'),2),/引号外/)
  assert.equal(insertConditionField(source,'amount',source.length,source.length,2).value,source+'amount')
  assert.throws(()=>insertConditionField('','bad field',0,0,2),/标识/)
  assert.throws(()=>insertConditionField(' '.repeat(4000),'amount',4000,4000,2),/4000/)
})

test('插入识别旧版无引号撇号，选区两端都不能切入引用内容', () => {
  const legacy="memo == O'Reilly AND "
  assert.equal(insertConditionField(legacy,'flag',legacy.length,legacy.length,1).value,legacy+'flag')
  assert.throws(()=>insertConditionField('memo == "abc"','amount',0,10,2),/引号外/)
  assert.deepEqual(insertConditionField('memo == "abc"','amount',0,4,2),{value:'amount == "abc"',caret:6})
})
