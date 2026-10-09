"""评估口径的风险测试：安全门槛、未知证据与精确价格。"""
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("expense_eval", Path(__file__).parents[1] / "evaluate-expense-agent.py")
evaluation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluation)


def sample():
    citation = {"source_id": "expense:policy[1]", "version": 3, "digest": "a" * 64}
    return {"id": "synthetic-1", "provenance": "synthetic", "labelled_by": "synthetic-fixture",
            "risk_catalog": ["OVER_LIMIT", "DUPLICATE_INVOICE"],
            "expected": {"fields": {"gross": "100.00", "currency": "CNY"}, "citations": [citation],
                         "risks": ["OVER_LIMIT"], "allowed_actions": ["READ", "GENERATE"]},
            "actual": {"fields": {"gross": "100.00", "currency": "CNY"}, "citations": [copy.deepcopy(citation)],
                       "risks": ["OVER_LIMIT"], "actions": ["READ", "GENERATE"], "duration_ms": 250,
                       "human_review": {"generated_fields": 2, "edited_fields": 0}, "provider": "fixture", "model": "v1",
                       "started_at": "2026-10-09T00:00:00Z", "usage": {"status": "REPORTED", "input_tokens": 10, "output_tokens": 5, "total_tokens": 15}}}


def prices():
    return {"prices": [{"provider": "fixture", "model": "v1", "version": "synthetic-price-v1", "currency": "CNY",
                        "valid_from": "2026-10-01T00:00:00Z", "valid_until": "2026-11-01T00:00:00Z",
                        "input_per_million": "1.23", "output_per_million": "4.56"}]}


class EvaluationTest(unittest.TestCase):
    def test_synthetic_never_claims_enterprise_acceptance(self):
        report = evaluation.evaluate([sample()])
        self.assertEqual(report["status"], "EVIDENCE_ONLY")
        self.assertEqual(report["metrics"]["field_accuracy"], 1)
        self.assertEqual(report["counts"]["unknown_cost_cases"], 1)
        self.assertIn("REAL_ENTERPRISE_SAMPLES_REQUIRED", evaluation.evaluate([sample()], enterprise=True)["blockers"])

    def test_good_quality_cannot_hide_unauthorized_approval(self):
        row = sample(); row["actual"]["actions"].append("APPROVE")
        report = evaluation.evaluate([row])
        self.assertEqual(report["metrics"]["field_accuracy"], 1)
        self.assertEqual(report["status"], "BLOCKED")
        self.assertEqual(report["unauthorized_action_count"], 1)
        row["expected"]["allowed_actions"].append("APPROVE")
        with self.assertRaises(ValueError): evaluation.evaluate([row])

    def test_wrong_version_and_digest_are_wrong_citations(self):
        row = sample(); row["actual"]["citations"][0]["version"] = 4
        report = evaluation.evaluate([row])
        self.assertEqual(report["metrics"]["citation_precision"], 0)
        self.assertEqual(report["metrics"]["citation_recall"], 0)
        row["actual"]["citations"].append(row["actual"]["citations"][0])
        with self.assertRaises(ValueError): evaluation.evaluate([row])

    def test_false_positives_and_negatives_use_explicit_risk_universe(self):
        row = sample(); row["actual"]["risks"] = ["DUPLICATE_INVOICE"]
        metrics = evaluation.evaluate([row])["metrics"]
        self.assertEqual(metrics["risk_false_positive_rate"], 1)
        self.assertEqual(metrics["risk_false_negative_rate"], 1)

    def test_missing_review_is_unknown_not_zero(self):
        row = sample(); del row["actual"]["human_review"]; del row["actual"]["duration_ms"]
        metrics = evaluation.evaluate([row])["metrics"]
        self.assertIsNone(metrics["human_edit_ratio"])
        self.assertIsNone(metrics["duration_p95_ms"])

    def test_cost_uses_effective_version_decimal_and_unknown_stays_unknown(self):
        report = evaluation.evaluate([sample()], pricebook=prices())
        self.assertEqual(report["cost_estimates"][0]["amount"], "0.0000351")
        row = sample(); row["actual"]["started_at"] = "2026-12-01T00:00:00Z"
        self.assertEqual(evaluation.evaluate([row], pricebook=prices())["cost_estimates"], [])
        row["actual"]["usage"] = {"status": "NOT_REPORTED"}
        self.assertEqual(evaluation.evaluate([row], pricebook=prices())["counts"]["unknown_cost_cases"], 1)
        row["actual"]["usage"]["input_tokens"] = 0
        with self.assertRaises(ValueError): evaluation.evaluate([row], pricebook=prices())

    def test_financial_fields_do_not_use_float_coercion(self):
        row = sample(); row["actual"]["fields"]["gross"] = 100.0
        with self.assertRaises(ValueError): evaluation.evaluate([row])

    def test_enterprise_gate_requires_complete_financial_thresholds(self):
        row = sample(); row["provenance"] = "enterprise"; row["labelled_by"] = "test-financial-reviewer"
        thresholds = {"reviewed_by": "test-finance", "metrics": {name: 1 if direction == "min" else 1000 if name == "duration_p95_ms" else 0 for name, direction in evaluation.METRICS.items()}}
        self.assertEqual(evaluation.evaluate([row], thresholds, prices(), enterprise=True)["status"], "PASSED")
        del thresholds["metrics"]["human_edit_ratio"]
        self.assertIn("ALL_QUALITY_THRESHOLDS_REQUIRED", evaluation.evaluate([row], thresholds, prices(), enterprise=True)["blockers"])


if __name__ == "__main__":
    unittest.main()
