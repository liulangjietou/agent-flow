import test from 'node:test'
import assert from 'node:assert/strict'
const { projectQuickGraph, editQuickGraph, quickNodeIds, quickStep } = await import(process.env.AGENTFLOW_TEST_QUICK)
const { parseConditionRows, serializeConditionRows, safeConditionLiteral, operatorsFor } = await import(process.env.AGENTFLOW_TEST_CONDITIONS)
const node = (id, type='USER_TASK') => ({ id, name:id, type, properties:{ assigneeRule:'role:MANAGER', retained:'原属性', x:'123', y:'456' } })
const edge = (id, source, target, condition='', defaultBranch=false) => ({id, source,target,condition,defaultBranch})
const line = () => ({nodes:[node('start','START'),node('a'),node('b'),node('end','END')],edges:[edge('one','start','a'),edge('two','a','b'),edge('three','b','end')]})
const branch = () => ({nodes:[node('start','START'),node('gate','EXCLUSIVE_GATEWAY'),node('a'),node('join'),node('end','END')],edges:[edge('one','start','gate'),edge('default','gate','join','',true),edge('condition','gate','a','amount > 5000'),edge('a-join','a','join'),edge('join-end','join','end')]})
const parallel = () => ({nodes:[node('start','START'),node('fork','PARALLEL_GATEWAY'),node('a'),node('b'),node('join','PARALLEL_GATEWAY'),node('end','END')],edges:[edge('sf','start','fork'),edge('fa','fork','a'),edge('fb','fork','b'),edge('aj','a','join'),edge('bj','b','join'),edge('je','join','end')]})
let counter=0
const edit = (graph, command) => editQuickGraph(graph,command,()=>String(++counter))

test('子流程在直线与并行分支中插入、移动和删除，固定版本及输入映射不变', () => {
  for (const [graph, edgeId] of [[line(), 'two'], [parallel(), 'aj']]) {
    const inserted = edit(graph, { kind: 'insert', edgeId, type: 'SUB_PROCESS' })
    const call = inserted.nodes.find(node => node.type === 'SUB_PROCESS')
    assert.ok(call)
    assert.equal(call.properties.subprocessKey, undefined)
    Object.assign(call.properties, { subprocessKey: 'child', subprocessVersion: '1', 'subprocessInput.total': 'amount' })
    assert.equal(projectQuickGraph(inserted).reason, '')
    assert.equal(quickNodeIds(projectQuickGraph(inserted).sequence).filter(id => id === call.id).length, 1)
    const moved = edit(inserted, { kind: 'swapTasks', firstId: 'a', secondId: call.id })
    assert.deepEqual(moved.nodes.find(node => node.id === call.id).properties, call.properties)
    assert.deepEqual(edit(inserted, { kind: 'removeTask', nodeId: call.id }), graph)
  }
})

test('抄送可在直线和并行分支插入、移动、删除且保留收件规则', () => {
  for (const [graph, edgeId] of [[line(), 'two'], [parallel(), 'aj']]) {
    const inserted = edit(graph, { kind: 'insert', edgeId, type: 'COPY' })
    const copy = inserted.nodes.find(node => node.type === 'COPY')
    copy.properties.recipientRule = 'user:bob'
    assert.equal(projectQuickGraph(inserted).reason, '')
    assert.equal(quickNodeIds(projectQuickGraph(inserted).sequence).filter(id => id === copy.id).length, 1)
    assert.deepEqual(edit(inserted, { kind: 'removeTask', nodeId: copy.id }), graph)
  }
  const inserted = edit(line(), { kind: 'insert', edgeId: 'two', type: 'COPY' })
  const copy = inserted.nodes.find(node => node.type === 'COPY')
  copy.properties.recipientRule = 'user:bob'
  const moved = edit(inserted, { kind: 'swapTasks', firstId: 'a', secondId: copy.id })
  assert.deepEqual(quickNodeIds(projectQuickGraph(moved).sequence), ['start', copy.id, 'a', 'b', 'end'])
  assert.equal(moved.nodes.find(node => node.id === copy.id).properties.recipientRule, 'user:bob')
})

test('并行投影完整保留原图，拆分与汇合各出现一次', () => {
  const graph=parallel(), before=structuredClone(graph), view=projectQuickGraph(graph)
  assert.equal(view.reason,'')
  assert.deepEqual(graph,before)
  assert.deepEqual(view.sequence.steps.map(step=>step.nodeId),['start','fork','join','end'])
  assert.equal(quickStep(view.sequence,'fork').joinId,'join')
  assert.deepEqual(quickStep(view.sequence,'fork').branches.map(branch=>branch.sequence.tailEdge),['aj','bj'])
  assert.equal(new Set(quickNodeIds(view.sequence)).size,graph.nodes.length)
})

test('插入和整体删除并行块可还原原图，节点属性及条件语言版本保持', () => {
  const graph={...line(),conditionLanguageVersion:2},before=structuredClone(graph)
  const inserted=edit(graph,{kind:'insert',edgeId:'two',type:'PARALLEL_GATEWAY'})
  const fork=inserted.nodes.find(node=>node.type==='PARALLEL_GATEWAY'&&inserted.edges.filter(edge=>edge.source===node.id).length===2)
  const step=quickStep(projectQuickGraph(inserted).sequence,fork.id)
  assert.equal(step.branches.length,2)
  assert.equal(inserted.nodes.find(node=>node.id===step.joinId).type,'PARALLEL_GATEWAY')
  assert.ok(inserted.edges.every(edge=>!edge.condition&&!edge.defaultBranch))
  assert.deepEqual(edit(inserted,{kind:'removeGateway',nodeId:fork.id}),graph)
  assert.deepEqual(graph,before)
})

test('并行增删和左右排序保留其余分支，至少保留两条路径', () => {
  const graph=parallel(), expanded=edit(graph,{kind:'addBranch',nodeId:'fork'})
  const added=expanded.edges.find(edge=>edge.source==='fork'&&!graph.edges.some(old=>old.id===edge.id))
  const moved=edit(expanded,{kind:'moveBranch',nodeId:'fork',edgeId:added.id,direction:-1})
  assert.deepEqual(quickStep(projectQuickGraph(moved).sequence,'fork').branches.map(branch=>branch.edgeId),['fa',added.id,'fb'])
  const removed=edit(moved,{kind:'removeBranch',nodeId:'fork',edgeId:added.id})
  assert.deepEqual(removed.nodes,graph.nodes)
  assert.deepEqual(removed.edges.filter(edge=>edge.source==='fork'),graph.edges.filter(edge=>edge.source==='fork'))
  assert.deepEqual([...removed.edges].sort((a,b)=>a.id.localeCompare(b.id)),[...graph.edges].sort((a,b)=>a.id.localeCompare(b.id)))
  assert.throws(()=>edit(graph,{kind:'removeBranch',nodeId:'fork',edgeId:'fa'}),/至少保留/)
  assert.throws(()=>edit(graph,{kind:'addBranch',nodeId:'join'}),/汇合节点/)
})

test('嵌套并行删除只删除所属区域，不删除共同后继', () => {
  const graph=parallel(), nested=edit(graph,{kind:'insert',edgeId:'fa',type:'PARALLEL_GATEWAY'})
  const view=projectQuickGraph(nested)
  assert.equal(view.reason,'')
  assert.equal(new Set(quickNodeIds(view.sequence)).size,nested.nodes.length)
  const inner=nested.nodes.find(node=>node.type==='PARALLEL_GATEWAY'&&!graph.nodes.some(old=>old.id===node.id))
  assert.deepEqual(edit(nested,{kind:'removeGateway',nodeId:inner.id}),graph)
  const removed=edit(nested,{kind:'removeGateway',nodeId:'fork'})
  assert.deepEqual(removed.nodes.map(node=>node.id),['start','end'])
  assert.deepEqual(removed.edges.map(edge=>[edge.source,edge.target]),[['start','end']])
})

test('并行入口或分支末尾插入条件时建立互斥汇合，避免缺少并行令牌', () => {
  for(const edgeId of ['sf','aj']) {
    const graph=parallel(), inserted=edit(graph,{kind:'insert',edgeId,type:'EXCLUSIVE_GATEWAY'})
    const fork=inserted.nodes.find(node=>node.type==='EXCLUSIVE_GATEWAY'&&inserted.edges.filter(edge=>edge.source===node.id).length===2)
    const merge=inserted.nodes.find(node=>node.type==='EXCLUSIVE_GATEWAY'&&node.id!==fork.id)
    assert.ok(merge)
    assert.equal(quickStep(projectQuickGraph(inserted).sequence,fork.id).joinId,merge.id)
    assert.deepEqual(inserted.edges.filter(edge=>edge.source===merge.id).map(edge=>[edge.target,edge.condition,edge.defaultBranch]),[[edgeId==='sf'?'fork':'join','',false]])
    assert.deepEqual(edit(inserted,{kind:'removeGateway',nodeId:fork.id}),graph)
  }
})

test('共同插入不能绕过并行汇合，分支内插入和汇合后插入仍可用', () => {
  const graph=parallel(),before=structuredClone(graph)
  assert.throws(()=>edit(graph,{kind:'insert',beforeNodeId:'join',type:'USER_TASK'}),/不能绕过汇合/)
  assert.equal(projectQuickGraph(edit(graph,{kind:'insert',edgeId:'aj',type:'USER_TASK'})).reason,'')
  assert.equal(projectQuickGraph(edit(graph,{kind:'insert',edgeId:'je',type:'USER_TASK'})).reason,'')
  assert.deepEqual(graph,before)
})

test('一个网关先汇合再拆分时，删除任一块均保留另一块的职责', () => {
  const base=parallel(),graph=structuredClone(base)
  graph.nodes.push(node('c'),node('d'),node('lastJoin','PARALLEL_GATEWAY'))
  graph.edges=graph.edges.filter(edge=>edge.id!=='je')
  graph.edges.push(edge('jc','join','c'),edge('jd','join','d'),edge('cl','c','lastJoin'),edge('dl','d','lastJoin'),edge('le','lastJoin','end'))
  assert.equal(projectQuickGraph(graph).reason,'')
  const removeFirst=edit(graph,{kind:'removeGateway',nodeId:'fork'})
  assert.deepEqual(removeFirst.nodes.map(node=>node.id),['start','join','end','c','d','lastJoin'])
  assert.equal(quickStep(projectQuickGraph(removeFirst).sequence,'join').branches.length,2)
  const removeSecond=edit(graph,{kind:'removeGateway',nodeId:'join'})
  assert.deepEqual(removeSecond.nodes,base.nodes)
  assert.deepEqual(removeSecond.edges.map(edge=>[edge.source,edge.target]),base.edges.map(edge=>[edge.source,edge.target]))
})

test('错接并行、互斥入口和带条件的并行连线均保留原图并退出快速编辑', () => {
  const ordinary=parallel();ordinary.nodes.find(node=>node.id==='join').type='USER_TASK'
  const missing=parallel();missing.edges.find(edge=>edge.id==='bj').target='end'
  const conditional=parallel();conditional.edges.find(edge=>edge.id==='fa').condition='amount > 1'
  const exclusive=parallel();exclusive.nodes.find(node=>node.id==='fork').type='EXCLUSIVE_GATEWAY'
  exclusive.edges.find(edge=>edge.id==='fa').condition='amount > 1';exclusive.edges.find(edge=>edge.id==='fb').defaultBranch=true
  for(const graph of [ordinary,missing,conditional,exclusive]) {
    const before=structuredClone(graph)
    assert.equal(projectQuickGraph(graph).sequence,null)
    assert.throws(()=>edit(graph,{kind:'removeTask',nodeId:'a'}))
    assert.deepEqual(graph,before)
  }
})

test('删除或前插并行时保留审批步骤原本承担的互斥汇合', () => {
  const graph=parallel()
  graph.nodes.push(node('choice','EXCLUSIVE_GATEWAY'),node('optional'))
  graph.edges=graph.edges.filter(edge=>edge.id!=='fa')
  graph.edges.push(edge('fc','fork','choice'),edge('co','choice','optional','amount > 10'),edge('ca','choice','a','',true),edge('oa','optional','a'))
  assert.equal(projectQuickGraph(graph).reason,'')
  const removed=edit(graph,{kind:'removeTask',nodeId:'a'})
  assert.equal(projectQuickGraph(removed).reason,'')
  const merge=removed.nodes.find(node=>node.type==='EXCLUSIVE_GATEWAY'&&node.id!=='choice')
  assert.ok(merge)
  assert.ok(removed.edges.some(edge=>edge.source===merge.id&&edge.target==='join'))
  const inserted=edit(graph,{kind:'insert',beforeNodeId:'a',type:'PARALLEL_GATEWAY'})
  assert.equal(projectQuickGraph(inserted).reason,'')
  assert.equal(inserted.edges.filter(edge=>edge.target==='a').length,1)
})

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
