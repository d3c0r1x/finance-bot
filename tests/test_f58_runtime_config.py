from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_environment_template_has_safe_saas_defaults_and_secret_placeholders():
    template = (ROOT / ".env.example").read_text(encoding="utf-8")
    required = (
        "POSTGRES_DB=finance",
        "POSTGRES_USER=finance",
        "REDIS_URL=redis://localhost:6379/0",
        "KEYCLOAK_ISSUER_URI=http://localhost:8081/realms/finance",
        "FINANCE_TELEGRAM_BOT_TOKEN=replace-me",
        "FINANCE_TELEGRAM_SERVICE_TOKEN=replace-me",
        "FINANCE_AI_POLICY=local-only",
    )
    for entry in required:
        assert entry in template, f"missing safe configuration example: {entry}"
    assert "123456789:ABCdef" not in template


def test_gitignore_protects_local_secrets_and_keeps_example_template_trackable():
    ignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
    assert ".env" in ignore
    assert ".env.*" in ignore
    assert "!.env.example" in ignore
    assert "*.jks" in ignore
    assert "*.keystore" in ignore
    assert "var/" in ignore


def test_docker_context_excludes_secrets_user_data_and_git_metadata():
    dockerignore = (ROOT / ".dockerignore").read_text(encoding="utf-8")
    for pattern in (".env*", "!.env.example", "**/*.pem", "**/*.key", "data/", "var/", ".git/"):
        assert pattern in dockerignore, f"Docker build context must exclude {pattern}"


def test_cross_platform_dev_entrypoints_dispatch_legacy_modes_without_secret_output():
    powershell = (ROOT / "scripts" / "dev.ps1").read_text(encoding="utf-8")
    shell = (ROOT / "scripts" / "dev.sh").read_text(encoding="utf-8")
    for text in (powershell, shell):
        assert "legacy-bot" in text
        assert "legacy-panel" in text
        assert "infisical" in text.lower()
        assert "secret value" not in text.lower()
    assert ".venv\\Scripts\\python.exe" in powershell
    assert ".venv/bin/python" in shell
    assert (ROOT / "start_bot.bat").read_text(encoding="utf-8").lower().count("dev.ps1") == 1
    assert (ROOT / "start_panel.bat").read_text(encoding="utf-8").lower().count("dev.ps1") == 1


def test_developer_docs_describe_real_windows_and_linux_commands_and_compose_status():
    development = (ROOT / "docs" / "DEVELOPMENT.md").read_text(encoding="utf-8")
    readme = (ROOT / "README.md").read_text(encoding="utf-8")
    security = (ROOT / "docs" / "SECURITY.md").read_text(encoding="utf-8")
    for text in (development, readme):
        assert "scripts/dev.ps1" in text
        assert "scripts/dev.sh" in text
    assert "Compose" in development
    assert "do not exist yet" in development.lower()
    assert ".env.example" in security
    assert "legacy Python app" in security
    assert "SaaS target" in security


def test_mit_license_and_original_copyright_are_preserved():
    license_text = (ROOT / "LICENSE").read_text(encoding="utf-8")
    assert license_text.startswith("MIT License\n\nCopyright (c) 2026 d3c0r1x")
    assert "THE SOFTWARE IS PROVIDED \"AS IS\"" in license_text
