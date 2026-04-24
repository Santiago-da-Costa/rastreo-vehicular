from datetime import datetime, timezone
from math import atan2, cos, radians, sin, sqrt

from sqlalchemy.orm import Session

from app.models.trip import Trip
from app.models.trip_point import TripPoint
from app.models.trip_stop import TripStop

TRIP_STOP_MIN_DURATION_SECONDS = 120
TRIP_STOP_ACCURACY_FALLBACK_METERS = 50.0
TRIP_STOP_MIN_RADIUS_METERS = 10.0


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


def _get_stop_radius_meters(point: TripPoint) -> float:
    accuracy = point.accuracy
    if accuracy is None or accuracy <= 0:
        accuracy = TRIP_STOP_ACCURACY_FALLBACK_METERS

    return max(
        TRIP_STOP_MIN_RADIUS_METERS,
        2 * float(accuracy),
    )


def _get_open_trip_stop(db: Session, trip_id: int) -> TripStop | None:
    return (
        db.query(TripStop)
        .filter(TripStop.trip_id == trip_id, TripStop.status == "open")
        .order_by(TripStop.start_time.desc(), TripStop.id.desc())
        .first()
    )


def _get_previous_trip_point(db: Session, trip_id: int, new_point_id: int) -> TripPoint | None:
    return (
        db.query(TripPoint)
        .filter(TripPoint.trip_id == trip_id, TripPoint.id != new_point_id)
        .order_by(TripPoint.timestamp.desc(), TripPoint.id.desc())
        .first()
    )


def _close_trip_stop(
    trip_stop: TripStop,
    end_time: datetime | None = None,
) -> None:
    start_time = _normalize_datetime(trip_stop.start_time)
    if end_time is not None and trip_stop.end_time is not None:
        normalized_end_time = _normalize_datetime(end_time)
        current_end_time = _normalize_datetime(trip_stop.end_time)
        if normalized_end_time is not None and current_end_time is not None and start_time is not None:
            if normalized_end_time >= current_end_time:
                trip_stop.end_time = end_time
                trip_stop.duration_seconds = max(
                    0,
                    int((normalized_end_time - start_time).total_seconds()),
                )

    trip_stop.status = "closed"


def update_trip_stops_for_new_point(
    db: Session,
    trip: Trip,
    new_point: TripPoint,
) -> None:
    previous_point = _get_previous_trip_point(db, trip.id, new_point.id)
    if previous_point is None:
        return

    open_trip_stop = _get_open_trip_stop(db, trip.id)
    previous_timestamp = _normalize_datetime(previous_point.timestamp)
    new_timestamp = _normalize_datetime(new_point.timestamp)

    if previous_timestamp is None or new_timestamp is None or new_timestamp <= previous_timestamp:
        if open_trip_stop is not None:
            _close_trip_stop(open_trip_stop)
        return

    elapsed_seconds = int((new_timestamp - previous_timestamp).total_seconds())
    stop_radius_meters = _get_stop_radius_meters(previous_point)
    distance_meters = _distance_meters(
        previous_point.latitude,
        previous_point.longitude,
        new_point.latitude,
        new_point.longitude,
    )

    has_stop_evidence = (
        elapsed_seconds >= TRIP_STOP_MIN_DURATION_SECONDS and
        distance_meters <= stop_radius_meters
    )

    if has_stop_evidence:
        if open_trip_stop is None:
            db.add(
                TripStop(
                    trip_id=trip.id,
                    latitude=previous_point.latitude,
                    longitude=previous_point.longitude,
                    start_time=previous_point.timestamp,
                    end_time=new_point.timestamp,
                    duration_seconds=elapsed_seconds,
                    status="open",
                )
            )
            return

        open_trip_stop.end_time = new_point.timestamp
        open_stop_start_time = _normalize_datetime(open_trip_stop.start_time)
        open_trip_stop.duration_seconds = max(
            0,
            int((new_timestamp - open_stop_start_time).total_seconds()) if open_stop_start_time is not None else 0,
        )
        return

    if open_trip_stop is not None:
        _close_trip_stop(open_trip_stop)


def finalize_open_trip_stop(
    db: Session,
    trip_id: int,
    end_time: datetime | None = None,
    stop_time: datetime | None = None,
) -> None:
    open_trip_stop = _get_open_trip_stop(db, trip_id)
    if open_trip_stop is None:
        return

    effective_end_time = end_time if end_time is not None else stop_time
    _close_trip_stop(open_trip_stop, end_time=effective_end_time)
