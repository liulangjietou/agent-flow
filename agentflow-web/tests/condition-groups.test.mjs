import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const { parseConditionGroup: parse, serializeConditionGroup: serialize, editConditionGroup: edit } = await import(process.env.AGENTFLOW_TEST_GROUPS)
const fixture = JSON.parse(readFileSync(new URL('../../agentflow-domain/src/test/resources/condition-group-cases.json', import.meta.url),'utf8'))
const fields = fixture.fields
const stripIds = term => term.kind === 'group' ? {kind:term.kind,join:term.join,negated:term.negated,children:term.children.map(stripIds)} : {kind:term.kind,negated:term.negated,row:term.row}

test('共享样例输出与 Java 权威领域测试一致，原输入不被修改', () => {
  const before = structuredClone(fixture)
  for (const item of fixture.cases) {
    const tree = parse(item.source, fields, 2)
    assert.ok(tree, item.name)
    assert.equal(serialize(tree, fields), item.serialized, item.name)
    assert.deepEqual(stripIds(parse(item.serialized,fields,2)),stripIds(tree),item.name)
  }
  assert.deepEqual(fixture,before)
})

test('非法原文、未知字段、失效枚举和旧语法不能被猜测为新版分组', () => {
  for (const source of ['amount >','unknown == 1','category IN ["missing"]','description > "x"','dueDate == "2026-02-30"','amount > "NaN"','urgent == True','description == "a;b"']) assert.equal(parse(source,fields,2),null,source)
  assert.equal(parse('description == a && b', fields,1),null)
  assert.equal(parse('amount > 1',[],2),null)
})

test('新增子组、切换且或、取反与修改值是一次不可变编辑', () => {
  const root=parse('amount > 1',fields,2),before=structuredClone(root)
  let next=edit(root,{kind:'addGroup',id:root.id},fields)
  const child=next.children[1]
  next=edit(next,{kind:'join',id:child.id,join:'OR'},fields)
  next=edit(next,{kind:'row',id:child.children[0].id,row:{field:'urgent',operator:'==',value:'true'}},fields)
  next=edit(next,{kind:'addRule',id:child.id},fields)
  next=edit(next,{kind:'row',id:next.children[1].children[1].id,row:{field:'description',operator:'EXISTS',value:''}},fields)
  next=edit(next,{kind:'negate',id:child.id,negated:true},fields)
  assert.equal(serialize(next,fields),'amount > "1" AND !(urgent == "true" OR description EXISTS)')
  assert.deepEqual(root,before); assert.equal(next.children[0].id,root.children[0].id)
  const reduced=edit(next,{kind:'remove',id:child.id},fields)
  assert.equal(serialize(reduced,fields),'amount > "1"')
  assert.throws(()=>edit(reduced,{kind:'remove',id:reduced.children[0].id},fields),/至少保留/)
})

test('一个条件的子组与同连接符子组在保存回读后保持结构', () => {
  for (const source of ['amount EXISTS AND (urgent EXISTS)','amount EXISTS AND (urgent EXISTS AND description EXISTS)']) {
    const tree=parse(source,fields,2),output=serialize(tree,fields)
    assert.ok(output.includes('(')); assert.deepEqual(stripIds(parse(output,fields,2)),stripIds(tree))
  }
})

test('金额和文本转义保留原始字符串，多选值不会共享可变数组', () => {
  const source='amount > 9007199254740993.123456789012345678 AND category IN ["DAILY", "TRAVEL"]'
  let tree=parse(source,fields,2), row={field:'category',operator:'IN',value:'',values:['OTHER']}
  tree=edit(tree,{kind:'row',id:tree.children[1].id,row},fields); row.values.push('TRAVEL')
  assert.deepEqual(tree.children[1].row.values,['OTHER'])
  assert.equal(serialize(tree,fields),'amount > "9007199254740993.123456789012345678" AND category IN ["OTHER"]')
  const text=parse('description == "a\\n\\\"b\\\"\\\\c"',fields,2)
  assert.equal(parse(serialize(text,fields),fields,2).children[0].row.value,'a\n"b"\\c')
})

test('未填完的数值和多选可在当前编辑树继续填写，不绕过最终服务器校验', () => {
  let tree=parse('',fields,2)
  tree=edit(tree,{kind:'row',id:tree.children[0].id,row:{field:'category',operator:'IN',value:'',values:[]}},fields)
  assert.equal(serialize(tree,fields),'category IN []'); assert.equal(parse(serialize(tree,fields),fields,2),null)
  tree=edit(tree,{kind:'row',id:tree.children[0].id,row:{field:'category',operator:'IN',value:'',values:['TRAVEL']}},fields)
  assert.ok(parse(serialize(tree,fields),fields,2))
})

test('删除无关节点或错误的命令目标失败，原树不发生部分修改', () => {
  const tree=parse('amount EXISTS AND urgent EXISTS',fields,2),before=structuredClone(tree)
  for (const command of [{kind:'remove',id:'missing'},{kind:'remove',id:tree.id},{kind:'join',id:tree.children[0].id,join:'OR'}]) assert.throws(()=>edit(tree,command,fields))
  assert.deepEqual(tree,before)
})

test('比较数量及表达式长度在修改前受限，不能留下半次新增', () => {
  const tree=parse(Array(100).fill('urgent EXISTS').join(' OR '),fields,2),before=structuredClone(tree)
  assert.throws(()=>edit(tree,{kind:'addRule',id:tree.id},fields),/100/)
  assert.deepEqual(tree,before)
  const long=parse(Array(14).fill('description == "'+'x'.repeat(250)+'"').join(' OR '),fields,2)
  const added=edit(long,{kind:'addRule',id:long.id},fields)
  assert.throws(()=>edit(added,{kind:'row',id:added.children.at(-1).id,row:{field:'description',operator:'==',value:'x'.repeat(250)}},fields),/4000/)
})

test('括号和取反共同计入深度预算', () => {
  let tree=parse('urgent EXISTS',fields,2)
  for(let i=0;i<16;i++) tree={kind:'group',id:'depth-'+i,join:'AND',negated:false,children:[tree]}
  assert.ok(serialize(tree,fields))
  assert.throws(()=>edit(tree,{kind:'negate',id:tree.id,negated:true},fields),/16/)
})
