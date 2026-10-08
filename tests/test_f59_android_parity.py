from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
VALID_ANDROID_STATES = {"implemented", "partial", "missing", "not_applicable"}


def _features():
    registry = yaml.safe_load((ROOT / "contracts/parity/feature-parity.yaml").read_text(encoding="utf-8"))
    return registry["features"]


def test_every_legacy_feature_has_explicit_android_parity_mapping():
    features = _features()
    by_id = {feature["id"]: feature for feature in features}

    assert len(by_id) == len(features), "parity registry must not duplicate feature IDs"
    for number in range(1, 59):
        feature_id = f"F{number:02}"
        feature = by_id[feature_id]
        android = feature.get("android")
        assert isinstance(android, dict), f"{feature_id} needs Android status and scenario"
        assert android.get("status") in VALID_ANDROID_STATES, f"{feature_id} needs valid Android status"
        assert android.get("scenario"), f"{feature_id} needs concrete Android scenario or gap"


def test_android_mapping_references_real_tests_or_approved_platform_exception():
    for feature in _features():
        if not feature["id"].startswith("F") or int(feature["id"][1:]) > 58:
            continue
        android = feature.get("android") or {}
        if android.get("status") == "not_applicable":
            assert android.get("approved_by"), f"{feature['id']} N/A needs plan authority"
            assert android.get("reason"), f"{feature['id']} N/A needs a reason"
            continue

        if android.get("status") == "missing":
            assert android.get("gap"), f"{feature['id']} needs a concrete missing-work statement"
            continue

        tests = android.get("tests") or []
        assert tests, f"{feature['id']} needs Android test path or explicit missing-work test"
        if android.get("status") == "partial":
            assert android.get("gap"), f"{feature['id']} partial mapping needs a concrete gap"
        for test in tests:
            assert (ROOT / test).is_file(), f"{feature['id']} references missing Android test: {test}"


def test_all_android_mappings_are_accounted_for_in_f59_acceptance():
    features = {feature["id"]: feature for feature in _features()}
    assert "F59" in features
    evidence = " ".join(features["F59"].get("evidence") or [])
    assert "feature-by-feature" not in evidence.lower(), "F59 must replace stale summary-only evidence"
    assert len([features[f"F{number:02}"] for number in range(1, 59)]) == 58
