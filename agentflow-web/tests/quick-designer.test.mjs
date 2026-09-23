import test from 'node:test'
import assert from 'node:assert/strict'
const { projectQuickGraph, editQuickGraph, quickNodeIds, quickStep } = await import(process.env.AGENTFLOW_TEST_QUICK)
const { parseConditionRows, serializeConditionRows, safeConditionLiteral, operatorsFor } = await import(process.env.AGENTFLOW_TEST_CONDITIONS)
const node = (id, type='USER_TASK') => ({ id, name:id, type, properties:{ assigneeRule:'role:MANAGER', retained:'原属性', x:'123', y:'456' } })
const edge = (id, source, target, condition='', defaultBranch=false) => ({id, source,target,condition,defaultBranch})
const line = () => ({nodes:[node('start','START'),node('a'),node('b'),node('end','END')],edges:[edge('one','start','a'),edge('two','a','b'),edge('three','b','end')]})
const branch = () => ({nodes:[node('start','START'),node('gate','EXCLUSIVE_GATEWAY'),node('a'),node('join'),node('end','END')],edges:[edge('one','start','gate'),edge('default','gate','join','',true),edge('condition','gate','a','amount > 5000'),edge('a-join','a','join'),edge('join-end','join','end')]})
let counter=0
const edit = (graph, command) => editQuickGraph(graph,command,()=>String(++counter))

test('快速投影保留原图，默认分支最后展示且汇合节点只出现一次', () => {
  const graph=branch(), before=structuredClone(graph), view=projectQuickGraph(graph)
  assert.equal(view.reason,''); assert.deepEqual(graph,before)
  assert.deepEqual(view.sequence.steps.map(x=>x.nodeId),['start','gate','join','end'])
  const gateway=quickStep(view.sequence,'gate')
  assert.equal(gateway.joinId,'join');assert.deepEqual(gateway.branches.map(b=>b.edgeId),['condition','default'])
  assert.deepEqual(quickNodeIds(gateway.branches[0].sequence),['a'])
  assert.equal(gateway.branches[1].sequence.tailEdge,'default')
  assert.equal(new Set(quickNodeIds(view.sequence)).size,graph.nodes.length)
})

test('插入、删除和交换审批步骤保留标识、属性及上游分支条件', () => {
  const graph=branch(), before=structuredClone(graph)
  const inserted=edit(graph,{kind:'insert',edgeId:'condition',type:'USER_TASK'})
  const added=inserted.nodes.find(n=>!graph.nodes.some(x=>x.id===n.id))
  assert.equal(inserted.edges.find(e=>e.id==='condition').target,added.id)
  assert.equal(inserted.edges.find(e=>e.id==='condition').condition,'amount > 5000')
  assert.deepEqual(inserted.nodes.find(n=>n.id==='a'),graph.nodes.find(n=>n.id==='a'))
  const removed=edit(inserted,{kind:'removeTask',nodeId:added.id})
  assert.deepEqual(removed,graph);assert.deepEqual(graph,before)
  const swapped=edit(line(),{kind:'swapTasks',firstId:'a',secondId:'b'})
  assert.deepEqual(projectQuickGraph(swapped).sequence.steps.map(x=>x.nodeId),['start','b','a','end'])
  assert.deepEqual(edit(swapped,{kind:'swapTasks',firstId:'b',secondId:'a'}),line())
})

test('在共同后续前插入步骤只创建一次，条件块删除正确接回后继', () => {
  const graph=branch()
  const inserted=edit(graph,{kind:'insert',beforeNodeId:'join',type:'USER_TASK'})
  const added=inserted.nodes.find(n=>!graph.nodes.some(x=>x.id===n.id))
  assert.equal(inserted.edges.find(e=>e.id==='default').target,added.id)
  assert.equal(inserted.edges.find(e=>e.id==='a-join').target,added.id)
  const removed=edit(graph,{kind:'removeGateway',nodeId:'gate'})
  assert.deepEqual(removed.nodes.map(n=>n.id),['start','join','end'])
  assert.deepEqual(removed.edges.map(e=>[e.source,e.target]),[['start','join'],['join','end']])
})

test('新增嵌套条件、增删分支与判断优先级均作用于原边顺序', () => {
  let graph=edit(branch(),{kind:'insert',edgeId:'condition',type:'EXCLUSIVE_GATEWAY'})
  assert.ok(projectQuickGraph(graph).sequence)
  const nested=graph.nodes.find(n=>n.type==='EXCLUSIVE_GATEWAY'&&n.id!=='gate')
  assert.equal(quickStep(projectQuickGraph(graph).sequence,nested.id).joinId,'a')
  graph=edit(graph,{kind:'addBranch',nodeId:nested.id})
  const conditions=graph.edges.filter(e=>e.source===nested.id&&!e.defaultBranch)
  const moved=edit(graph,{kind:'moveBranch',nodeId:nested.id,edgeId:conditions[1].id,direction:-1})
  assert.deepEqual(moved.edges.filter(e=>e.source===nested.id&&!e.defaultBranch).map(e=>e.id),[conditions[1].id,conditions[0].id])
  const branchOwned=quickNodeIds(quickStep(projectQuickGraph(moved).sequence,nested.id).branches.find(b=>b.edgeId===conditions[1].id).sequence)
  const removed=edit(moved,{kind:'removeBranch',nodeId:nested.id,edgeId:conditions[1].id})
  assert.ok(removed.nodes.every(n=>!branchOwned.includes(n.id)));assert.ok(removed.nodes.some(n=>n.id==='a'))
  assert.throws(()=>edit(removed,{kind:'removeBranch',nodeId:nested.id,edgeId:conditions[0].id}),/至少保留/)
})

test('各分支独立结束时添加与删除条件仍形成完整流程', () => {
  const graph={nodes:[node('start','START'),node('gate','EXCLUSIVE_GATEWAY'),node('a','END'),node('b','END')],edges:[edge('one','start','gate'),edge('ca','gate','a','amount > 1'),edge('cb','gate','b','',true)]}
  assert.equal(quickStep(projectQuickGraph(graph).sequence,'gate').joinId,null)
  const expanded=edit(graph,{kind:'addBranch',nodeId:'gate'})
  assert.equal(expanded.nodes.filter(n=>n.type==='END').length,3)
  const deleted=edit(expanded,{kind:'removeGateway',nodeId:'gate'})
  assert.equal(deleted.nodes.length,2);assert.equal(deleted.edges.length,1)
})

test('复杂、回环和断线图失败关闭，原图没有被修剪或展平', () => {
  const disconnected=line();disconnected.nodes.push(node('orphan'))
  const cycle=line();cycle.edges[2].target='a'
  const conditionalLine=line();conditionalLine.edges[1].condition='amount > 1'
  const cross={nodes:[node('start','START'),node('g','EXCLUSIVE_GATEWAY'),node('h','EXCLUSIVE_GATEWAY'),node('a'),node('b'),node('end','END')],edges:[edge('1','start','g'),edge('2','g','h','amount > 1'),edge('3','g','a','',true),edge('4','h','a','amount > 2'),edge('5','h','b','',true),edge('6','a','end'),edge('7','b','end')]}
  for(const graph of [disconnected,cycle,conditionalLine,cross]) {
    const before=structuredClone(graph)
    assert.equal(projectQuickGraph(graph).sequence,null)
    assert.throws(()=>edit(graph,{kind:'removeTask',nodeId:'a'}))
    assert.deepEqual(graph,before)
  }
})

const fields=[{key:'amount',label:'金额',type:'NUMBER'},{key:'memo',label:'说明',type:'TEXT'},{key:'flag',label:'是否',type:'BOOLEAN'}]
test('条件值含 AND/OR 或单引号时保持一个字面量，大额数值不转浮点', () => {
  for(const source of ["memo == 'R AND D OR Finance'", 'memo == "O\'Reilly"', 'amount >= 99999999999999999999.123456789']) {
    const parsed=parseConditionRows(source,fields);assert.ok(parsed)
    const generated=serializeConditionRows(parsed,fields)
    assert.deepEqual(parseConditionRows(generated,fields),parsed)
  }
  assert.equal(serializeConditionRows(parseConditionRows('amount >= 99999999999999999999.123456789',fields),fields),'amount >= 99999999999999999999.123456789')
  assert.deepEqual(parseConditionRows('flag EXISTS AND memo NOT_EXISTS',fields),{join:'AND',rows:[{field:'flag',operator:'EXISTS',value:''},{field:'memo',operator:'NOT_EXISTS',value:''}]})
})

test('混合逻辑、未知字段与不可安全引用的值保留表达式且不制造注入', () => {
  for(const source of ['amount > 1 AND amount < 10 OR flag == true','unknown == 1','memo > abc',"memo == 'x' trailing",'amount > 1 AND']) assert.equal(parseConditionRows(source,fields),null)
  for(const value of ["' OR amount > 0 OR memo == \"x",'${bean.run()}', 'a;b']) {
    assert.equal(safeConditionLiteral(value),false)
    assert.throws(()=>serializeConditionRows({join:'AND',rows:[{field:'memo',operator:'==',value}]},fields))
  }
  assert.deepEqual(operatorsFor(fields[1]).map(x=>x.value),['==','!=','EXISTS','NOT_EXISTS'])
})

test('明细条件只提供存在性判断，默认选择与解析序列化一致', () => {
  const field = { key: 'items', label: '明细', type: 'TABLE', required: false, columns: [] }
  assert.deepEqual(operatorsFor(field).map(operator => operator.value), ['EXISTS', 'NOT_EXISTS'])
  assert.equal(parseConditionRows('', [field]).rows[0].operator, 'EXISTS')
  assert.equal(parseConditionRows("items == 'x'", [field]), null)
  assert.equal(serializeConditionRows({ join: 'AND', rows: [{ field: 'items', operator: 'NOT_EXISTS', value: '' }] }, [field]), 'items NOT_EXISTS')
  assert.throws(() => serializeConditionRows({ join: 'AND', rows: [{ field: 'items', operator: '==', value: 'x' }] }, [field]), /明细/)
})

test('新版枚举多选与引号转义无损回显，复杂表达式保留原文', () => {
  const typed = [...fields, {key:'category', label:'类型', type:'SELECT', options:[{value:'A',label:'甲'},{value:'B',label:'乙'}]}]
  for (const source of ['category IN ["A", "B"] && amount > "9007199254740993.123456789"', 'memo == "a (b) AND \\"c\\""', 'memo == "a\\\\b"']) {
    const parsed = parseConditionRows(source,typed,2)
    assert.ok(parsed,source)
    assert.deepEqual(parseConditionRows(serializeConditionRows(parsed,typed,2),typed,2),parsed)
  }
  assert.deepEqual(parseConditionRows('category in ["B","A"]',typed,2).rows[0].values,['B','A'])
  for (const source of ['(amount > 1)', '!flag EXISTS', 'amount > 1 AND flag EXISTS OR memo EXISTS', 'category IN []', 'category IN ["A",]', 'category IN ["UNKNOWN"]', 'memo IN ["A"]', 'memo == "x" trailing', 'memo == "\\u003b"']) {
    assert.equal(parseConditionRows(source,typed,2),null,source)
  }
  assert.equal(operatorsFor(typed[3],1).some(x=>x.value==='IN'),false)
  assert.equal(operatorsFor(typed[3],2).some(x=>x.value==='IN'),true)
  assert.throws(()=>serializeConditionRows({join:'AND',rows:[{field:'category',operator:'IN',value:'',values:['UNKNOWN']}]},typed,2))
  assert.equal(serializeConditionRows({join:'AND',rows:[{field:'amount',operator:'>',value:'9007199254740993.123456789'}]},typed,2),'amount > "9007199254740993.123456789"')
})

test('快速编辑保留图条件语言版本，历史缺省图不被隐式升级', () => {
  for (const version of [undefined,1,2]) {
    const graph=branch()
    if(version!==undefined)graph.conditionLanguageVersion=version
    assert.equal(edit(graph,{kind:'insert',edgeId:'condition',type:'USER_TASK'}).conditionLanguageVersion,version)
  }
})

test('新版存在性关键字需要单词边界，非法相连词不能被回显重写', () => {
  for (const source of ['memo EXISTSAND flag EXISTS', 'memo NOT_EXISTSOR flag EXISTS']) {
    assert.equal(parseConditionRows(source, fields, 2), null, source)
  }
})
