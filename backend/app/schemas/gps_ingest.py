from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, field_validator


class GpsIngestRequest(BaseModel):
    device_uid: str
    timestamp: datetime
    lat: float
    lon: float
    speed_kmh: float | None = None
    heading: float | None = None
    accuracy_m: float | None = None
    altitude_m: float | None = None
    ignition: bool | None = None
    battery: float | None = None
    raw: dict[str, Any] | list[Any] | str | int | float | bool | None = None

    @field_validator("device_uid")
    @classmethod
    def validate_device_uid(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("device_uid cannot be empty")
        return value

    @field_validator("lat")
    @classmethod
    def validate_lat(cls, value: float) -> float:
        if value < -90 or value > 90:
            raise ValueError("lat must be between -90 and 90")
        return value

    @field_validator("lon")
    @classmethod
    def validate_lon(cls, value: float) -> float:
        if value < -180 or value > 180:
            raise ValueError("lon must be between -180 and 180")
        return value

    @field_validator("speed_kmh", "accuracy_m")
    @classmethod
    def validate_non_negative(cls, value: float | None) -> float | None:
        if value is not None and value < 0:
            raise ValueError("value must be non-negative")
        return value

    @field_validator("battery")
    @classmethod
    def validate_battery(cls, value: float | None) -> float | None:
        if value is not None and (value < 0 or value > 100):
            raise ValueError("battery must be between 0 and 100")
        return value


class GpsIngestResponse(BaseModel):
    ok: bool
    point_id: int
    device_id: int
    vehicle_id: int | None
    company_id: int


class GpsDeviceCreateRequest(BaseModel):
    company_id: int
    vehicle_id: int | None = None
    name: str | None = None
    device_uid: str
    device_token: str
    provider: str | None = None
    protocol: str | None = None
    is_active: bool = True

    @field_validator("company_id")
    @classmethod
    def validate_company_id(cls, value: int) -> int:
        if value <= 0:
            raise ValueError("company_id must be positive")
        return value

    @field_validator("vehicle_id")
    @classmethod
    def validate_vehicle_id(cls, value: int | None) -> int | None:
        if value is not None and value <= 0:
            raise ValueError("vehicle_id must be positive")
        return value

    @field_validator("name", "provider", "protocol")
    @classmethod
    def normalize_optional_text(cls, value: str | None) -> str | None:
        if value is None:
            return None
        value = value.strip()
        return value or None

    @field_validator("device_uid", "device_token")
    @classmethod
    def validate_required_text(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("Field cannot be empty")
        return value


class GpsDeviceResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    company_id: int
    vehicle_id: int | None
    name: str | None
    device_uid: str
    provider: str | None
    protocol: str | None
    is_active: bool
    last_seen_at: datetime | None
    created_at: datetime
    updated_at: datetime
