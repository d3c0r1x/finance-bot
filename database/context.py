"""Optional request-local database routing; Telegram keeps its configured path."""
from contextvars import ContextVar

request_database: ContextVar[str | None] = ContextVar("request_database", default=None)


def database_path(default: str) -> str:
    return request_database.get() or default
