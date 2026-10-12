"""Guard the agreed Android + Telegram product surface."""

from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]


def test_no_user_facing_web_client_or_browser_bff():
    assert not (ROOT / "apps/web").exists()
    core = ROOT / "services/core/src/main/java/com/decorix/finance/core/api"
    assert not any("/bff" in path.read_text("utf-8") for path in core.glob("*.java"))
    paths = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))["paths"]
    assert not any(path.startswith("/bff/") for path in paths)


def test_android_calls_only_resource_server_api():
    android = ROOT / "apps/android/app/src/main"
    assert not any("/bff" in path.read_text("utf-8") for path in android.rglob("*.kt"))
    security = (ROOT / "services/core/src/main/java/com/decorix/finance/core/config/SecurityConfiguration.java")
    if security.exists():
        assert "oauth2Login" not in security.read_text("utf-8")
