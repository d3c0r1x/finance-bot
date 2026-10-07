"""Contracts for the read-only receipt inventory command."""

import asyncio
import json
import subprocess
import sys
from pathlib import Path

import pytest

from tools.evaluation import receipt_inventory as inventory


ROOT = Path(__file__).resolve().parents[3]


def good_row() -> dict:
    return {
        "total": 100.0,
        "items_sum": 100.0,
        "positions": 1,
        "store": "synthetic store",
        "bad_names": [],
        "reader": "ocr",
        "seconds": None,
        "total_estimated": False,
        "over_total": False,
        "mismatch": False,
    }


def test_module_command_exposes_existing_flags_without_reading_receipts():
    result = subprocess.run(
        [sys.executable, "-m", "tools.evaluation.receipt_inventory", "--help"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )

    assert result.returncode == 0, result.stderr
    for flag in ("--vision", "--strict", "--json", "--limit", "--debug"):
        assert flag in result.stdout


def test_json_mode_emits_only_parseable_json_for_synthetic_photo(monkeypatch, tmp_path, capsys):
    photo = tmp_path / "synthetic-receipt.png"
    photo.write_bytes(b"synthetic fixture; OCR is controlled by this test")
    monkeypatch.setattr(sys, "argv", ["receipt_inventory.py", "--json", str(photo)])
    monkeypatch.setattr(inventory, "collect_photos", lambda _: [str(photo)])
    monkeypatch.setattr(inventory, "ocr_only", lambda _: good_row())

    exit_code = asyncio.run(inventory.main())
    stdout = capsys.readouterr().out

    assert exit_code == 0
    rows = json.loads(stdout)
    assert len(rows) == 1
    assert rows[0]["file"] == str(photo)
    assert rows[0]["разница"] == 0


def test_json_debug_diagnostics_go_to_stderr(monkeypatch, tmp_path, capsys):
    photo = tmp_path / "synthetic-bad.png"
    photo.write_bytes(b"synthetic")
    row = good_row()
    row.update(items_sum=125.0, over_total=True)

    def candidates(*_, **__):
        return [{"names": [], "variant": "synthetic", "score": 1.0,
                 "total": 100, "positions": 1, "items_total": 125}]

    monkeypatch.setattr(sys, "argv", ["receipt_inventory.py", "--json", "--debug", str(photo)])
    monkeypatch.setattr(inventory, "collect_photos", lambda _: [str(photo)])
    monkeypatch.setattr(inventory, "ocr_only", lambda _: row)
    monkeypatch.setattr("ai.ocr.parse_candidates", candidates)

    assert asyncio.run(inventory.main()) == 0
    captured = capsys.readouterr()
    assert len(json.loads(captured.out)) == 1
    assert "Разборы-кандидаты" in captured.err


def test_empty_json_mode_emits_empty_array_without_human_summary(monkeypatch, capsys):
    monkeypatch.setattr(sys, "argv", ["receipt_inventory.py", "--json"])
    monkeypatch.setattr(inventory, "collect_photos", lambda _: [])

    exit_code = asyncio.run(inventory.main())
    stdout = capsys.readouterr().out

    assert exit_code == 0
    assert json.loads(stdout) == []


def test_strict_json_isolates_a_broken_photo_and_reports_failure(monkeypatch, tmp_path, capsys):
    photo = tmp_path / "synthetic-broken.png"
    photo.write_bytes(b"synthetic")

    def broken_ocr(_):
        raise RuntimeError("synthetic OCR failure")

    monkeypatch.setattr(sys, "argv", ["receipt_inventory.py", "--json", "--strict", str(photo)])
    monkeypatch.setattr(inventory, "collect_photos", lambda _: [str(photo)])
    monkeypatch.setattr(inventory, "ocr_only", broken_ocr)

    assert asyncio.run(inventory.main()) == 1
    rows = json.loads(capsys.readouterr().out)
    assert rows[0]["reader"] == "ошибка: RuntimeError"
    assert rows[0]["нет позиций"] is True


def test_strict_mode_with_no_photos_is_success(monkeypatch, capsys):
    monkeypatch.setattr(sys, "argv", ["receipt_inventory.py", "--strict"])
    monkeypatch.setattr(inventory, "collect_photos", lambda _: [])

    assert asyncio.run(inventory.main()) == 0
    assert "чеков нет" in capsys.readouterr().out


def test_photo_discovery_preserves_order_and_deduplicates_synthetic_paths(monkeypatch, tmp_path):
    default_dir = tmp_path / "receipts"
    default_dir.mkdir()
    sorted_first = default_dir / "a.png"
    sorted_last = default_dir / "z.png"
    explicit = tmp_path / "explicit.png"
    for photo in (sorted_last, sorted_first, explicit):
        photo.write_bytes(b"synthetic")

    monkeypatch.setattr(inventory, "DEFAULT_DIR", str(default_dir))
    monkeypatch.setattr(
        inventory,
        "load_samples",
        lambda: {"synthetic": {"photo": str(sorted_last), "extra_photos": [str(explicit)]}},
    )

    assert inventory.collect_photos([str(explicit)]) == [
        str(explicit),
        str(sorted_first),
        str(sorted_last),
    ]


def test_vision_and_strict_flags_use_vision_and_fail_on_hard_defect(monkeypatch, tmp_path):
    photo = tmp_path / "synthetic-bad.png"
    photo.write_bytes(b"synthetic")
    calls = []

    async def vision(path: str) -> dict:
        calls.append(path)
        row = good_row()
        row.update(total=100.0, items_sum=125.0, over_total=True, reader="vision")
        return row

    monkeypatch.setattr(sys, "argv", ["receipt_inventory.py", "--vision", "--strict", str(photo)])
    monkeypatch.setattr(inventory, "collect_photos", lambda _: [str(photo)])
    monkeypatch.setattr(inventory, "with_vision", vision)

    assert asyncio.run(inventory.main()) == 1
    assert calls == [str(photo)]


def test_root_entry_point_remains_available():
    result = subprocess.run(
        [sys.executable, "receipt_inventory.py", "--help"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )

    assert result.returncode == 0, result.stderr
    assert "--vision" in result.stdout
    assert "--strict" in result.stdout
    assert "--json" in result.stdout


def test_ci_guards_private_samples_and_runs_new_cli_without_artifact_upload():
    workflow = (ROOT / ".github" / "workflows" / "tests.yml").read_text(encoding="utf-8")

    assert "git ls-files" in workflow
    assert "receipt_samples.json" in workflow
    assert "python -m tools.evaluation.receipt_inventory --json" in workflow
    assert "actions/upload-artifact" not in workflow


def test_owner_receipt_paths_are_ignored_and_not_tracked():
    gitignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
    assert "receipt_samples.json" in gitignore
    assert "data/" in gitignore

    tracked = subprocess.run(
        ["git", "ls-files", "--", "receipt_samples.json", "data/receipts"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=True,
    )
    assert tracked.stdout == ""
