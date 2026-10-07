"""供应商来源验收的回环财务账本；只消费合成命令，独立保存首次写入和收到次数。"""
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading


def instant(seconds=0):
    return (datetime.now(timezone.utc) + timedelta(seconds=seconds)).isoformat().replace('+00:00', 'Z')


def money(value): return {'value': str(value), 'currency': 'CNY'}
def amount(value): return Decimal(value['value'])
def identity(command):
    return command['id'] if 'id' in command else command['authorization']['id'] if 'authorization' in command else command['holdCommand']['authorization']['id']
def paid_amount(command): return command['held']['heldAmount']
def payee(command): return command['holdCommand']['authorization']['payable']['account']
def save(path, value): path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


class Peer:
    def __init__(self, directory):
        self.directory, self.entity, self.records, self.commands, self.errors = directory, None, [], {}, []
        self.lock = threading.RLock(); peer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_POST(self):
                try:
                    envelope = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                    operation = self.path.rsplit('/', 1)[1]
                    with peer.lock:
                        peer.records.append({'operation': operation, 'traceId': self.headers.get('X-Trace-Id'),
                                             'key': self.headers.get('Idempotency-Key'), 'body': envelope})
                        value = peer.finance(operation, envelope['data'])
                        save(directory / 'peer-records.json', peer.records); save(directory / 'peer-commands.json', peer.commands)
                    body = json.dumps({'contractVersion': 1, 'tenantId': envelope['tenantId'], 'requestId': envelope['requestId'], 'outcome': 'SUCCESS', 'data': value}).encode()
                    self.send_response(200); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(body)))
                    self.end_headers(); self.wfile.write(body)
                except Exception as error:
                    peer.errors.append(repr(error)); save(directory / 'peer-errors.json', peer.errors); self.send_error(500)

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()

    def finance(self, operation, data):
        if operation == 'catalog':
            return {'employeeId': 'alice', 'sourceVersion': 'synthetic-v1', 'validUntil': instant(3600),
                    'legalEntities': [{'id': self.entity, 'name': '合成法人', 'baseCurrency': 'CNY', 'paperReceiptRequired': False, 'sourceVersion': 'entity-v1', 'timeZone': 'Asia/Shanghai'}],
                    'categories': [{'code': 'PROCUREMENT', 'name': '采购', 'units': ['ITEM']}],
                    'costCenters': [{'legalEntityId': self.entity, 'code': 'IT', 'name': '研发'}], 'projects': [], 'cities': []}
        if operation == 'procurement-payable':
            number = str(int(hashlib.sha256(data['payableReference'].encode()).hexdigest()[:15], 16)).zfill(20)
            return {'request': data, 'sourceVersion': 'payable-v1', 'observedAt': instant(), 'validUntil': instant(600), 'supplierName': '合成供应商',
                    'account': {'legalEntityId': self.entity, 'supplierReference': data['supplierReference'], 'accountReference': 'private-synthetic-account',
                                'maskedAccount': '****3456', 'accountDigest': 'a' * 64, 'sourceVersion': 'account-v1'},
                    'contractReference': 'contract-1', 'orderReference': 'order-1', 'matchingReference': 'match-1', 'accrualVoucherReference': 'accrual-1',
                    'budgetRecognitionReference': 'budget-1', 'dueOn': instant(864000)[:10], 'gross': money(100), 'settled': money(30),
                    'lines': [{'lineNo': 1, 'orderLineNo': 1, 'acceptanceReference': 'acceptance-1', 'invoice': {'type': 'DIGITAL', 'number': number},
                               'invoiceLineNo': 1, 'invoiceDigest': 'b' * 64, 'verificationReference': 'verification-1', 'unit': '件',
                               'orderedQuantity': 10, 'acceptedQuantity': 10, 'invoicedQuantity': 10,
                               'orderedGross': money(100), 'acceptedGross': money(100), 'invoicedGross': money(100), 'tax': money(10)}]}
        if operation == 'debit-accounts':
            return {'request': data, 'sourceVersion': 'directory-v1', 'observedAt': instant(), 'validUntil': instant(600),
                    'accounts': [{'reference': 'debit-1', 'displayName': '法人基本户', 'maskedAccount': '****5678', 'currency': 'CNY', 'sourceVersion': 'debit-v1'}]}
        if operation == 'accounting-period':
            return {'request': data, 'periodReference': 'period-1', 'sourceVersion': 'period-v1', 'startsOn': '2026-01-01', 'endsOn': '2026-12-31',
                    'observedAt': instant(), 'validUntil': instant(600)}
        if operation == 'supplier-payment-return':
            original, command = data['original'], data['command']; bank = {**original, 'observedAt': instant()}
            return {'request': data, 'status': 'PARTIALLY_RETURNED', 'revision': 1, 'observedAt': instant(), 'validUntil': instant(240), 'current': bank,
                    'returns': [{'transactionReference': 'return-' + identity(command), 'creditAccountReference': command['debitAccount']['reference'],
                                 'amount': money(20), 'receivedAt': original['completedAt']}]}
        if operation.endswith('-command'):
            command, fingerprint = data['command'], data['commandDigest']; command_id = identity(command)
            assert self.records[-1]['key'] == command_id
            key = operation + ':' + command_id
            if key not in self.commands:
                self.commands[key] = {'command': command, 'received': 0, 'observation': self.observation(operation, command, fingerprint)}
            stored = self.commands[key]; assert stored['command'] == command and stored['observation']['commandDigest'] == fingerprint
            stored['received'] += 1
            return {**stored['observation'], 'observedAt': instant()}
        if operation.endswith('-query'):
            command_id = data.get('operationId', data.get('authorizationId')); key = operation.removesuffix('-query') + '-command:' + command_id
            stored = self.commands[key]; assert stored['observation']['commandDigest'] == data['commandDigest']
            return {**stored['observation'], 'observedAt': instant()}
        raise AssertionError('Unexpected supplier operation: ' + operation)

    def observation(self, operation, command, fingerprint):
        command_id = identity(command); result = {'commandDigest': fingerprint, 'revision': 1, 'observedAt': instant()}
        if operation == 'supplier-payable-hold-command':
            authorization = command['authorization']; payable = authorization['payable']
            return {**result, 'authorizationId': command_id, 'status': 'HELD', 'holdReference': 'hold-' + command_id, 'ledgerVersion': 'ledger-v1',
                    'heldAmount': authorization['source']['reservation']['source']['round']['content']['amount'],
                    'accountDigest': payable['account']['accountDigest'], 'heldAt': authorization['authorizedAt']}
        if operation == 'supplier-payment-command':
            return {**result, 'authorizationId': command_id, 'status': 'SUCCEEDED', 'paymentReference': 'bank-' + command_id,
                    'paidAmount': paid_amount(command), 'accountDigest': payee(command)['accountDigest'], 'completedAt': command['registeredAt'], 'receiptReference': 'receipt-' + command_id}
        if operation == 'supplier-payable-settlement-command':
            payment, period, paid = command['payment'], command['period'], command['paid']
            posting = {'settlementReference': 'settled-' + command_id, 'holdReference': payment['held']['holdReference'], 'ledgerVersion': 'settled-v1',
                       'settledAmount': paid_amount(payment), 'settledBefore': money(30), 'settledAfter': money(100), 'bankPaymentReference': paid['paymentReference'],
                       'bankReceiptReference': paid['receiptReference'], 'voucherReference': 'voucher-' + command_id, 'periodReference': period['periodReference'],
                       'accountingDate': period['request']['accountingDate'], 'settledAt': command['registeredAt']}
            return {**result, 'operationId': command_id, 'status': 'SETTLED', 'posting': posting}
        if operation == 'supplier-payable-adjustment-command':
            source, period = command['source'], command['period']; ledger = source['returns']; payment = ledger['request']['command']; entries = ledger['entries']
            assert source.get('previous') is None and source.get('settlement') is None
            total = sum((amount(entry['proof']['amount']) for entry in entries), Decimal(0)); paid = amount(paid_amount(payment))
            before = amount(payment['holdCommand']['authorization']['payable']['settled'])
            posting = {'adjustmentReference': 'adjusted-' + command_id, 'holdReference': payment['held']['holdReference'], 'ledgerVersion': 'adjusted-v1',
                       'recognitionVoucherReference': 'recognized-' + command_id, 'returnedAmount': money(total), 'totalReturned': money(total), 'netPaid': money(paid-total),
                       'payableSettledBefore': money(before), 'payableSettledAfter': money(before+paid-total),
                       'entries': [{'transactionReference': entry['proof']['transactionReference'], 'amount': entry['proof']['amount'],
                                    'voucherReference': 'voucher-' + command_id, 'entryReference': 'entry-' + str(index)} for index, entry in enumerate(entries)],
                       'periodReference': period['periodReference'], 'accountingDate': period['request']['accountingDate'], 'adjustedAt': command['registeredAt']}
            return {**result, 'operationId': command_id, 'status': 'ADJUSTED', 'posting': posting}
        raise AssertionError(operation)

    def close(self): self.server.shutdown(); self.server.server_close(); self.thread.join(timeout=5)
