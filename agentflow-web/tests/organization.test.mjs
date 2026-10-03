import test from 'node:test'
import assert from 'node:assert/strict'
const { emptyOrganizationDraft, organizationForm, organizationPayload, organizationPath, organizationDirty, OrganizationDrafts } = await import(process.env.AGENTFLOW_TEST_ORGANIZATION)

test('人员主体保留原值，编辑请求不替换稳定身份或系统角色', () => {
  const draft = emptyOrganizationDraft('PERSON')
  draft.form.name = ' 审批人 '; draft.form.subject = 'realm:opaque/主体'; draft.form.approvalEligible = true
  assert.deepEqual(organizationPayload(draft), { subject: 'realm:opaque/主体', displayName: '审批人', active: true, approvalEligible: true })
  draft.baseline = { id: 'p1', subject: 'realm:opaque/主体', displayName: '审批人', active: true, approvalEligible: true, revision: 2 }
  draft.form.subject = '伪造的新身份'
  const body = organizationPayload(draft)
  assert.equal(body.subject, undefined); assert.equal(body.roles, undefined); assert.equal(body.expectedRevision, 2)
})

test('结束任职只提交在用状态和原版本，不覆盖原关系', () => {
  const baseline = { id: 'a1', personId: 'p', departmentId: 'd', positionId: 'j', active: true, revision: 3 }
  const draft = { section: 'APPOINTMENT', baseline, form: organizationForm(baseline) }
  draft.form.active = false; draft.form.departmentId = 'other'
  assert.deepEqual(organizationPayload(draft), { active: false, expectedRevision: 3 })
  assert.equal(organizationPath(draft), '/organization/appointments/a1')
})

test('组织草稿按账号隔离，恢复不会覆盖发送后的修改或另一条记录', () => {
  const drafts = new OrganizationDrafts(), value = emptyOrganizationDraft('DEPARTMENT')
  assert.equal(organizationDirty(value), false)
  value.form.name = '部门'; value.form.legalEntityId = 'l1'; drafts.put('tenant:admin', value)
  const body = JSON.stringify(organizationPayload(value))
  const saved = { id: 'd1', kind: 'DEPARTMENT', name: '部门', legalEntityId: 'l1', active: true, revision: 1 }
  assert.equal(drafts.get('other:admin'), null)
  assert.equal(drafts.acknowledge('tenant:admin', '/organization/people', body, saved), false)
  value.form.name = '后续修改'; drafts.put('tenant:admin', value)
  assert.equal(drafts.acknowledge('tenant:admin', '/organization/units', body, saved), false)
  assert.equal(drafts.get('tenant:admin').baseline, null)
  value.form.name = '部门'; drafts.put('tenant:admin', value)
  assert.equal(drafts.acknowledge('tenant:admin', '/organization/units', body, saved), true)
  assert.equal(drafts.hasDrafts(), false); assert.equal(drafts.get('tenant:admin').baseline.revision, 1)
})

test('本地组织保存结果未知时复用原正文与幂等键', async () => {
  globalThis.localStorage = { getItem: () => 'synthetic-organization-token' }
  const { api, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
  writeRequests.setActor({ tenantId: 'org-test', userId: 'admin', roles: ['ADMIN'] })
  const requests = [], saved = { id: 'p1', subject: 'sub', displayName: '人', active: true, approvalEligible: false, revision: 1 }
  globalThis.fetch = async (url, init) => { requests.push({ url, ...init }); if (requests.length === 1) throw new TypeError('lost'); return Response.json(saved) }
  const body = { subject: 'sub', displayName: '人', active: true, approvalEligible: false }
  await assert.rejects(api.saveOrganization('/organization/people', false, '保存人员', body))
  body.subject = 'later-change'
  const pending = writeRequests.pending()[0]
  await writeRequests.recover(pending.id)
  assert.equal(requests[0].body, requests[1].body)
  assert.equal(requests[0].headers['Idempotency-Key'], requests[1].headers['Idempotency-Key'])
})

test('主管关系独立保存，恢复原结果后保留正确修订', () => {
  const baseline = { id: 'a1', personId: 'p', departmentId: 'd', positionId: 'j', active: true, revision: 3, supervisorAppointmentId: 'old' }
  const draft = { section: 'APPOINTMENT', mode: 'relationship', baseline, form: organizationForm(baseline) }
  draft.form.relationshipAppointmentId = 'new'
  const store = new OrganizationDrafts(); store.put('scope', draft)
  const body = JSON.stringify(organizationPayload(draft)), path = organizationPath(draft)
  assert.equal(path, '/organization/appointments/a1/supervisor')
  assert.deepEqual(JSON.parse(body), { appointmentId: 'new', expectedRevision: 3 })
  assert.equal(store.acknowledge('scope', path, body, { ...baseline, supervisorAppointmentId: 'new', revision: 4 }), true)
  assert.equal(store.get('scope').mode, 'relationship'); assert.equal(store.hasDrafts(), false)
})
