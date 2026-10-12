from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]


def test_f58_parity_registry_records_completed_local_acceptance():
    registry = yaml.safe_load(
        (ROOT / "contracts/parity/feature-parity.yaml").read_text(encoding="utf-8")
    )
    features = {feature["id"]: feature for feature in registry["features"]}
    f58 = features["F58"]

    assert f58["status"] == "complete"

    test_paths = f58.get("tests") or []
    assert any((ROOT / test_path).is_file() for test_path in test_paths), (
        "F58 must reference at least one existing test file"
    )
    assert f58.get("evidence"), "F58 must record verification evidence"
