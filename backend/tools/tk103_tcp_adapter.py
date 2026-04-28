import json
import os
import re
import socketserver
from datetime import datetime, timezone
from typing import Any
from urllib import error, request


TCP_HOST = os.getenv("TCP_HOST", "0.0.0.0")
TCP_PORT = int(os.getenv("TCP_PORT", "5000"))
INGEST_URL = os.getenv("INGEST_URL", "http://127.0.0.1:8000/ingest/gps")
DEVICE_TOKEN = os.getenv("DEVICE_TOKEN", "token-secreto-de-prueba")
FALLBACK_DEVICE_UID = os.getenv("DEVICE_UID", "123456789012345")
CLIENT_TIMEOUT_SECONDS = float(os.getenv("TCP_CLIENT_TIMEOUT", "15"))


IMEI_REGEX = re.compile(r"imei[:=]?\s*(\d{10,17})", re.IGNORECASE)
ANY_IMEI_REGEX = re.compile(r"(?<!\d)(\d{15})(?!\d)")


def log(message: str) -> None:
    print(message, flush=True)


def iso_utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def convert_nmea_coord(value: str, hemisphere: str) -> float:
    text = value.strip()
    hemi = hemisphere.strip().upper()
    if hemi not in {"N", "S", "E", "W"}:
        raise ValueError(f"Unsupported hemisphere: {hemisphere!r}")

    whole, _, fraction = text.partition(".")
    if not whole.isdigit() or len(whole) < 4:
        raise ValueError(f"Invalid NMEA coordinate: {value!r}")

    degree_digits = 2 if hemi in {"N", "S"} else 3
    if len(whole) <= degree_digits:
        raise ValueError(f"Invalid NMEA coordinate length: {value!r}")

    degrees = int(whole[:degree_digits])
    minutes_text = whole[degree_digits:] + (f".{fraction}" if fraction else "")
    minutes = float(minutes_text)
    decimal = degrees + (minutes / 60.0)
    if hemi in {"S", "W"}:
        decimal *= -1.0
    return decimal


def parse_tk103_message(raw_message: str) -> dict[str, Any]:
    message = raw_message.strip().strip("\x00")
    tokens = [token.strip() for token in message.replace(";", ",").split(",")]

    parsed: dict[str, Any] = {
        "raw_message": message,
        "timestamp": extract_timestamp(tokens, message),
    }

    device_uid = extract_device_uid(tokens, message)
    if device_uid:
        parsed["device_uid"] = device_uid

    coords = extract_coords_and_speed(tokens)
    if coords is not None:
        parsed.update(coords)

    return parsed


def extract_device_uid(tokens: list[str], message: str) -> str | None:
    match = IMEI_REGEX.search(message)
    if match:
        return match.group(1)

    for token in tokens:
        cleaned = token.strip()
        if cleaned.isdigit() and len(cleaned) == 15:
            return cleaned

    fallback_match = ANY_IMEI_REGEX.search(message)
    if fallback_match:
        return fallback_match.group(1)

    return None


def extract_timestamp(tokens: list[str], message: str) -> str:
    combined_match = re.search(r"(?<!\d)(\d{12})(?!\d)", message)
    if combined_match:
        return yymmdd_hhmmss_to_iso(combined_match.group(1))

    date_token: str | None = None
    time_token: str | None = None
    for token in tokens:
        clean = token.strip()
        if date_token is None and re.fullmatch(r"\d{6}", clean):
            date_token = clean
            continue
        if time_token is None and re.fullmatch(r"\d{6}(?:\.\d+)?", clean):
            time_token = clean[:6]

    if date_token and time_token:
        try:
            return yymmdd_hhmmss_to_iso(date_token + time_token)
        except ValueError:
            pass

    return iso_utc_now()


def yymmdd_hhmmss_to_iso(value: str) -> str:
    parsed = datetime.strptime(value, "%y%m%d%H%M%S").replace(tzinfo=timezone.utc)
    return parsed.isoformat().replace("+00:00", "Z")


def extract_coords_and_speed(tokens: list[str]) -> dict[str, Any] | None:
    for index in range(len(tokens) - 3):
        lat_value = tokens[index]
        lat_hemi = tokens[index + 1].upper()
        lon_value = tokens[index + 2]
        lon_hemi = tokens[index + 3].upper()

        if lat_hemi not in {"N", "S"} or lon_hemi not in {"E", "W"}:
            continue
        if not is_nmea_token(lat_value, 2) or not is_nmea_token(lon_value, 3):
            continue

        lat = convert_nmea_coord(lat_value, lat_hemi)
        lon = convert_nmea_coord(lon_value, lon_hemi)

        speed_kmh = None
        speed_index = index + 4
        if speed_index < len(tokens):
            speed_kmh = parse_float_token(tokens[speed_index])

        return {
            "lat": lat,
            "lon": lon,
            "speed_kmh": speed_kmh,
        }

    return None


def is_nmea_token(value: str, degree_digits: int) -> bool:
    text = value.strip()
    if not re.fullmatch(r"\d+(?:\.\d+)?", text):
        return False
    whole = text.split(".", 1)[0]
    return len(whole) >= degree_digits + 2


def parse_float_token(value: str) -> float | None:
    text = value.strip()
    if not text:
        return None
    try:
        return float(text)
    except ValueError:
        return None


def post_to_ingest(payload: dict[str, Any]) -> tuple[bool, str]:
    body = json.dumps(payload, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
    headers = {
        "Authorization": f"Bearer {DEVICE_TOKEN}",
        "Content-Type": "application/json",
    }
    http_request = request.Request(INGEST_URL, data=body, headers=headers, method="POST")

    try:
        with request.urlopen(http_request, timeout=10) as response:
            response_body = response.read().decode("utf-8", errors="replace")
            return True, f"status={response.status} body={response_body}"
    except error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        return False, f"status={exc.code} body={detail}"
    except error.URLError as exc:
        return False, str(exc.reason)
    except Exception as exc:  # pragma: no cover - defensive logging
        return False, str(exc)


def split_raw_messages(buffer: str) -> list[str]:
    messages: list[str] = []
    normalized = buffer.replace("\r\n", "\n").replace("\r", "\n")
    for line in normalized.split("\n"):
        chunk = line.strip()
        if not chunk:
            continue
        parts = re.findall(r"[^;]+;?", chunk)
        for part in parts:
            cleaned = part.strip()
            if cleaned:
                messages.append(cleaned)
    return messages


def build_ingest_payload(parsed: dict[str, Any]) -> dict[str, Any]:
    device_uid = parsed.get("device_uid") or FALLBACK_DEVICE_UID
    return {
        "device_uid": device_uid,
        "timestamp": parsed.get("timestamp") or iso_utc_now(),
        "lat": parsed["lat"],
        "lon": parsed["lon"],
        "speed_kmh": parsed.get("speed_kmh"),
        "heading": None,
        "accuracy_m": None,
        "altitude_m": None,
        "ignition": None,
        "battery": None,
        "raw": {
            "source": "tk103_tcp",
            "raw_message": parsed["raw_message"],
        },
    }


def process_message(raw_message: str) -> None:
    log(f"RAW: {raw_message}")
    parsed = parse_tk103_message(raw_message)

    if "lat" not in parsed or "lon" not in parsed:
        log(f"UNPARSED: {raw_message}")
        return

    if "device_uid" not in parsed:
        log(f"PARSED without IMEI, using fallback device_uid={FALLBACK_DEVICE_UID}")

    log(f"PARSED: {json.dumps(parsed, ensure_ascii=True, separators=(',', ':'))}")
    payload = build_ingest_payload(parsed)
    ok, detail = post_to_ingest(payload)
    if ok:
        log(f"POST OK: {detail}")
    else:
        log(f"POST ERROR: {detail}")


class ThreadedTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True


class Tk103TCPHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        host, port = self.client_address
        log(f"Connection from {host}:{port}")
        self.request.settimeout(CLIENT_TIMEOUT_SECONDS)

        chunks: list[bytes] = []
        while True:
            try:
                data = self.request.recv(4096)
            except TimeoutError:
                break
            except OSError as exc:
                log(f"TCP receive error from {host}:{port}: {exc}")
                return

            if not data:
                break
            chunks.append(data)

        if not chunks:
            return

        text = b"".join(chunks).decode("utf-8", errors="replace")
        for message in split_raw_messages(text):
            process_message(message)


def main() -> None:
    with ThreadedTCPServer((TCP_HOST, TCP_PORT), Tk103TCPHandler) as server:
        log(f"TCP adapter listening on {TCP_HOST}:{TCP_PORT}")
        server.serve_forever()


if __name__ == "__main__":
    main()
