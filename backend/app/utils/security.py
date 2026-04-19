import base64
import hashlib
import hmac
import json
import secrets
from datetime import datetime, timedelta, timezone

from app.config import ACCESS_TOKEN_EXPIRE_MINUTES, JWT_ALGORITHM, JWT_SECRET_KEY

HASH_ALGORITHM = "pbkdf2_sha256"
HASH_ITERATIONS = 260000


def hash_password(password: str) -> str:
    salt = secrets.token_urlsafe(16)
    derived_key = hashlib.pbkdf2_hmac(
        "sha256",
        password.encode("utf-8"),
        salt.encode("utf-8"),
        HASH_ITERATIONS,
    )
    password_hash = base64.urlsafe_b64encode(derived_key).decode("ascii")
    return f"{HASH_ALGORITHM}${HASH_ITERATIONS}${salt}${password_hash}"


def verify_password(password: str, password_hash: str) -> bool:
    try:
        algorithm, iterations, salt, expected_hash = password_hash.split("$", 3)
    except ValueError:
        return False

    if algorithm != HASH_ALGORITHM:
        return False

    derived_key = hashlib.pbkdf2_hmac(
        "sha256",
        password.encode("utf-8"),
        salt.encode("utf-8"),
        int(iterations),
    )
    actual_hash = base64.urlsafe_b64encode(derived_key).decode("ascii")
    return hmac.compare_digest(actual_hash, expected_hash)


def create_access_token(subject: str, expires_delta: timedelta | None = None) -> str:
    if JWT_ALGORITHM != "HS256":
        raise ValueError("Only HS256 JWT tokens are supported")

    now = datetime.now(timezone.utc)
    expires_at = now + (expires_delta or timedelta(minutes=ACCESS_TOKEN_EXPIRE_MINUTES))
    payload = {
        "sub": subject,
        "iat": int(now.timestamp()),
        "exp": int(expires_at.timestamp()),
    }
    return _encode_jwt(payload)


def decode_access_token(token: str) -> dict:
    if JWT_ALGORITHM != "HS256":
        raise ValueError("Only HS256 JWT tokens are supported")

    parts = token.split(".")
    if len(parts) != 3:
        raise ValueError("Invalid token format")

    signing_input = f"{parts[0]}.{parts[1]}".encode("ascii")
    expected_signature = _sign(signing_input)
    actual_signature = _base64url_decode(parts[2])
    if not hmac.compare_digest(actual_signature, expected_signature):
        raise ValueError("Invalid token signature")

    header = json.loads(_base64url_decode(parts[0]).decode("utf-8"))
    if header.get("alg") != JWT_ALGORITHM or header.get("typ") != "JWT":
        raise ValueError("Invalid token header")

    payload = json.loads(_base64url_decode(parts[1]).decode("utf-8"))
    expires_at = payload.get("exp")
    if expires_at is None or datetime.now(timezone.utc).timestamp() > float(expires_at):
        raise ValueError("Token expired")

    return payload


def _encode_jwt(payload: dict) -> str:
    header = {"alg": JWT_ALGORITHM, "typ": "JWT"}
    header_segment = _base64url_encode(json.dumps(header, separators=(",", ":")).encode("utf-8"))
    payload_segment = _base64url_encode(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    signing_input = f"{header_segment}.{payload_segment}".encode("ascii")
    signature_segment = _base64url_encode(_sign(signing_input))
    return f"{header_segment}.{payload_segment}.{signature_segment}"


def _sign(signing_input: bytes) -> bytes:
    return hmac.new(JWT_SECRET_KEY.encode("utf-8"), signing_input, hashlib.sha256).digest()


def _base64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _base64url_decode(data: str) -> bytes:
    padding = "=" * (-len(data) % 4)
    return base64.urlsafe_b64decode(data + padding)
