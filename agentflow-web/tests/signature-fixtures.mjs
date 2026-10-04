export const id = n => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`
export const APP = id(1), OPERATION = id(2), DOCUMENT = id(3)
export const profile = () => ({ key: 'contract-seal', version: '9007199254740993', name: '合同签署资料' })
export const options = () => ({ enabled: true, profiles: [profile()], maxDocuments: 10, maxDocumentBytes: 16777216, maxTotalBytes: 33554432, maxAuthorizationSeconds: 86400 })
export const source = (roundNo = 1) => ({ roundNo, status: 'APPROVED', formSchema: { schemaVersion: 1, fields: [{ key: 'contract', label: '已批准合同', type: 'ATTACHMENT', required: true }] }, payload: { contract: [DOCUMENT] } })
export const application = () => ({ id: APP, businessNo: 'signature-example', processKey: 'contract', definitionVersion: 1, createdBy: 'alice', title: '已批准合同', payload: source().payload, formSchema: source().formSchema, status: 'APPROVED', roundNo: 1, version: 4 })
export const metadata = (document = DOCUMENT) => ({ id: document, fieldPath: 'contract', filename: '合同原件.pdf', size: 8, sha256: 'a'.repeat(64), status: 'READY' })
export const receipt = (status = 'QUEUED', operation = OPERATION) => ({ id: operation, version: status === 'QUEUED' ? '1' : '5', status })
export const view = (status = 'QUEUED', operation = OPERATION) => ({ operation: receipt(status, operation), roundNo: 1, profileKey: profile().key, profileVersion: profile().version, authorizedBy: 'alice', purpose: '签署已批准合同', authorizedAt: '2026-10-04T12:00:00.000000001Z', validUntil: '2026-10-04T13:00:00Z', updatedAt: '2026-10-04T12:00:01Z', canCancel: status === 'QUEUED', documents: [{ id: DOCUMENT, filename: metadata().filename, originalBytes: 8, downloadable: status === 'SIGNED', ...(status === 'SIGNED' ? { signedBytes: 6 } : {}) }] })
export const input = () => ({ roundNo: 1, expectedVersion: '4', profileKey: profile().key, profileVersion: profile().version, documentIds: [DOCUMENT], purpose: '签署已批准合同', validUntil: '2026-10-04T13:00:00Z' })
export const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
