import test from 'node:test'
import assert from 'node:assert/strict'
const {DesignerValidation}=await import(process.env.AGENTFLOW_TEST_VALIDATION)
const graph=()=>({nodes:[],edges:[],conditionLanguageVersion:2})
const deferred=()=>{let resolve,reject;const promise=new Promise((ok,no)=>{resolve=ok;reject=no});return{promise,resolve,reject}}

test('校验发送不可变设计快照，编辑取消旧结果与高亮，迟到响应无效',async()=>{
  const calls=[]
  const view=new DesignerValidation((g,s,signal)=>{const call={g,s,signal,...deferred()};calls.push(call);return call.promise})
  const input=graph(), first=view.run(input,null)
  input.edges.push({id:'new'})
  assert.equal(calls[0].g.edges.length,0)
  view.clear();assert.equal(calls[0].signal.aborted,true)
  const next=view.run(graph(),null)
  const current={errors:[],branchDiagnostics:[{severity:'WARNING',code:'BRANCH_OVERLAP'}]}
  calls[1].resolve(current);assert.deepEqual(await next,current)
  calls[0].resolve({errors:['stale'],branchDiagnostics:[]});assert.equal(await first,undefined)
  assert.deepEqual(view.result,current)
})

test('切换账号与卸载后的迟到失败不会恢复旧校验，错误和超时都不能放行发布',async()=>{
  const wait=deferred(),view=new DesignerValidation(()=>wait.promise)
  const pending=view.run(graph(),null);view.clear();wait.reject(new Error('旧账号失败'));await pending
  assert.equal(view.error,'');assert.equal(view.result,null);assert.equal(view.loading,false)
  const failed=new DesignerValidation(async()=>{throw new Error('校验不可用')})
  assert.equal(await failed.run(graph(),null),undefined);assert.equal(failed.error,'校验不可用')
  const stuck=new DesignerValidation(()=>new Promise(()=>{}),5)
  assert.equal(await stuck.run(graph(),null),undefined);assert.match(stuck.error,/超时/);assert.equal(stuck.loading,false)
})
