import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_ATTACHMENT_FIELD)
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { attachmentIds, fileDigest } = await import(process.env.AGENTFLOW_TEST_ATTACHMENTS)
const original = Object.fromEntries(['attachmentOptions', 'reserveAttachment', 'uploadAttachment', 'attachment', 'downloadAttachment', 'copyAttachment', 'downloadCopyAttachment'].map(key => [key, api[key]]))
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const id = '6d3c98ca-b9d3-4ca5-948c-d8975f94e333'
const sha256 = 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'
const file = new File(['abc'], '凭证.txt')
const pendingFile = { id, fieldPath: 'proof', filename: file.name, size: 3, sha256, status: 'UPLOADING' }
const readyFile = { ...pendingFile, status: 'READY' }
const limits = { enabled: true, maxFileBytes: 1024, maxApplicationBytes: 10240, maxApplicationUploads: 100, maxAttachmentsPerField: 10, contentScanAvailable: false }
const settle = () => new Promise(resolve => setImmediate(resolve))

test('抄送附件只走专用轮次接口，切回普通上下文不复用旧元数据', async () => {
  defaults(); const called = []
  api.copyAttachment = async (app, file, round) => { called.push([app, file, round]); return readyFile }
  api.attachment = async () => { throw { message: '普通申请不可见' } }
  const p = panel({ modelValue: [id], readonly: true, context: { applicationId: 'copied', roundNo: 3, scopeKey: 'demo/bob', copy: true } })
  try {
    await settle()
    assert.deepEqual(called, [['copied', id, 3]])
    assert.equal(p.state.metadata[id].filename, file.name)
    p.props.context.copy = false
    await settle()
    assert.deepEqual(p.state.metadata, {})
    assert.equal(p.state.error, '普通申请不可见')
  } finally { p.close() }
})
function defaults() {
  api.attachmentOptions = async () => limits
  api.reserveAttachment = async () => pendingFile
  api.attachment = async () => readyFile
  api.uploadAttachment = async () => readyFile
}
function panel(extra = {}) {
  const props = reactive({ modelValue: [], fieldPath: 'proof', context: { applicationId: 'application', expectedVersion: 0, scopeKey: 'demo/alice' }, ...extra })
  const busy = [], updates = []
  const app = renderer.createApp({ ...Panel, setup: (_, context) => Panel.setup(props, context), render: () => null }, {
    ...props, 'onUpdate:modelValue': values => { updates.push(values); props.modelValue = values }, onUploading: value => busy.push(value)
  })
  const instance = app.mount({})
  return { state: instance.$.setupState, props, busy, updates, close: () => { app.unmount(); Object.assign(api, original) } }
}
test('文件摘要按原始字节生成，附件引用只接受完整 UUID 数组', async () => {
  assert.equal(await fileDigest(file), sha256)
  assert.deepEqual(attachmentIds([id]), [id])
  assert.deepEqual(attachmentIds(['../secret']), [])
  assert.deepEqual(attachmentIds(id), [])
})
test('上传失败后保留原身份，重试不新增登记，移除仅修改当前引用', async () => {
  defaults(); let reserves = 0, uploads = 0
  api.reserveAttachment = async () => { reserves++; return pendingFile }
  api.uploadAttachment = async () => { uploads++; if (uploads === 1) throw { message: '连接断开' }; return readyFile }
  const p = panel()
  try {
    await settle(); p.state.attempt = { file, key: 'stable-key' }; await p.state.upload()
    assert.deepEqual(p.props.modelValue, [id]); assert.equal(p.state.attempt.id, id); assert.equal(p.state.error, '连接断开')
    await p.state.upload(); assert.equal(reserves, 1); assert.equal(uploads, 2)
    assert.equal(p.state.metadata[id].status, 'READY'); assert.equal(p.state.attempt, null); assert.equal(p.state.uploading, false)
    p.state.remove(id); assert.deepEqual(p.props.modelValue, []); assert.match(p.state.phase, /历史轮次文件保留/)
  } finally { p.close() }
})
test('读取超时立即解除等待，迟到元数据不能回填，权限失败清空旧文件名', async () => {
  defaults(); const timers = [], timer = globalThis.setTimeout
  let release, signal
  api.attachment = (_, __, ___, s) => { signal = s; return new Promise(resolve => { release = resolve }) }
  globalThis.setTimeout = (callback, delay) => { timers.push({ callback, delay }); return 1 }
  const p = panel({ modelValue: [id] })
  try {
    assert.equal(timers[0].delay, 12000); timers[0].callback()
    assert.equal(signal.aborted, true); assert.equal(p.state.loading, false); assert.match(p.state.error, /超时/)
    release(readyFile); await settle(); assert.deepEqual(p.state.metadata, {})
    api.attachment = async () => readyFile; await p.state.refresh(); assert.equal(p.state.metadata[id].filename, file.name)
    api.attachment = async () => { throw { message: '字段已隐藏' } }; await p.state.refresh()
    assert.deepEqual(p.state.metadata, {}); assert.equal(p.state.error, '字段已隐藏')
  } finally { globalThis.setTimeout = timer; p.close() }
})
test('登记超时不接续上传，重试沿用同一个幂等键', async () => {
  defaults(); const p = panel(), timers = [], timer = globalThis.setTimeout
  let release, signal, uploads = 0; const keys = []
  try {
    await settle()
    globalThis.setTimeout = (callback, delay) => { timers.push({ callback, delay }); return 1 }
    api.reserveAttachment = (_, __, key, s) => { keys.push(key); signal = s; return new Promise(resolve => { release = resolve }) }
    api.uploadAttachment = async () => { uploads++; return readyFile }
    p.state.attempt = { file, key: 'uncertain-result-key' }
    const result = p.state.upload(); while (!release) await settle()
    timers[0].callback(); assert.equal(signal.aborted, true); assert.equal(p.state.uploading, false)
    release(pendingFile); await result; assert.equal(uploads, 0); assert.deepEqual(p.props.modelValue, [])
    api.reserveAttachment = async (_, __, key) => { keys.push(key); return pendingFile }
    await p.state.upload(); assert.deepEqual(keys, ['uncertain-result-key', 'uncertain-result-key']); assert.equal(uploads, 1)
  } finally { globalThis.setTimeout = timer; p.close() }
})
test('账号切换取消传输，迟到响应不能写入其他人的表单', async () => {
  defaults(); const p = panel(); let release, signal
  try {
    await settle()
    api.uploadAttachment = (_, __, ___, ____, s) => { signal = s; return new Promise(resolve => { release = resolve }) }
    p.state.attempt = { file, key: 'switch-key' }; const result = p.state.upload()
    while (!release) await settle()
    p.props.context = { applicationId: 'other', expectedVersion: 0, scopeKey: 'demo/bob' }; p.props.modelValue = []
    await settle(); assert.equal(signal.aborted, true); assert.equal(p.state.attempt, null)
    release(readyFile); await result; assert.deepEqual(p.state.metadata, {}); assert.deepEqual(p.props.modelValue, [])
  } finally { p.close() }
})
test('下载权限读取超时后不继续读取原文件，空身份不发送附件请求', async () => {
  defaults(); const p = panel({ readonly: true }), timers = [], timer = globalThis.setTimeout
  let release, downloads = 0, reads = 0
  try {
    await settle()
    globalThis.setTimeout = (callback, delay) => { timers.push({ callback, delay }); return 1 }
    api.attachment = () => { reads++; return new Promise(resolve => { release = resolve }) }
    api.downloadAttachment = async () => { downloads++; return new Blob(['abc']) }
    const result = p.state.download(id); timers[0].callback()
    assert.equal(p.state.downloading, ''); release(readyFile); await result; assert.equal(downloads, 0)
    p.props.context.scopeKey = ''; p.props.modelValue = [id]; await settle(); await p.state.refresh(); await p.state.download(id)
    assert.equal(reads, 1)
  } finally { globalThis.setTimeout = timer; p.close() }
})

test('上传期间 Escape 不关闭申请弹窗，结束上传后恢复键盘关闭', async () => {
  const { default: ApplicationRecord } = await import(process.env.AGENTFLOW_TEST_APPLICATION_RECORD)
  const document = globalThis.document, original = { ...api }
  globalThis.document = { activeElement: null }
  api.application = async () => ({ id: 'app', createdBy: 'alice', status: 'DRAFT', title: '附件申请', payload: {}, version: 1, roundNo: 0 })
  api.applicationRounds = async () => []
  let closed = 0, prevented = 0
  const app = renderer.createApp({ ...ApplicationRecord, render: () => null }, {
    applicationId: 'app', userId: 'alice', scopeKey: 'demo/alice', commentRefreshVersion: 0,
    pendingWrites: [], recoveryError: '', onClose: () => closed++
  })
  const state = app.mount({}).$.setupState
  try {
    await settle()
    state.uploading = true
    state.trapFocus({ key: 'Escape', preventDefault: () => prevented++ })
    assert.equal(prevented, 1); assert.equal(closed, 0)
    state.uploading = false
    state.trapFocus({ key: 'Escape', preventDefault() {} })
    assert.equal(closed, 1)
  } finally { app.unmount(); globalThis.document = document; Object.assign(api, original) }
})
