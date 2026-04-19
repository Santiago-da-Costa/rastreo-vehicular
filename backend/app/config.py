import os
import secrets
from pathlib import Path

from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent.parent
ENV_PATH = BASE_DIR / ".env"

load_dotenv(dotenv_path=ENV_PATH)

DATABASE_URL = os.getenv(
    "DATABASE_URL",
    "sqlite:///./rastreo.db",
)

JWT_SECRET_KEY = os.getenv("JWT_SECRET_KEY") or secrets.token_urlsafe(32)
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
