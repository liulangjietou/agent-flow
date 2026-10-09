#!/usr/bin/env python3
"""离线评估带财务标注的报销助手样本，不发送业务材料或调用模型。"""
import argparse
import hashlib
import json
from datetime import datetime
from decimal import Decimal
from pathlib import Path

ALLOWED_ACTIONS = {"READ", "GENERATE", "PRECHECK", "SAVE_WITH_CONFIRMATION", "USER_SUBMIT"}
METRICS = {"field_accuracy": "min", "citation_precision": "min", "citation_recall": "min",
           "risk_false_positive_rate": "max", "risk_false_negative_rate": "max", "human_edit_ratio": "max",
           "duration_p95_ms": "max"}


def require(condition, message):
    """错误输入立即拒绝，缺失证据不自动按通过计分。"""
    if not condition:
        raise ValueError(message)


def count(value):
    return type(value) is int and value >= 0


def ratio(numerator, denominator):
    return float(Decimal(numerator) / Decimal(denominator)) if denominator else None


def references(values):
    require(isinstance(values, list), "citations must be a list")
    result = set()
    for value in values:
        require(isinstance(value, dict) and set(value) == {"source_id", "version", "digest"}, "invalid citation")
        require(isinstance(value["source_id"], str) and value["source_id"] and count(value["version"])
                and value["version"] > 0 and isinstance(value["digest"], str) and len(value["digest"]) == 64
                and all(c in "0123456789abcdef" for c in value["digest"]), "invalid citation identity")
        key = (value["source_id"], value["version"], value["digest"])
        require(key not in result, "duplicate citation cannot inflate scores")
        result.add(key)
    return result


def timestamp(value):
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    require(parsed.tzinfo is not None, "timestamp must include timezone")
    return parsed


def estimate_cost(actual, pricebook):
    """只对明确返回的用量和生效价格计算，Decimal 保留金额精度。"""
    usage = actual.get("usage")
    if usage is None:
        return None
    require(isinstance(usage, dict) and usage.get("status") in {"REPORTED", "NOT_REPORTED", "INVALID"}, "invalid usage status")
    if usage["status"] != "REPORTED":
        require(all(usage.get(k) is None for k in ["input_tokens", "output_tokens", "total_tokens"]), "unknown usage cannot contain zero-filled counts")
        return None
    require(all(count(usage.get(k)) for k in ["input_tokens", "output_tokens", "total_tokens"])
            and usage["input_tokens"] + usage["output_tokens"] == usage["total_tokens"], "invalid token counts")
    if not pricebook or not actual.get("started_at"):
        return None
    started = timestamp(actual["started_at"])
    candidates = [item for item in pricebook["prices"] if item["provider"] == actual.get("provider")
                  and item["model"] == actual.get("model") and timestamp(item["valid_from"]) <= started < timestamp(item["valid_until"])]
    require(len(candidates) <= 1, "overlapping price versions")
    if not candidates:
        return None
    price = candidates[0]
    for field in ["version", "currency"]:
        require(isinstance(price[field], str) and price[field].strip(), "price identity required")
    rates = []
    for field in ["input_per_million", "output_per_million"]:
        require(isinstance(price[field], str), "prices must be decimal strings")
        rate = Decimal(price[field])
        require(rate.is_finite() and rate >= 0, "invalid price")
        rates.append(rate)
    amount = (Decimal(usage["input_tokens"]) * rates[0] + Decimal(usage["output_tokens"]) * rates[1]) / Decimal(1000000)
    return {"amount": str(amount), "currency": price["currency"], "price_version": price["version"]}


def evaluate(rows, thresholds=None, pricebook=None, enterprise=False):
    """字段、引用、风险和人工修改分别计量，安全断言独立于效果分数。"""
    require(rows, "dataset must not be empty")
    seen, cases, durations, costs = set(), [], [], []
    fields = matched = expected_refs = actual_refs = correct_refs = tp = fp = fn = tn = edited = generated = 0
    labelled_edits = violations = enterprise_cases = 0
    for row in rows:
        require(isinstance(row, dict) and isinstance(row.get("id"), str) and row["id"] and row["id"] not in seen, "case id required and unique")
        seen.add(row["id"])
        require(row.get("provenance") in {"synthetic", "enterprise"} and isinstance(row.get("labelled_by"), str)
                and row["labelled_by"].strip(), "sample provenance and financial label reviewer required")
        expected, actual = row["expected"], row["actual"]
        require(isinstance(expected["fields"], dict) and isinstance(actual["fields"], dict), "fields must be labelled dictionaries")
        # 金额必须用十进制字符串标注；所有字段按标注精确比较，禁止宽松类型转换。
        require(all(isinstance(v, (str, bool)) or v is None for v in expected["fields"].values())
                and all(isinstance(v, (str, bool)) or v is None for v in actual["fields"].values()), "field values must be strings, booleans or null")
        fields += len(expected["fields"])
        matched += sum(key in actual["fields"] and type(value) is type(actual["fields"][key]) and value == actual["fields"][key]
                       for key, value in expected["fields"].items())
        gold, predicted = references(expected["citations"]), references(actual["citations"])
        expected_refs += len(gold); actual_refs += len(predicted); correct_refs += len(gold & predicted)
        universe = row["risk_catalog"]
        require(isinstance(universe, list) and universe and all(isinstance(v, str) and v for v in universe)
                and len(set(universe)) == len(universe), "risk catalog must be explicit and unique")
        require(isinstance(expected["risks"], list) and isinstance(actual["risks"], list), "risks must be lists")
        expected_risks, actual_risks = set(expected["risks"]), set(actual["risks"])
        require(expected_risks <= set(universe) and actual_risks <= set(universe), "unlabelled risk code")
        tp += len(expected_risks & actual_risks); fp += len(actual_risks - expected_risks)
        fn += len(expected_risks - actual_risks); tn += len(set(universe) - expected_risks - actual_risks)
        allowed = expected["allowed_actions"]
        require(isinstance(allowed, list) and set(allowed) <= ALLOWED_ACTIONS and isinstance(actual["actions"], list), "invalid action labels")
        unsafe = [action for action in actual["actions"] if action not in allowed]
        violations += len(unsafe)
        edits = actual.get("human_review")
        if edits is not None:
            require(count(edits["edited_fields"]) and count(edits["generated_fields"]) and edits["edited_fields"] <= edits["generated_fields"], "invalid human review counts")
            edited += edits["edited_fields"]; generated += edits["generated_fields"]; labelled_edits += 1
        duration = actual.get("duration_ms")
        if duration is not None:
            require(count(duration), "duration must be nonnegative integer milliseconds")
            durations.append(duration)
        cost = estimate_cost(actual, pricebook)
        if cost is not None:
            costs.append({"case_id": row["id"], **cost})
        enterprise_cases += row["provenance"] == "enterprise"
        cases.append({"id": row["id"], "provenance": row["provenance"], "unsafe_actions": unsafe})
    durations.sort()
    metrics = {"field_accuracy": ratio(matched, fields), "citation_precision": ratio(correct_refs, actual_refs),
               "citation_recall": ratio(correct_refs, expected_refs), "risk_false_positive_rate": ratio(fp, fp + tn),
               "risk_false_negative_rate": ratio(fn, fn + tp), "human_edit_ratio": ratio(edited, generated),
               "duration_p95_ms": durations[(95 * len(durations) + 99) // 100 - 1] if durations else None}
    blockers = []
    if violations:
        blockers.append("UNAUTHORIZED_ACTIONS")
    if enterprise and enterprise_cases != len(rows):
        blockers.append("REAL_ENTERPRISE_SAMPLES_REQUIRED")
    if thresholds:
        require(isinstance(thresholds.get("reviewed_by"), str) and thresholds["reviewed_by"].strip(), "financial threshold reviewer required")
        limits = thresholds["metrics"]
        require(isinstance(limits, dict) and limits and set(limits) <= set(METRICS), "unknown or empty metric thresholds")
        for name, limit in limits.items():
            require(type(limit) in {int, float} and 0 <= limit < float("inf") and (name == "duration_p95_ms" or limit <= 1), "invalid threshold")
            observed = metrics[name]
            if observed is None or (observed < limit if METRICS[name] == "min" else observed > limit):
                blockers.append("METRIC_GATE:" + name)
        if enterprise and set(limits) != set(METRICS):
            blockers.append("ALL_QUALITY_THRESHOLDS_REQUIRED")
    elif enterprise:
        blockers.append("FINANCIAL_THRESHOLDS_REQUIRED")
    if enterprise and (labelled_edits != len(rows) or len(durations) != len(rows) or len(costs) != len(rows)):
        blockers.append("COMPLETE_REVIEW_DURATION_AND_PRICING_REQUIRED")
    return {"status": "BLOCKED" if blockers else "PASSED" if enterprise else "EVIDENCE_ONLY", "case_count": len(rows),
            "enterprise_case_count": enterprise_cases, "metrics": metrics, "counts": {"matched_fields": matched, "labelled_fields": fields,
            "correct_citations": correct_refs, "predicted_citations": actual_refs, "labelled_citations": expected_refs,
            "risk_true_positive": tp, "risk_false_positive": fp, "risk_false_negative": fn, "risk_true_negative": tn,
            "reviewed_cases": labelled_edits, "timed_cases": len(durations), "priced_cases": len(costs), "unknown_cost_cases": len(rows) - len(costs)},
            "unauthorized_action_count": violations, "blockers": blockers, "cost_estimates": costs, "cases": cases}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path, help="财务标注及实际输出的 JSONL 文件")
    parser.add_argument("--thresholds", type=Path)
    parser.add_argument("--pricebook", type=Path)
    parser.add_argument("--enterprise", action="store_true", help="要求全部样本真实、完整且达到财务确认的阈值")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        data = args.dataset.read_bytes()
        rows = [json.loads(line) for line in data.decode("utf-8").splitlines() if line.strip()]
        result = evaluate(rows, json.loads(args.thresholds.read_text()) if args.thresholds else None,
                          json.loads(args.pricebook.read_text()) if args.pricebook else None, args.enterprise)
        result["dataset_sha256"] = hashlib.sha256(data).hexdigest()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n")
        print(json.dumps({"status": result["status"], "cases": result["case_count"], "output": str(args.output)}, ensure_ascii=False))
        return 2 if result["status"] == "BLOCKED" else 0
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    raise SystemExit(main())
