import test from 'node:test'
import assert from 'node:assert/strict'
import { pathToFileURL } from 'node:url'

const { UnsavedConfirmation } = await import(pathToFileURL(process.env.AGENTFLOW_TEST_CONFIRMATION))

test('未答复时不执行，取消保留内容，只有确认才放行一次', async () => {
  const confirmation = new UnsavedConfirmation()
  let calls = 0
  const original = confirmation.confirm('放弃修改并切换', () => true).then(allowed => { if (allowed) calls++ })
  await Promise.resolve()
  assert.equal(calls, 0)
  confirmation.answer(confirmation.active.id, false)
  await original
  assert.equal(calls, 0)
  assert.equal(confirmation.active, null)
  const accepted = confirmation.confirm('放弃修改并新建', () => true).then(allowed => { if (allowed) calls++ })
  const id = confirmation.active.id
  confirmation.answer(id, true)
  confirmation.answer(id, true)
  await accepted
  assert.equal(calls, 1)
})

test('双击与其他并发操作不能覆盖或共享原待确认意图', async () => {
  const confirmation = new UnsavedConfirmation()
  const first = confirmation.confirm('放弃修改并复制', () => true)
  const active = confirmation.active
  assert.equal(await confirmation.confirm('放弃修改并退出', () => true), false)
  assert.equal(await confirmation.confirm('放弃修改并复制', () => true), false)
  assert.equal(confirmation.active, active)
  assert.equal(confirmation.active.confirmLabel, '放弃修改并复制')
  confirmation.answer(active.id, true)
  assert.equal(await first, true)
})

test('上一对话框的延迟答复不能确认后来打开的操作', async () => {
  const confirmation = new UnsavedConfirmation()
  const first = confirmation.confirm('切换', () => true)
  const oldId = confirmation.active.id
  confirmation.cancel()
  assert.equal(await first, false)
  const next = confirmation.confirm('退出', () => true)
  confirmation.answer(oldId, true)
  assert.ok(confirmation.active)
  confirmation.answer(confirmation.active.id, false)
  assert.equal(await next, false)
})

test('等待期间账号或业务锁变化后，确认不会放行旧操作', async () => {
  for (const change of ['actor', 'busy', 'writesBlocked']) {
    const confirmation = new UnsavedConfirmation()
    const state = { actor: 'original', busy: false, writesBlocked: false }
    const allowed = () => state.actor === 'original' && !state.busy && !state.writesBlocked
    const pending = confirmation.confirm('继续', allowed)
    state[change] = change === 'actor' ? 'other' : true
    confirmation.answer(confirmation.active.id, true)
    assert.equal(await pending, false)
  }
})

test('卸载取消待答Promise，已答复但未恢复的延迟操作同样失效', async () => {
  const confirmation = new UnsavedConfirmation()
  const pending = confirmation.confirm('退出', () => true)
  confirmation.dispose()
  assert.equal(await pending, false)
  assert.equal(confirmation.active, null)
  assert.equal(await confirmation.confirm('复制', () => true), false)
  const late = new UnsavedConfirmation()
  const accepted = late.confirm('新建', () => true)
  late.answer(late.active.id, true)
  late.dispose()
  assert.equal(await accepted, false)
})

test('无未保存修改时直接放行，但准入失败或已有确认时不启动第二操作', async () => {
  const confirmation = new UnsavedConfirmation()
  assert.equal(await confirmation.confirm('打开', () => true, false), true)
  assert.equal(confirmation.active, null)
  assert.equal(await confirmation.confirm('打开', () => false, false), false)
  const pending = confirmation.confirm('退出', () => true)
  assert.equal(await confirmation.confirm('打开', () => true, false), false)
  confirmation.cancel()
  await pending
})
