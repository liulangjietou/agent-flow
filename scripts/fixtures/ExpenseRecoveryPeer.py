"""合成 ERP 冲销接收端；保存原命令和反向分录以检查恢复不会重复发送。"""
from copy import deepcopy


def install(sources, directory, save, instant):
    original, commands = sources.finance, {}

    def finance(operation, request):
        data = request['data']
        if operation not in ('voucher-reversal-command', 'voucher-reversal-query'):
            result, status = original(operation, request)
            if operation == 'voucher-query' and status == 200:
                for stored in commands.values():
                    if stored['command']['source']['command']['id'] == data['operationId']:
                        result['data'] = {**stored['observation']['posting']['current'], 'observedAt': instant()}
            return result, status
        identity = data['command']['id'] if operation.endswith('command') else data['operationId']
        if operation.endswith('command'):
            command = data['command']
            if identity not in commands:
                stamp, verified = instant(), command['verifiedOriginal']
                reversed_original = {**verified, 'status': 'REVERSED', 'revision': verified['revision'] + 1, 'observedAt': stamp}
                posting = {'postingReference': 'synthetic-reversal-posting-' + identity,
                    'voucherReference': 'synthetic-reversal-voucher-' + identity,
                    'periodReference': command['period']['periodReference'],
                    'accountingDate': command['period']['request']['accountingDate'], 'postedAt': stamp,
                    'lines': [{'entryReference': 'synthetic-line-' + str(line['originalLineNo']), **line} for line in data['lines']]}
                observation = {'operationId': identity, 'commandDigest': data['commandDigest'], 'status': 'POSTED',
                    'revision': 2, 'observedAt': stamp, 'acceptanceReference': 'synthetic-reversal-' + identity,
                    'posting': {'request': command['source'], 'status': 'VERIFIED', 'revision': 2,
                        'observedAt': stamp, 'validUntil': instant(240), 'current': reversed_original, 'reversal': posting}, 'rejection': None}
                commands[identity] = {'command': command, 'lines': data['lines'], 'observation': observation, 'received': 0}
            stored = commands[identity]
            assert stored['command'] == command and stored['lines'] == data['lines'] and stored['observation']['commandDigest'] == data['commandDigest']
            stored['received'] += 1
            save(directory / 'reversal-peer-commands.json', commands)
        stored = commands[identity]
        assert stored['observation']['commandDigest'] == data['commandDigest']
        return {'contractVersion': 1, 'tenantId': 'demo', 'requestId': request['requestId'],
                'outcome': 'SUCCESS', 'data': deepcopy(stored['observation'])}, 200

    sources.finance = finance
    return commands
