import test from 'node:test'
import assert from 'node:assert/strict'
const { FirstWorkflowQuery, workflowSteps, guideHidden, hideGuide, guideSelection, rememberGuideSelection } = await import(process.env.AGENTFLOW_TEST_GUIDE)

test('步骤只依据真实保存、发布和轮次证据，草稿和未批准结果不冒充完成', () => {
  assert.deepEqual(workflowSteps({submittedRounds:0,approvedRounds:0}), [false,false,false,false])
  assert.deepEqual(workflowSteps({definition:{version:0},submittedRounds:0,approvedRounds:0}), [true,false,false,false])
  assert.deepEqual(workflowSteps({definition:{version:1},submittedRounds:3,approvedRounds:0}), [true,true,true,false])
  assert.deepEqual(workflowSteps({definition:{version:1},submittedRounds:3,approvedRounds:1}), [true,true,true,true])
})

test('引导偏好和选择按租户账号隔离，不存储完成状态', () => {
  const values = new Map(), storage = {getItem:key=>values.get(key)??null,setItem:(key,value)=>values.set(key,value)}
  const first='["demo","admin"]', other='["other","admin"]'
  assert.equal(guideHidden(first,storage),false)
  hideGuide(first,true,storage); rememberGuideSelection(first,'definition-a',storage)
  assert.equal(guideHidden(first,storage),true);assert.equal(guideHidden(other,storage),false)
  assert.equal(guideSelection(first,storage),'definition-a');assert.equal(guideSelection(other,storage),'')
  hideGuide(first,false,storage);assert.equal(guideHidden(first,storage),false)
  const denied={getItem:()=>{throw Error('denied')},setItem:()=>{throw Error('denied')}}
  assert.equal(guideHidden(first,denied),false);assert.equal(guideSelection(first,denied),'')
  assert.doesNotThrow(()=>hideGuide(first,true,denied));assert.doesNotThrow(()=>rememberGuideSelection(first,'id',denied))
})

test('浏览器拒绝获取 localStorage 本身时仍可打开引导', t => {
  const descriptor=Object.getOwnPropertyDescriptor(globalThis,'localStorage')
  t.after(()=>{if(descriptor)Object.defineProperty(globalThis,'localStorage',descriptor);else delete globalThis.localStorage})
  Object.defineProperty(globalThis,'localStorage',{configurable:true,get(){throw Error('SecurityError')}})
  assert.equal(guideHidden('demo:admin'),false);assert.equal(guideSelection('demo:admin'),'')
  assert.doesNotThrow(()=>hideGuide('demo:admin',true));assert.doesNotThrow(()=>rememberGuideSelection('demo:admin','id'))
})

test('账号和版本切换立即清空旧证据，迟到响应不能覆盖新进度', async () => {
  const requests=[]
  const query=new FirstWorkflowQuery((id,signal)=>new Promise((resolve,reject)=>requests.push({id,signal,resolve,reject})))
  const old=query.load('demo:admin','old'), current=query.load('other:admin','new')
  assert.equal(requests[0].signal.aborted,true)
  requests[1].resolve({approvedRounds:0});await current
  requests[0].resolve({approvedRounds:10});await old
  assert.equal(query.report.approvedRounds,0)
  const stale=query.load('other:admin','stale');query.clear();requests[2].reject({message:'old error'});await stale
  assert.equal(query.report,null);assert.equal(query.error,'');assert.equal(query.loading,false)
  await query.load('','');assert.equal(requests.length,3)
})

test('失败不保留已成功的进度，超时迟到成功也不显示完成', async t => {
  let fail=false
  const query=new FirstWorkflowQuery(async()=>{if(fail)throw {message:'服务不可用'};return {approvedRounds:1}})
  await query.load('demo:admin','id');fail=true;await query.load('demo:admin','id')
  assert.equal(query.report,null);assert.equal(query.error,'服务不可用')
  t.mock.timers.enable({apis:['setTimeout']})
  let finish
  const timeout=new FirstWorkflowQuery(()=>new Promise(resolve=>{finish=resolve}))
  const pending=timeout.load('demo:admin','id');t.mock.timers.tick(12000);finish({approvedRounds:1});await pending
  assert.equal(timeout.report,null);assert.match(timeout.error,/超时/)
})

test('进度 API 只读、认证、可取消；定义标识编码且空选择不发送空参数', async () => {
  globalThis.localStorage={getItem:()=> 'guide-token'}
  const {api}=await import(process.env.AGENTFLOW_TEST_API)
  const calls=[]
  globalThis.fetch=async(url,init)=>{calls.push({url,...init});return Response.json({})}
  const controller=new AbortController()
  await api.firstWorkflow('id/a&b',controller.signal);await api.firstWorkflow('',controller.signal)
  assert.equal(new URL(calls[0].url,'http://localhost').searchParams.get('definitionId'),'id/a&b')
  assert.ok(calls[1].url.endsWith('/system/first-workflow'))
  for(const call of calls){assert.equal(call.signal,controller.signal);assert.equal(call.headers.get('Authorization'),'Bearer guide-token');assert.equal(call.headers.has('Idempotency-Key'),false);assert.equal(call.body,undefined)}
})
