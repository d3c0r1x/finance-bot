"""Read-only extraction and repeat-safe import of legacy goal history."""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import os
import re
import sqlite3
import sys
import urllib.error
import urllib.request
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Callable, Mapping
from urllib.parse import urlsplit
from uuid import UUID
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError


HISTORY_KEY = re.compile(r"^advice:goal_history:(.+)$")
USER_ID = re.compile(r"^[0-9]+$")
MONEY = re.compile(r"^(?:0|[1-9][0-9]{0,29})\.[0-9]{2}$")
MAX_BATCH_SIZE = 500
IMPORT_PATH = "/internal/v1/migrations/goal-history"


@dataclass(frozen=True)
class LegacyUserMapping:
    tenant_id: str
    owner_user_id: str
    timezone: str


@dataclass(frozen=True)
class QuarantinedEntry:
    source_user_id: str
    setting_key: str
    index: int
    reason: str


@dataclass
class ExtractionReport:
    source_entries: int
    imports: dict[tuple[UUID, UUID], list[dict]]
    quarantined: list[QuarantinedEntry]


@dataclass(frozen=True)
class ImportReport:
    sent: int
    inserted: int
    already_present: int


def load_manifest(path: str | Path) -> dict[str, LegacyUserMapping]:
    """Read explicit legacy-user ownership and timezone mapping from JSON."""
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("Cannot read legacy user mapping manifest") from error
    if not isinstance(data, dict) or not isinstance(data.get("legacyUsers"), dict):
        raise ValueError("Manifest must contain a legacyUsers object")

    mappings: dict[str, LegacyUserMapping] = {}
    for legacy_user_id, raw in data["legacyUsers"].items():
        if not isinstance(legacy_user_id, str) or not USER_ID.fullmatch(legacy_user_id):
            raise ValueError("Manifest legacy user IDs must be decimal strings")
        if not isinstance(raw, dict):
            raise ValueError("Manifest mapping entries must be objects")
        try:
            tenant_id = str(UUID(raw["tenantId"]))
            owner_user_id = str(UUID(raw["ownerUserId"]))
        except (KeyError, TypeError, ValueError) as error:
            raise ValueError("Manifest mapping requires valid tenantId and ownerUserId") from error
        timezone_name = raw.get("timezone")
        if not isinstance(timezone_name, str):
            timezone_name = ""
        mappings[legacy_user_id] = LegacyUserMapping(tenant_id, owner_user_id, timezone_name)
    return mappings


def extract_goal_history(
    source_database: str | Path,
    mappings: Mapping[str, LegacyUserMapping],
) -> ExtractionReport:
    """Extract every matching SQLite setting without opening the database for writes."""
    source_path = Path(source_database).resolve()
    uri = f"{source_path.as_uri()}?mode=ro"
    try:
        db = sqlite3.connect(uri, uri=True)
    except sqlite3.Error as error:
        raise ValueError("Cannot open legacy SQLite database read-only") from error

    imports: dict[tuple[UUID, UUID], list[dict]] = defaultdict(list)
    quarantined: list[QuarantinedEntry] = []
    source_entries = 0
    occurrences: dict[tuple[str, str], int] = defaultdict(int)
    with db:
        rows = db.execute(
            "SELECT key, value FROM settings WHERE key LIKE 'advice:goal_history:%' ORDER BY key"
        ).fetchall()
    db.close()

    for setting_key, raw_value in rows:
        match = HISTORY_KEY.fullmatch(setting_key or "")
        if not match:
            continue
        legacy_user_id = match.group(1)
        if not USER_ID.fullmatch(legacy_user_id):
            source_entries += 1
            quarantined.append(QuarantinedEntry(legacy_user_id, setting_key, -1, "invalid_user_id"))
            continue
        try:
            entries = json.loads(raw_value)
        except (TypeError, ValueError):
            source_entries += 1
            quarantined.append(QuarantinedEntry(legacy_user_id, setting_key, -1, "invalid_json"))
            continue
        if not isinstance(entries, list):
            source_entries += 1
            quarantined.append(QuarantinedEntry(legacy_user_id, setting_key, -1, "invalid_history_shape"))
            continue
        source_entries += len(entries)
        mapping = mappings.get(legacy_user_id)
        if mapping is None:
            for index in range(len(entries)):
                quarantined.append(QuarantinedEntry(legacy_user_id, setting_key, index, "unmapped_user"))
            continue
        try:
            tenant_id = UUID(mapping.tenant_id)
            owner_user_id = UUID(mapping.owner_user_id)
        except (TypeError, ValueError):
            for index in range(len(entries)):
                quarantined.append(QuarantinedEntry(legacy_user_id, setting_key, index, "invalid_owner_mapping"))
            continue
        try:
            zone = ZoneInfo(mapping.timezone) if mapping.timezone else None
        except ZoneInfoNotFoundError:
            zone = None
            timezone_error = "invalid_timezone"
        else:
            timezone_error = "missing_timezone" if zone is None else ""

        for index, entry in enumerate(entries):
            if timezone_error:
                quarantined.append(QuarantinedEntry(legacy_user_id, setting_key, index, timezone_error))
                continue
            try:
                normalized, fingerprint = _normalize_entry(entry, zone)
            except ValueError as error:
                quarantined.append(QuarantinedEntry(legacy_user_id, setting_key, index, str(error)))
                continue
            occurrence_key = (legacy_user_id, fingerprint)
            occurrence = occurrences[occurrence_key]
            occurrences[occurrence_key] += 1
            if occurrence > 9999:
                quarantined.append(QuarantinedEntry(legacy_user_id, setting_key, index,
                                                    "duplicate_occurrence_limit"))
                continue
            source_hash = hashlib.sha256(
                f"{legacy_user_id}\0{fingerprint}".encode("utf-8")
            ).hexdigest()
            normalized["legacyKey"] = f"goal-history:{source_hash}:{occurrence}"
            imports[(tenant_id, owner_user_id)].append(normalized)

    return ExtractionReport(source_entries, dict(imports), quarantined)


def _normalize_entry(entry: object, zone: ZoneInfo) -> tuple[dict, str]:
    if not isinstance(entry, dict):
        raise ValueError("invalid_entry")
    name = entry.get("name")
    key = entry.get("key")
    if not isinstance(name, str) or not name.strip():
        raise ValueError("missing_name")
    if not isinstance(key, str) or not key.strip():
        raise ValueError("missing_key")
    if len(name) > 200 or len(key) > 256:
        raise ValueError("text_too_long")
    unit = entry.get("unit")
    if unit not in ("count", "sum"):
        raise ValueError("invalid_unit")

    legacy_target = _integer(entry.get("target"), "invalid_target")
    if unit == "count" and legacy_target < 1:
        raise ValueError("invalid_target")
    bought = _integer(entry.get("bought"), "invalid_bought")
    legacy_limit = _money(entry.get("limit"), nullable=True)
    if unit == "sum" and legacy_limit is None:
        raise ValueError("missing_monthly_limit")
    spent = _money(entry.get("spent"), nullable=True)
    saved = _money(entry.get("saved"), nullable=True)
    met = entry.get("met")
    if met is not None and not isinstance(met, bool):
        raise ValueError("invalid_met")
    window = entry.get("window")
    if not isinstance(window, str) or not window.strip() or len(window) > 128:
        raise ValueError("invalid_window")

    accepted_at = _timestamp(entry.get("started_at"), zone)
    completed_at = _timestamp(entry.get("closed_at"), zone)
    if completed_at < accepted_at:
        raise ValueError("completed_before_accepted")
    monthly_limit = legacy_limit if unit == "sum" else None
    result = {
        "key": key,
        "name": name,
        "scope": "group" if key.strip().startswith("cat:") else "product",
        "unit": unit,
        "legacyTarget": legacy_target,
        "legacyLimit": legacy_limit,
        "countTarget": legacy_target if unit == "count" else 0,
        "monthlyLimit": monthly_limit,
        "bought": bought,
        "spent": spent,
        "met": met,
        "saved": saved,
        "window": window,
        "acceptedAt": accepted_at,
        "completedAt": completed_at,
    }
    fingerprint = json.dumps(entry, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return result, fingerprint


def _integer(value: object, reason: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= 2_147_483_647:
        raise ValueError(reason)
    return value


def _money(value: object, nullable: bool) -> str | None:
    if value is None and nullable:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float, str)):
        raise ValueError("invalid_money")
    try:
        amount = Decimal(str(value))
    except InvalidOperation as error:
        raise ValueError("invalid_money") from error
    if not amount.is_finite() or amount < 0:
        raise ValueError("invalid_money")
    try:
        quantized = amount.quantize(Decimal("0.01"))
    except InvalidOperation as error:
        raise ValueError("invalid_money") from error
    if quantized != amount:
        raise ValueError("invalid_money_precision")
    normalized = format(quantized, ".2f")
    if not MONEY.fullmatch(normalized):
        raise ValueError("invalid_money")
    return normalized


def _timestamp(value: object, zone: ZoneInfo) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError("invalid_datetime")
    try:
        parsed = datetime.fromisoformat(value.strip())
    except ValueError as error:
        raise ValueError("invalid_datetime") from error
    if parsed.tzinfo is not None and parsed.utcoffset() is not None:
        return parsed.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

    candidates: dict[object, datetime] = {}
    for fold in (0, 1):
        local = parsed.replace(tzinfo=zone, fold=fold)
        utc = local.astimezone(timezone.utc)
        round_trip = utc.astimezone(zone).replace(tzinfo=None)
        if round_trip == parsed:
            candidates[local.utcoffset()] = utc
    if not candidates:
        raise ValueError("nonexistent_local_time")
    if len(candidates) > 1:
        raise ValueError("ambiguous_local_time")
    instant = next(iter(candidates.values()))
    return instant.isoformat().replace("+00:00", "Z")


def import_goal_history(
    base_url: str,
    service_token: str,
    imports: Mapping[tuple[UUID, UUID], list[dict]],
    *,
    sender: Callable[[str, dict[str, str], bytes], tuple[int, dict]] | None = None,
) -> ImportReport:
    """Upload bounded owner batches. Stop if server response cannot account for all rows."""
    parsed_url = urlsplit(base_url)
    if parsed_url.scheme not in ("https", "http") or not parsed_url.hostname:
        raise ValueError("Core URL must be an HTTP or HTTPS origin")
    if parsed_url.username or parsed_url.password:
        raise ValueError("Core URL must not contain credentials")
    if parsed_url.scheme == "http" and not _is_loopback(parsed_url.hostname):
        raise ValueError("Core URL must use HTTPS outside loopback")
    if not service_token:
        raise ValueError("Migration service token is required")
    send = sender or _http_sender
    sent = inserted = already_present = 0
    endpoint = base_url.rstrip("/") + IMPORT_PATH

    for tenant_id, owner_user_id in sorted(imports, key=lambda item: (str(item[0]), str(item[1]))):
        rows = imports[(tenant_id, owner_user_id)]
        for start in range(0, len(rows), MAX_BATCH_SIZE):
            batch = rows[start:start + MAX_BATCH_SIZE]
            request_body = json.dumps({
                "tenantId": str(tenant_id),
                "ownerUserId": str(owner_user_id),
                "outcomes": batch,
            }, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            status, response = send(endpoint, {
                "Content-Type": "application/json",
                "X-Finance-Migration-Token": service_token,
            }, request_body)
            if status < 200 or status >= 300:
                raise ValueError(f"Goal history import failed with HTTP {status}")
            try:
                response_inserted = response["inserted"]
                response_existing = response["alreadyPresent"]
            except (KeyError, TypeError) as error:
                raise ValueError("Goal history import response is incomplete") from error
            if (isinstance(response_inserted, bool) or not isinstance(response_inserted, int)
                    or isinstance(response_existing, bool) or not isinstance(response_existing, int)
                    or response_inserted < 0 or response_existing < 0
                    or response_inserted + response_existing != len(batch)):
                raise ValueError("Goal history import response accounting mismatch")
            sent += len(batch)
            inserted += response_inserted
            already_present += response_existing
    return ImportReport(sent, inserted, already_present)


def _is_loopback(host: str) -> bool:
    if host.lower() == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def _http_sender(url: str, headers: dict[str, str], body: bytes) -> tuple[int, dict]:
    request = urllib.request.Request(url, data=body, headers=headers, method="POST")
    opener = urllib.request.build_opener(_RejectRedirects())
    try:
        with opener.open(request, timeout=30) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        return error.code, {}


class _RejectRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, _request, _fp, _code, _message, _headers, _new_url):
        return None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Import legacy goal history into Core")
    parser.add_argument("source_database", type=Path)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--core-url")
    parser.add_argument("--apply", action="store_true", help="Upload extracted rows; default is dry-run")
    parser.add_argument("--allow-quarantine", action="store_true",
                        help="Allow valid rows to import after quarantine review")
    args = parser.parse_args(argv)

    try:
        report = extract_goal_history(args.source_database, load_manifest(args.manifest))
        summary = {
            "sourceEntries": report.source_entries,
            "owners": len(report.imports),
            "importable": sum(len(rows) for rows in report.imports.values()),
            "quarantined": len(report.quarantined),
            "quarantine": [item.__dict__ for item in report.quarantined],
        }
        if args.apply:
            if report.quarantined and not args.allow_quarantine:
                raise ValueError("Quarantine entries need review; pass --allow-quarantine after review")
            token = os.environ.get("FINANCE_MIGRATION_SERVICE_TOKEN", "")
            if not args.core_url:
                raise ValueError("--core-url is required with --apply")
            uploaded = import_goal_history(args.core_url, token, report.imports)
            summary["upload"] = uploaded.__dict__
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 0
    except (ValueError, sqlite3.Error) as error:
        print(str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
