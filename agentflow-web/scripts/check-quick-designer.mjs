import assert from 'node:assert/strict'
import { readFileSync, writeFileSync, mkdtempSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { randomUUID } from 'node:crypto'
import ts from 'typescript'

// 只向明确指定的独立演示环境写入合成流程与申请，所有验收数据保留。
const base = new URL(process.argv[2])
assert.ok(['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname) && base.pathname === '/' && process.argv[3] === '--exercise')
const root = fileURLToPath(new URL('../', import.meta.url)), temporary = mkdtempSync('/fyoung/tmp/agentflow-quick-runtime-')
writeFileSync(resolve(temporary, 'package.json'), '{"type":"module"}')
for (const name of ['quickDesigner','conditionBuilder','formSchema']) writeFileSync(resolve(temporary,name+'.js'), ts.transpileModule(readFileSync(resolve(root,'src/'+name+'.ts'),'utf8'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText)
const { editQuickGraph, projectQuickGraph } = await import(pathToFileURL(resolve(temporary,'quickDesigner.js')))
const { serializeConditionRows } = await import(pathToFileURL(resolve(temporary,'conditionBuilder.js')))
const tokens = {}, key = 'quick-' + randomUUID().slice(0,8)
async function call(method,path,user='admin',body,status=200) {
  const headers={'Content-Type':'application/json'}
  if(tokens[user]) headers.Authorization='Bearer '+tokens[user]
  if(method!=='GET'&&!path.startsWith('/auth/')) headers['Idempotency-Key']=randomUUID()
  const response=await fetch(base.origin+'/api/v1'+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)})
  const value=await response.json();assert.equal(response.status,status,JSON.stringify({path,code:value.code}));return value
}
for(const user of ['admin','alice','manager','finance','bob']) tokens[user]=(await call('POST','/auth/login','anonymous',{tenantId:'demo',username:user,password:'demo'})).token
const formSchema={schemaVersion:1,fields:[{key:'amount',label:'申请金额',type:'NUMBER',required:true,minimum:'0'},{key:'memo',label:'申请说明',type:'TEXT',required:false,maxLength:256}]}
let graph={nodes:[{id:'start',name:'开始',type:'START',properties:{}},{id:'end',name:'结束',type:'END',properties:{}}],edges:[{id:'initial',source:'start',target:'end',condition:'',defaultBranch:false}]}
graph=editQuickGraph(graph,{kind:'insert',edgeId:'initial',type:'USER_TASK'})
const manager=graph.nodes.find(n=>n.type==='USER_TASK');manager.name='经理审批';manager.properties.assigneeRule='role:MANAGER'
graph=editQuickGraph(graph,{kind:'insert',beforeNodeId:'end',type:'EXCLUSIVE_GATEWAY'})
const gate=graph.nodes.find(n=>n.type==='EXCLUSIVE_GATEWAY'), finance=graph.nodes.find(n=>n.type==='USER_TASK'&&n.id!==manager.id)
gate.name='按金额分流';finance.name='财务会签';finance.properties={assigneeRule:'role:FINANCE',approvalMode:'ALL'}
const condition=graph.edges.find(e=>e.source===gate.id&&!e.defaultBranch)
condition.condition=serializeConditionRows({join:'AND',rows:[{field:'amount',operator:'>',value:'5000'}]},formSchema.fields)
graph=editQuickGraph(graph,{kind:'addBranch',nodeId:gate.id})
const urgent=graph.nodes.find(n=>n.type==='USER_TASK'&&n.id!==manager.id&&n.id!==finance.id)
urgent.name='高额复核';urgent.properties.assigneeRule='user:bob'
const urgentEdge=graph.edges.find(e=>e.source===gate.id&&e.target===urgent.id)
urgentEdge.condition=serializeConditionRows({join:'AND',rows:[{field:'amount',operator:'>',value:'10000'}]},formSchema.fields)
graph=editQuickGraph(graph,{kind:'moveBranch',nodeId:gate.id,edgeId:urgentEdge.id,direction:-1})
assert.ok(projectQuickGraph(graph).sequence)
assert.deepEqual((await call('POST','/process-definitions/validate','admin',{graph,formSchema})).errors,[])
for(const [amount,target] of [['100',null],['6000',finance.id],['20000',urgent.id]]) {
  const result=await call('POST','/process-definitions/simulate','admin',{graph,formSchema,values:{amount,memo:'R AND D'}})
  assert.equal(result.path.includes(finance.id),target===finance.id);assert.equal(result.path.includes(urgent.id),target===urgent.id)
}
const draft=await call('POST','/process-definitions','admin',{key,name:'快速设计器运行验收',graph,formSchema})
await call('POST','/process-definitions/'+draft.id+'/publish?expectedRevision=0','admin',{changeNote:'验证快速模式的真实分支顺序'})
const cases=[]
for(const amount of ['100','6000','20000']) {
  let app=await call('POST','/applications','alice',{businessNo:key+'-'+amount,processKey:key,definitionVersion:1,title:'快速设计验收 '+amount,payload:{amount,memo:'合成验收数据'}},201)
  app=await call('POST','/applications/'+app.id+'/submit','alice',{expectedVersion:app.version})
  for(const user of amount==='100'?['manager']:amount==='6000'?['manager','finance','admin']:['manager','bob']) {
    const available=await call('GET','/tasks',user),task=available.find(t=>t.applicationId===app.id)
    assert.ok(task,`missing task for ${user}`)
    await call('POST','/tasks/'+task.taskId+'/actions',user,{action:'APPROVE',expectedVersion:app.version,comment:'快速设计器验收'})
    app=await call('GET','/applications/'+app.id,'alice')
  }
  assert.equal(app.status,'APPROVED');cases.push({amount,id:app.id,status:app.status})
}
const editable=await call('POST','/process-definitions','admin',{key:key+'-edit',name:'快速设计器浏览器验收',graph,formSchema})
console.log(JSON.stringify({result:'PASS',base:base.origin,processKey:key,publishedId:draft.id,editableId:editable.id,cases}))
