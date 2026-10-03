import test from 'node:test'
import assert from 'node:assert/strict'
import { pathToFileURL } from 'node:url'
const { taskDeadlineState } = await import(pathToFileURL(process.env.AGENTFLOW_TEST_DEADLINE).href)

test('期限按真实时刻比较，在精确到期边界进入超时', () => {
  const due = '2026-09-29T09:30:00Z'
  const time = Date.parse(due)
  assert.equal(taskDeadlineState(due, time - 1), 'pending')
  assert.equal(taskDeadlineState(due, time), 'overdue')
  assert.equal(taskDeadlineState('2026-09-29T17:30:00+08:00', time), 'overdue')
})

test('历史缺失与无效时间分别提示，不伪造期限或正常状态', () => {
  assert.equal(taskDeadlineState(undefined, Date.now()), 'unrecorded')
  assert.equal(taskDeadlineState(null, Date.now()), 'unrecorded')
  assert.equal(taskDeadlineState('invalid', Date.now()), 'unknown')
})
