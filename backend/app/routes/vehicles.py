import logging
from datetime import datetime, timedelta, timezone
from math import atan2, cos, radians, sin, sqrt

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.database import get_db
from app.dependencies.auth import get_current_active_user
from app.models.trip import Trip
from app.models.trip_point import TripPoint
from app.models.user import User, UserVehicleAccess
from app.models.vehicle import Vehicle
from app.schemas.vehicles import LiveVehiclePositionResponse, VehicleCreate, VehicleResponse
from app.utils.permissions import (
    filter_trip_query_for_user,
    filter_vehicle_query_for_user,
    require_permission,
)

router = APIRouter(prefix="/vehicles", tags=["vehicles"])

logger = logging.getLogger(__name__)

LIVE_STATE_POINTS_LIMIT = 5
LIVE_STATE_OFFLINE_THRESHOLD = timedelta(minutes=5)
LIVE_STATE_STOPPED_MIN_DURATION = timedelta(seconds=120)
LIVE_STATE_ACCURACY_FALLBACK_METERS = 50.0
LIVE_STATE_MIN_RADIUS_METERS = 10.0
LIVE_STATE_LABELS = {
    "moving": "En movimiento",
    "stopped": "Detenido",
    "offline": "Sin conexión",
    "unknown": "Desconocido",
}


def _normalize_datetime(value: datetime | None) -> datetime | None:
    if value is None:
        return None
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def _distance_meters(
    start_latitude: float,
    start_longitude: float,
    end_latitude: float,
    end_longitude: float,
) -> float:
    earth_radius_meters = 6_371_000.0
    start_lat_rad = radians(start_latitude)
    end_lat_rad = radians(end_latitude)
    delta_lat_rad = radians(end_latitude - start_latitude)
    delta_lon_rad = radians(end_longitude - start_longitude)
    haversine_a = (
        sin(delta_lat_rad / 2) * sin(delta_lat_rad / 2) +
        cos(start_lat_rad) * cos(end_lat_rad) *
        sin(delta_lon_rad / 2) * sin(delta_lon_rad / 2)
    )
    haversine_c = 2 * atan2(sqrt(haversine_a), sqrt(1 - haversine_a))
    return earth_radius_meters * haversine_c


def _infer_live_vehicle_state(
    points: list[TripPoint],
    now: datetime,
) -> str:
    if not points:
        return "unknown"

    ordered_points = sorted(
        points,
        key=lambda point: (_normalize_datetime(point.timestamp), point.id),
    )
    last_point = ordered_points[-1]
    last_timestamp = _normalize_datetime(last_point.timestamp)
    normalized_now = _normalize_datetime(now)

    if last_timestamp is None or normalized_now is None:
        return "unknown"

    if normalized_now - last_timestamp > LIVE_STATE_OFFLINE_THRESHOLD:
        return "offline"

    if len(ordered_points) < 2:
        return "moving"

    last_accuracy = last_point.accuracy
    if last_accuracy is None or last_accuracy <= 0:
        last_accuracy = LIVE_STATE_ACCURACY_FALLBACK_METERS

    stop_radius_meters = max(
        LIVE_STATE_MIN_RADIUS_METERS,
        2 * float(last_accuracy),
    )

    stationary_points = [
        point
        for point in ordered_points
        if _distance_meters(
            point.latitude,
            point.longitude,
            last_point.latitude,
            last_point.longitude,
        ) <= stop_radius_meters
    ]

    if len(stationary_points) < 2:
        return "moving"

    first_stationary_timestamp = _normalize_datetime(stationary_points[0].timestamp)
    if first_stationary_timestamp is None:
        return "unknown"

    if last_timestamp - first_stationary_timestamp >= LIVE_STATE_STOPPED_MIN_DURATION:
        return "stopped"

    return "moving"


def _translate_live_vehicle_state(state: str) -> str:
    return LIVE_STATE_LABELS.get(state, LIVE_STATE_LABELS["unknown"])


def get_vehicle_or_404(db: Session, vehicle_id: int) -> Vehicle:
    vehicle = db.query(Vehicle).filter(Vehicle.id == vehicle_id).first()
    if vehicle is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Vehicle not found",
        )
    return vehicle


@router.get("", response_model=list[VehicleResponse])
def list_vehicles(
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_active_user),
):
    return filter_vehicle_query_for_user(db.query(Vehicle), db, current_user).all()


@router.get("/live", response_model=list[LiveVehiclePositionResponse])
def list_live_vehicle_positions(
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_active_user),
):
    latest_trip_per_vehicle = (
        db.query(
            Trip.id.label("trip_id"),
            func.row_number()
            .over(
                partition_by=Trip.vehicle_id,
                order_by=(Trip.start_time.desc(), Trip.id.desc()),
            )
            .label("trip_rank"),
        )
        .subquery()
    )

    current_trips_query = (
        db.query(Trip, Vehicle)
        .join(Vehicle, Vehicle.id == Trip.vehicle_id)
        .join(
            latest_trip_per_vehicle,
            latest_trip_per_vehicle.c.trip_id == Trip.id,
        )
        .filter(latest_trip_per_vehicle.c.trip_rank == 1)
        .filter(Trip.status == "active", Trip.end_time.is_(None))
    )
    current_trips = (
        filter_trip_query_for_user(current_trips_query, db, current_user)
        .all()
    )
    live_positions = []
    now = datetime.now(timezone.utc)

    for trip, vehicle in current_trips:
        recent_points_desc = (
            db.query(TripPoint)
            .filter(TripPoint.trip_id == trip.id)
            .order_by(TripPoint.timestamp.desc(), TripPoint.id.desc())
            .limit(LIVE_STATE_POINTS_LIMIT)
            .all()
        )
        if not recent_points_desc:
            continue

        last_point = recent_points_desc[0]
        # The inferred state is now exposed as a compatible extra field in
        # /vehicles/live while keeping the existing payload unchanged.
        inferred_state = _infer_live_vehicle_state(recent_points_desc, now)
        estado_en_vivo = _translate_live_vehicle_state(inferred_state)
        logger.debug(
            "Live vehicle state inferred for vehicle=%s trip=%s state=%s estado_en_vivo=%s",
            vehicle.id,
            trip.id,
            inferred_state,
            estado_en_vivo,
        )

        live_positions.append({
            "vehicle_id": vehicle.id,
            "vehicle_name": vehicle.nombre,
            "trip_id": trip.id,
            "categoria": trip.categoria,
            "lat": last_point.latitude,
            "lon": last_point.longitude,
            "speed": last_point.speed,
            "timestamp": last_point.timestamp,
            "is_active": True,
            "estado_en_vivo": estado_en_vivo,
        })

    return sorted(live_positions, key=lambda item: item["vehicle_name"])


@router.post("", response_model=VehicleResponse, status_code=status.HTTP_201_CREATED)
def create_vehicle(
    vehicle_data: VehicleCreate,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_active_user),
):
    require_permission(current_user, "manage_vehicles")
    vehicle = Vehicle(**vehicle_data.model_dump())
    db.add(vehicle)
    db.commit()
    db.refresh(vehicle)
    return vehicle


@router.delete("/{vehicle_id}", response_model=VehicleResponse)
def delete_vehicle(
    vehicle_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_active_user),
):
    require_permission(current_user, "manage_vehicles")
    vehicle = get_vehicle_or_404(db, vehicle_id)
    trip_count = db.query(Trip).filter(Trip.vehicle_id == vehicle_id).count()
    if trip_count:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="No se puede eliminar el vehiculo porque tiene recorridos asociados",
        )

    deleted_vehicle = VehicleResponse.model_validate(vehicle)
    db.query(UserVehicleAccess).filter(UserVehicleAccess.vehicle_id == vehicle_id).delete()
    db.delete(vehicle)
    db.commit()
    return deleted_vehicle
