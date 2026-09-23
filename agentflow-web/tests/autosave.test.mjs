import test from 'node:test'
import assert from 'node:assert/strict'
const { DraftAutosave, AUTOSAVE_DELAY_MS } = await import(process.env.AGENTFLOW_TEST_AUTOSAVE)
const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve() }
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b }); return {promise,resolve,reject} }

test('连续编辑合并为停顿后的一个请求，保存期间的新内容排到下一次', async t => {
  t.mock.timers.enable({apis:['setTimeout']})
  const state={scope:'tenant:admin:1',snapshot:'A',eligible:true}, writes=[], pending=deferred()
  const saver=new DraftAutosave(()=>({...state}),async()=>{
    const sent=state.snapshot; writes.push(sent)
    if(writes.length===1) await pending.promise
    state.eligible=state.snapshot!==sent
  })
  saver.observe();t.mock.timers.tick(1500);state.snapshot='B';saver.observe();t.mock.timers.tick(1999)
  assert.deepEqual(writes,[])
  t.mock.timers.tick(1);assert.deepEqual(writes,['B']);assert.equal(saver.saving,true)
  state.snapshot='C';saver.observe();t.mock.timers.tick(10000);assert.deepEqual(writes,['B'])
  pending.resolve();await flush();assert.equal(saver.scheduled,true)
  t.mock.timers.tick(AUTOSAVE_DELAY_MS);await flush();assert.deepEqual(writes,['B','C'])
  assert.equal(saver.saving,false);assert.ok(saver.savedAt);assert.equal(saver.scheduled,false)
  saver.dispose()
})

test('清洁、只读、关闭、组合输入、拖动与操作锁均由准入条件取消计划', async t => {
  t.mock.timers.enable({apis:['setTimeout']})
  const state={scope:'one',snapshot:'A',eligible:true};let count=0
  const saver=new DraftAutosave(()=>({...state}),async()=>{count++;state.eligible=false})
  saver.observe();state.eligible=false;saver.observe();t.mock.timers.tick(10000);assert.equal(count,0)
  state.eligible=true;saver.observe();t.mock.timers.tick(AUTOSAVE_DELAY_MS);await flush();assert.equal(count,1)
  saver.dispose()
})

test('定时器执行前复核会话和快照，未收到观察通知也不保存错误设计', t => {
  t.mock.timers.enable({apis:['setTimeout']})
  const state={scope:'one',snapshot:'A',eligible:true};let count=0
  const saver=new DraftAutosave(()=>({...state}),async()=>{count++})
  saver.observe();state.scope='two';t.mock.timers.tick(AUTOSAVE_DELAY_MS);assert.equal(count,0)
  saver.observe();state.snapshot='B';t.mock.timers.tick(AUTOSAVE_DELAY_MS);assert.equal(count,0)
  saver.dispose()
})

test('冲突、校验与不确定网络失败都暂停，编辑或切换自动保存开关不偷偷重试', async t => {
  t.mock.timers.enable({apis:['setTimeout']})
  for(const error of [{status:409,code:'CONCURRENCY_CONFLICT',message:'conflict'},{status:422,code:'INVALID_FORM_SCHEMA',message:'invalid'},{status:0,code:'NETWORK_ERROR',message:'unknown'}]) {
    const state={scope:'one',snapshot:'A',eligible:true};let count=0
    const saver=new DraftAutosave(()=>({...state}),async()=>{count++;throw error})
    saver.observe();t.mock.timers.tick(AUTOSAVE_DELAY_MS);await flush()
    assert.deepEqual(saver.failure,error);assert.equal(saver.scheduled,false)
    state.snapshot='B';saver.observe();state.eligible=false;saver.observe();state.eligible=true;saver.observe()
    t.mock.timers.tick(10000);assert.equal(count,1)
    state.eligible=false;saver.saved();saver.observe();assert.equal(saver.failure,null)
    saver.dispose()
  }
})

test('切换设计后旧成功或失败不能覆盖新会话状态；卸载不会恢复定时器', async t => {
  t.mock.timers.enable({apis:['setTimeout']})
  for(const outcome of ['success','error']) {
    const pending=deferred(),state={scope:'one',snapshot:'A',eligible:true};let count=0
    const saver=new DraftAutosave(()=>({...state}),async()=>{count++;await pending.promise})
    saver.observe();t.mock.timers.tick(AUTOSAVE_DELAY_MS)
    state.scope='two';state.eligible=false;saver.reset()
    if(outcome==='success')pending.resolve();else pending.reject({status:503,code:'OFFLINE',message:'old'})
    await flush();assert.equal(saver.failure,null);assert.equal(saver.savedAt,null);assert.equal(saver.saving,false)
    state.eligible=true;saver.observe();saver.dispose();t.mock.timers.tick(10000);assert.equal(count,1)
  }
})
