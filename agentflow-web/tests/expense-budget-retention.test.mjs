import test from 'node:test'
import assert from 'node:assert/strict'
import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_EXPENSEBUDGETRETENTIONSTATUS)
const retention = status => ({ roundNo: 2, stoppedStatus: 'WITHDRAWN', retainedAt: '2026-10-03T10:00:00Z', retentionDays: 3,
  expiresAt: '2026-10-06T10:00:00Z', status, releaseOperationId: null, issue: null, updatedAt: '2026-10-06T10:00:00Z' })
const render = value => renderToString(createSSRApp(Panel, { retention: value, stopped: true }))

test('到期和释放排队都不能显示实际已释放，保留期使用服务端固定期限', async () => {
  for (const status of ['RETAINED', 'RECONCILING', 'RELEASE_QUEUED']) {
    const html = await render(retention(status))
    assert.match(html, /第 2 轮/); assert.match(html, /保留 3 天/); assert.match(html, /datetime="2026-10-06T10:00:00Z"/)
    assert.doesNotMatch(html, /预算已确认释放/)
  }
  assert.match(await render(retention('RECONCILING')), /正在核对原预算操作/)
  assert.match(await render(retention('RELEASE_QUEUED')), /尚未确认结果/)
})

test('实际释放、显式拒绝和新轮次取代分别显示，不能暗示票据和借款也释放', async () => {
  const released = await render(retention('RELEASED'))
  assert.match(released, /预算已确认释放/); assert.match(released, /重新提交须重新预检/); assert.match(released, /此期限仅用于预算冻结/)
  const rejected = await render({ ...retention('RELEASE_REJECTED'), issue: 'LEDGER_VERSION_CONFLICT' })
  assert.match(rejected, /role="alert"/); assert.match(rejected, /预算释放未通过/); assert.doesNotMatch(rejected, /预算已确认释放/)
  assert.match(await render(retention('SUPERSEDED')), /本轮释放计划已失效/)
})

test('未捕获期限的退回单不编造企业默认规则，普通在审单不显示该提示', async () => {
  assert.match(await render(null), /本轮未配置预算到期释放期限/)
  const html = await renderToString(createSSRApp(Panel, { retention: null, stopped: false }))
  assert.doesNotMatch(html, /保留期|释放期限/)
})
