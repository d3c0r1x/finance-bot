from pathlib import Path
import json

import yaml


ROOT = Path(__file__).resolve().parents[1]
COMPOSE_PATH = ROOT / "infra/compose/compose.yml"


def _compose():
    return yaml.safe_load(COMPOSE_PATH.read_text(encoding="utf-8"))


def _service_ports(service):
    return service.get("ports", [])


def test_core_profile_provides_postgres_and_executable_java_core():
    config = _compose()
    services = config["services"]

    assert {"postgres", "core"}.issubset(services)
    assert "core" in services["postgres"]["profiles"]
    assert "core" in services["core"]["profiles"]

    core_build = services["core"]["build"]
    assert (ROOT / "infra/compose" / core_build["context"] / core_build["dockerfile"]).resolve().is_file()


def test_database_bootstrap_uses_non_superuser_and_preserves_database_files():
    config = _compose()
    postgres = config["services"]["postgres"]
    environment = postgres["environment"]

    assert environment["POSTGRES_DB"] == "finance"
    assert environment["POSTGRES_USER"] != "finance_app"
    assert environment["POSTGRES_PASSWORD"].startswith("${")
    assert environment["FINANCE_APP_PASSWORD"].startswith("${")
    assert any("/var/lib/postgresql" in volume for volume in postgres["volumes"])
    assert postgres["healthcheck"]["test"]

    core = config["services"]["core"]
    assert core["environment"]["DATABASE_USERNAME"] == "finance_app"
    assert core["environment"]["DATABASE_MIGRATION_USERNAME"] == "finance_migrator"
    assert core["environment"]["DATABASE_MIGRATION_PASSWORD"].startswith("${")

    init_dir = ROOT / "infra/compose/postgres"
    init_scripts = list(init_dir.glob("*.sh"))
    sql_templates = list(init_dir.glob("*.sql.template"))
    assert init_scripts and sql_templates, "PostgreSQL must create the Core login role during first initialization"
    bootstrap = "\n".join(
        script.read_text(encoding="utf-8") for script in init_scripts + sql_templates
    ).lower()
    assert "finance_app" in bootstrap
    assert "finance_migrator" in bootstrap
    assert "nosuperuser" in bootstrap
    assert "nobypassrls" in bootstrap
    assert "\\getenv finance_app_password finance_app_password" in bootstrap
    assert "\\getenv finance_migrator_password finance_migrator_password" in bootstrap
    assert "--set=finance_app_password" not in bootstrap
    assert "--set=finance_migrator_password" not in bootstrap
    assert "unset finance_app_password" in bootstrap
    assert "unset finance_migrator_password" in bootstrap
    assert "alter database finance owner to finance_migrator" in bootstrap
    assert "alter database finance owner to finance_app" not in bootstrap
    assert "alter default privileges for role finance_migrator" in bootstrap
    assert "grant select, insert, update, delete on all tables in schema public to finance_app" in bootstrap

    application = (ROOT / "services/core/src/main/resources/application.yml").read_text(encoding="utf-8")
    assert "user: ${DATABASE_MIGRATION_USERNAME:" in application
    assert "password: ${DATABASE_MIGRATION_PASSWORD:" in application


def test_core_waits_for_healthy_database_and_uses_container_network_and_durable_receipts():
    config = _compose()
    core = config["services"]["core"]
    environment = core["environment"]

    assert core["depends_on"]["postgres"]["condition"] == "service_healthy"
    assert core["depends_on"]["keycloak"]["condition"] == "service_healthy"
    assert environment["DATABASE_URL"] == "jdbc:postgresql://postgres:5432/finance"
    assert environment["KEYCLOAK_ISSUER_URI"] == "http://keycloak:8080/realms/finance"
    assert environment["KEYCLOAK_JWK_SET_URI"].startswith("http://keycloak:8080/")
    assert environment["KEYCLOAK_WEB_CLIENT_SECRET"] == "finance-local-dev-secret-not-for-production"
    assert environment["DATABASE_USERNAME"] == "finance_app"
    assert environment["DATABASE_PASSWORD"].startswith("${")
    assert environment["FINANCE_RECEIPTS_FILESYSTEM_ROOT"] == "/var/lib/finance/receipts"
    assert any("/var/lib/finance/receipts" in volume for volume in core["volumes"])
    assert core["healthcheck"]["test"]


def test_keycloak_is_imported_persisted_and_bound_to_loopback_for_local_runtime():
    config = _compose()
    keycloak = config["services"]["keycloak"]

    assert "core" in keycloak["profiles"]
    assert "--import-realm" in keycloak["command"]
    assert any("realm-finance.json" in volume for volume in keycloak["volumes"])
    assert any("keycloak_data" in volume for volume in keycloak["volumes"])
    assert keycloak["healthcheck"]["test"]
    assert keycloak["healthcheck"]["test"] == ["CMD", "java", "-cp", "/opt/keycloak", "Healthcheck"]
    assert keycloak["ports"][0]["host_ip"] == "127.0.0.1"
    assert keycloak["environment"]["KC_HOSTNAME"] == "http://keycloak:8080"
    realm = json.loads((ROOT / "ops/keycloak/realm-finance.json").read_text(encoding="utf-8"))
    assert realm["registrationAllowed"] is True
    assert realm["verifyEmail"] is True
    assert realm["resetPasswordAllowed"] is True
    web = next(client for client in realm["clients"] if client["clientId"] == "finance-web")
    assert config["services"]["core"]["environment"]["KEYCLOAK_WEB_CLIENT_ID"] == web["clientId"]
    assert config["services"]["core"]["environment"]["KEYCLOAK_WEB_CLIENT_SECRET"] == web["secret"]

    build = keycloak["build"]
    dockerfile = ROOT / "infra/compose" / build["context"] / build["dockerfile"]
    image_source = dockerfile.read_text(encoding="utf-8").lower()
    assert "javac --release 11" in image_source
    assert "healthcheck.class" in image_source


def test_any_published_core_profile_ports_are_loopback_only():
    config = _compose()

    for service_name, service in config["services"].items():
        if "core" not in service.get("profiles", []):
            continue
        for published in _service_ports(service):
            assert isinstance(published, dict), f"{service_name} must use explicit host_ip bindings"
            assert published.get("host_ip") == "127.0.0.1", f"{service_name} exposes a non-loopback port"


def test_java_core_image_builds_locked_project_and_runs_healthcheckable_jar():
    dockerfile = ROOT / "services/core/Dockerfile"
    source = dockerfile.read_text(encoding="utf-8").lower()

    assert "gradlew" in source
    assert ":services:core:bootjar" in source
    assert '"java", "-jar"' in source
    assert "expose 8080" in source
    assert "healthcheck" in source
