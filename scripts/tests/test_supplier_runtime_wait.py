"""供应商验收必须等待银行执行完成，不能把发送中或查询中当作终态。"""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

SCRIPT = Path(__file__).resolve().parents[1] / 'check-supplier-trace-runtime.py'
SPEC = importlib.util.spec_from_file_location('supplier_wait_test', SCRIPT)
RUNTIME = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNTIME)


class SupplierRuntimeWaitTest(unittest.TestCase):
    def test_paid_waits_through_running_states(self):
        for state in ('SENDING', 'QUERYING', 'UNKNOWN'):
            with self.subTest(state=state):
                runtime = Mock()
                runtime.call.side_effect = [None, {'operation': {'status': state}},
                                            {'operation': {'status': 'SUCCEEDED'}}]
                with patch.object(RUNTIME, 'create', return_value='request'), \
                     patch.object(RUNTIME, 'held', return_value='payment'), \
                     patch.object(RUNTIME, 'execute_input', return_value={}):
                    self.assertEqual(RUNTIME.paid(runtime, {}), {'request': 'request', 'payment': 'payment'})
                self.assertEqual(runtime.call.call_count, 3)


if __name__ == '__main__':
    unittest.main()
