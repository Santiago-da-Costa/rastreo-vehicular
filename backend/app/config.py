import os
import secrets
from pathlib import Path

from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent.parent
ENV_PATH = BASE_DIR / ".env"

load_dotenv(dotenv_path=ENV_PATH)

ENVIRONMENT = os.getenv("ENVIRONMENT", "local").lower()
IS_PRODUCTION = ENVIRONMENT in {"prod", "production"}


def _parse_csv(value):
    if not value:
        return []
    return [item.strip().rstrip("/") for item in value.split(",") if item.strip()]


def _normalize_database_url(database_url):
    if database_url.startswith("postgres://"):
        return database_url.replace("postgres://", "postgresql+psycopg://", 1)
    if database_url.startswith("postgresql://"):
        return database_url.replace("postgresql://", "postgresql+psycopg://", 1)
    return database_url


_DATABASE_URL = os.getenv("DATABASE_URL")
if IS_PRODUCTION and not _DATABASE_URL:
    raise RuntimeError("DATABASE_URL debe configurarse en produccion.")

DATABASE_URL = _normalize_database_url(_DATABASE_URL or "sqlite:///./rastreo.db")

API_PUBLIC_URL = os.getenv("API_PUBLIC_URL", "").rstrip("/")
FRONTEND_PUBLIC_URL = os.getenv("FRONTEND_PUBLIC_URL", "").rstrip("/")

DEFAULT_CORS_ORIGINS = [
    "http://localhost:8000",
    "http://127.0.0.1:8000",
    "http://localhost:5500",
    "http://127.0.0.1:5500",
]
CORS_ORIGINS = _parse_csv(os.getenv("CORS_ORIGINS"))
if FRONTEND_PUBLIC_URL:
    CORS_ORIGINS.append(FRONTEND_PUBLIC_URL)
if not CORS_ORIGINS:
    CORS_ORIGINS = DEFAULT_CORS_ORIGINS

JWT_SECRET_KEY = os.getenv("JWT_SECRET_KEY")
if not JWT_SECRET_KEY:
    if IS_PRODUCTION:
        raise RuntimeError("JWT_SECRET_KEY debe configurarse en produccion.")
    JWT_SECRET_KEY = secrets.token_urlsafe(32)
JWT_ALGORITHM = os.getenv("JWT_ALGORITHM", "HS256")
ACCESS_TOKEN_EXPIRE_MINUTES = int(os.getenv("ACCESS_TOKEN_EXPIRE_MINUTES", "60"))

CREATE_INITIAL_ADMIN = os.getenv("CREATE_INITIAL_ADMIN", "false").lower() in {
    "1",
    "true",
    "yes",
}
INITIAL_ADMIN_USERNAME = os.getenv("INITIAL_ADMIN_USERNAME")
INITIAL_ADMIN_PASSWORD = os.getenv("INITIAL_ADMIN_PASSWORD")
INITIAL_ADMIN_FULL_NAME = os.getenv("INITIAL_ADMIN_FULL_NAME", "Administrador")
INITIAL_ADMIN_EMAIL = os.getenv("INITIAL_ADMIN_EMAIL")
