from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]


def test_f56_parity_registry_records_completed_cli_and_android_exception():
    registry = yaml.safe_load(
        (ROOT / "contracts/parity/feature-parity.yaml").read_text(encoding="utf-8")
    )
    features = {feature["id"]: feature for feature in registry["features"]}
    f56 = features["F56"]

    assert f56["status"] == "complete"

    test_paths = f56.get("tests") or []
    assert any((ROOT / test_path).is_file() for test_path in test_paths), (
        "F56 must reference at least one existing test file"
    )
    assert f56.get("evidence"), "F56 must record verification evidence"

    android = f56.get("android") or {}
    assert android.get("status") == "not_applicable"
    assert android.get("approved_by"), "F56 Android N/A needs plan authority"
    assert android.get("reason"), "F56 Android N/A needs a reason"
