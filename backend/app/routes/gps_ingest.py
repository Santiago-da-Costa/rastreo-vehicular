import json
from datetime import datetime
from datetime import timezone

from fastapi import APIRouter, Depends, Header, HTTPException, status
from sqlalchemy.orm import Session

from app.database import get_db
from app.dependencies.auth import require_admin
from app.models.company import Company
from app.models.gps_device import GpsDevice
from app.models.gps_point_raw import GpsPointRaw
from app.models.user import User
from app.models.vehicle import Vehicle
from app.schemas.gps_ingest import (
    GpsDeviceCreateRequest,
    GpsDeviceResponse,
    GpsIngestRequest,
    GpsIngestResponse,
)
from app.utils.security import hash_password, verify_password

router = APIRouter(prefix="/ingest", tags=["gps_ingest"])


def _normalize_datetime_for_storage(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value
    return value.astimezone(timezone.utc).replace(tzinfo=None)


def _company_filter(model, company_id: int | None):
    if company_id is None:
        return model.company_id.is_(None)
    return model.company_id == company_id


def _extract_device_token(
    authorization: str | None,
    x_device_token: str | None,
) -> str:
    if authorization:
        scheme, _, token = authorization.partition(" ")
        if scheme.lower() != "bearer" or not token.strip():
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid Authorization header",
                headers={"WWW-Authenticate": "Bearer"},
            )
        return token.strip()

    if x_device_token and x_device_token.strip():
        return x_device_token.strip()

    raise HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Device token is required",
        headers={"WWW-Authenticate": "Bearer"},
    )


def _get_company_or_400(db: Session, company_id: int) -> Company:
    company = db.query(Company).filter(Company.id == company_id).first()
    if company is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Device company not found",
        )
    return company


def _get_vehicle_or_400(db: Session, vehicle_id: int) -> Vehicle:
    vehicle = db.query(Vehicle).filter(Vehicle.id == vehicle_id).first()
    if vehicle is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Vehicle not found",
        )
    return vehicle


@router.post("/gps", response_model=GpsIngestResponse)
def ingest_gps_point(
    payload: GpsIngestRequest,
    authorization: str | None = Header(default=None),
    x_device_token: str | None = Header(default=None),
    db: Session = Depends(get_db),
):
    device_token = _extract_device_token(authorization, x_device_token)

    device = db.query(GpsDevice).filter(GpsDevice.device_uid == payload.device_uid).first()
    if device is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found",
        )

    if not device.is_active:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Device is inactive",
        )

    if not verify_password(device_token, device.device_token_hash):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid device token",
            headers={"WWW-Authenticate": "Bearer"},
        )

    _get_company_or_400(db, device.company_id)

    vehicle_id = device.vehicle_id
    if vehicle_id is not None:
        vehicle = _get_vehicle_or_400(db, vehicle_id)
        if vehicle.company_id != device.company_id:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Device vehicle must belong to the same company",
            )

    raw_payload = payload.raw if payload.raw is not None else payload.model_dump(mode="json")
    point = GpsPointRaw(
        device_id=device.id,
        company_id=device.company_id,
        vehicle_id=vehicle_id,
        timestamp=_normalize_datetime_for_storage(payload.timestamp),
        latitude=payload.lat,
        longitude=payload.lon,
        speed_kmh=payload.speed_kmh,
        heading=payload.heading,
        accuracy_m=payload.accuracy_m,
        altitude_m=payload.altitude_m,
        ignition=payload.ignition,
        battery=payload.battery,
        raw_json=json.dumps(raw_payload, separators=(",", ":"), ensure_ascii=True),
    )
    db.add(point)
    device.last_seen_at = _normalize_datetime_for_storage(payload.timestamp)
    db.commit()
    db.refresh(point)

    return GpsIngestResponse(
        ok=True,
        point_id=point.id,
        device_id=device.id,
        vehicle_id=point.vehicle_id,
        company_id=point.company_id,
    )


@router.post("/devices", response_model=GpsDeviceResponse, status_code=status.HTTP_201_CREATED)
def create_gps_device(
    payload: GpsDeviceCreateRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(require_admin),
):
    if current_user.company_id is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Current user is not assigned to a company",
        )

    if payload.company_id != current_user.company_id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Cannot create devices outside your company",
        )

    _get_company_or_400(db, payload.company_id)

    if db.query(GpsDevice.id).filter(GpsDevice.device_uid == payload.device_uid).first() is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Device UID already exists",
        )

    vehicle_id = payload.vehicle_id
    if vehicle_id is not None:
        vehicle = _get_vehicle_or_400(db, vehicle_id)
        if vehicle.company_id != payload.company_id:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Vehicle must belong to the same company",
            )

    device = GpsDevice(
        company_id=payload.company_id,
        vehicle_id=vehicle_id,
        name=payload.name,
        device_uid=payload.device_uid,
        device_token_hash=hash_password(payload.device_token),
        provider=payload.provider,
        protocol=payload.protocol,
        is_active=payload.is_active,
    )
    db.add(device)
    db.commit()
    db.refresh(device)
    return device


@router.get("/devices", response_model=list[GpsDeviceResponse])
def list_gps_devices(
    db: Session = Depends(get_db),
    current_user: User = Depends(require_admin),
):
    return (
        db.query(GpsDevice)
        .filter(_company_filter(GpsDevice, current_user.company_id))
        .order_by(GpsDevice.id)
        .all()
    )
