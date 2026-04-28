from datetime import datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.database import get_db
from app.dependencies.auth import get_current_active_user
from app.models.trip import Trip
from app.models.trip_point import TripPoint
from app.models.trip_stop import TripStop
from app.models.user import User
from app.models.vehicle import Vehicle
from app.schemas.trip_stops import TripStopResponse as TripStopItemResponse
from app.schemas.trips import (
    TripCategoryUpdate,
    TripManualCreate,
    TripManualResponse,
    TripResponse,
    TripSplitRequest,
    TripSplitResponse,
    TripStart,
    TripStopResponse,
)
from app.services.trip_stops import finalize_open_trip_stop
from app.utils.permissions import (
    filter_trip_query_for_user,
    get_accessible_trip_or_404,
    require_delete_trips,
    require_edit_trips,
    require_vehicle_access_or_404,
)

router = APIRouter(prefix="/trips", tags=["trips"])

MANUAL_TRIP_POINT_INTERVAL_SECONDS = 5


def _ensure_vehicle_matches_user_company(vehicle: Vehicle, current_user: User) -> None:
    if current_user.company_id is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Current user is not assigned to a company",
        )
    if vehicle.company_id != current_user.company_id:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Vehicle not found",
        )


def close_open_vehicle_trips(
    db: Session,
    vehicle_id: int,
    end_time: datetime,
    exclude_trip_id: int | None = None,
):
    query = db.query(Trip).filter(
        Trip.vehicle_id == vehicle_id,
        Trip.status == "active",
        Trip.end_time.is_(None),
    )
    if exclude_trip_id is not None:
        query = query.filter(Trip.id != exclude_trip_id)

    for open_trip in query.all():
        finalize_open_trip_stop(db, open_trip.id, end_time=end_time)
        open_trip.status = "finished"
        open_trip.end_time = end_time


@router.get("", response_model=list[TripResponse])
def list_trips(
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_active_user),
):
    return filter_trip_query_for_user(db.query(Trip), db, current_user).all()


@router.get("/{trip_id}", response_model=TripResponse)
def get_trip(
    trip_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_active_user),
):
    return get_accessible_trip_or_404(db, current_user, trip_id)


@router.get("/{trip_id}/stops", response_model=list[TripStopItemResponse])
def list_trip_stops(
    trip_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_active_user),
):
    get_accessible_trip_or_404(db, current_user, trip_id)
    return (
        db.query(TripStop)
        .filter(TripStop.trip_id == trip_id)
        .order_by(TripStop.start_time.asc(), TripStop.id.asc())
        .all()
    )


@router.patch("/{trip_id}/category", response_model=TripResponse)
def update_trip_category(
    trip_id: int,
    category_data: TripCategoryUpdate,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_active_user),
):
    require_edit_trips(current_user)
    trip = get_accessible_trip_or_404(db, current_user, trip_id)

    categoria = category_data.categoria.strip()
    if not categoria:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Category cannot be empty",
        )
    if len(categoria) > 50:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Category cannot exceed 50 characters",
        )

    trip.categoria = categoria
    db.commit()
    db.refresh(trip)
    return trip


@router.post("/manual", response_model=TripManualResponse, status_code=status.HTTP_201_CREATED)
def create_manual_trip(
    trip_data: TripManualCreate,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_active_user),
):
    require_edit_trips(current_user)
    vehicle = db.query(Vehicle).filter(Vehicle.id == trip_data.vehicle_id).first()
    if vehicle is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Vehicle not found",
        )
    require_vehicle_access_or_404(db, current_user, trip_data.vehicle_id)
    _ensure_vehicle_matches_user_company(vehicle, current_user)

    categoria = trip_data.categoria.strip()
    if not categoria:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Category cannot be empty",
        )
    if len(categoria) > 50:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Category cannot exceed 50 characters",
        )

    if len(trip_data.points) < 2:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Manual trip must have at least 2 points",
        )

    for point in trip_data.points:
        if not -90 <= point.latitude <= 90:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Latitude must be between -90 and 90",
            )
        if not -180 <= point.longitude <= 180:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Longitude must be between -180 and 180",
            )

    base_timestamp = datetime.now()
    point_timestamps = [
        base_timestamp + timedelta(seconds=index * MANUAL_TRIP_POINT_INTERVAL_SECONDS)
        for index in range(len(trip_data.points))
    ]

    try:
        trip = Trip(
            vehicle_id=trip_data.vehicle_id,
            company_id=vehicle.company_id,
            categoria=categoria,
            start_time=point_timestamps[0],
            end_time=point_timestamps[-1],
            status="finished",
            is_manual=True,
        )
        db.add(trip)
        db.flush()

        points = [
            TripPoint(
                trip_id=trip.id,
                latitude=point.latitude,
                longitude=point.longitude,
                timestamp=point_timestamps[index],
                accuracy=None,
                speed=0,
            )
            for index, point in enumerate(trip_data.points)
        ]
        db.add_all(points)
        db.commit()
        db.refresh(trip)
        for point in points:
            db.refresh(point)
    except Exception:
        db.rollback()
        raise

    return TripManualResponse(trip=trip, points=points)


@router.delete("/{trip_id}/manual", status_code=status.HTTP_200_OK)
def delete_manual_trip(
    trip_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_active_user),
):
    require_delete_trips(current_user)
    trip = get_accessible_trip_or_404(db, current_user, trip_id)

    if not trip.is_manual:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Only manual trips can be deleted from this action",
        )

    try:
        deleted_points = (
            db.query(TripPoint)
            .filter(TripPoint.trip_id == trip_id)
            .delete(synchronize_session=False)
        )
        db.delete(trip)
        db.commit()
    except Exception:
        db.rollback()
        raise

    return {
        "detail": "Manual trip deleted",
        "trip_id": trip_id,
        "deleted_points": deleted_points,
    }


@router.post("/{trip_id}/split", response_model=TripSplitResponse)
def split_trip(
    trip_id: int,
    split_data: TripSplitRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_active_user),
):
    require_edit_trips(current_user)
    trip = get_accessible_trip_or_404(db, current_user, trip_id)

    points = (
        db.query(TripPoint)
        .filter(TripPoint.trip_id == trip_id)
        .order_by(TripPoint.timestamp.asc(), TripPoint.id.asc())
        .all()
    )
    if len(points) < 3:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Trip must have at least 3 points to split",
        )

    split_index = next(
        (index for index, point in enumerate(points) if point.id == split_data.point_id),
        None,
    )
    if split_index is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Split point not found in trip",
        )

    if split_index == 0 or split_index == len(points) - 1:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Split point must be an interior point",
        )

    first_point = points[0]
    split_point = points[split_index]
    last_point = points[-1]
    points_for_new_trip = points[split_index:]

    try:
        new_trip = Trip(
            vehicle_id=trip.vehicle_id,
            company_id=trip.company_id,
            categoria=trip.categoria,
            start_time=split_point.timestamp,
            end_time=last_point.timestamp,
            status=trip.status,
            is_manual=trip.is_manual,
        )
        db.add(new_trip)
        db.flush()

        for point in points_for_new_trip:
            point.trip_id = new_trip.id

        duplicated_split_point = TripPoint(
            trip_id=trip.id,
            latitude=split_point.latitude,
            longitude=split_point.longitude,
            timestamp=split_point.timestamp,
            accuracy=split_point.accuracy,
            speed=split_point.speed,
        )
        db.add(duplicated_split_point)

        trip.start_time = first_point.timestamp
        trip.end_time = split_point.timestamp

        db.commit()
        db.refresh(trip)
        db.refresh(new_trip)
        db.refresh(duplicated_split_point)
    except Exception:
        db.rollback()
        raise

    return TripSplitResponse(
        original_trip=trip,
        new_trip=new_trip,
        split_point_id=split_data.point_id,
        duplicated_point_id=duplicated_split_point.id,
    )


@router.post("/start", response_model=TripResponse, status_code=status.HTTP_201_CREATED)
def start_trip(
    trip_data: TripStart,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_active_user),
):
    require_edit_trips(current_user)
    vehicle = db.query(Vehicle).filter(Vehicle.id == trip_data.vehicle_id).first()
    if vehicle is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Vehicle not found",
        )
    require_vehicle_access_or_404(db, current_user, trip_data.vehicle_id)
    _ensure_vehicle_matches_user_company(vehicle, current_user)

    client_trip_id = trip_data.client_trip_id.strip() if trip_data.client_trip_id else None
    if client_trip_id:
        existing_trip = (
            db.query(Trip)
            .filter(
                Trip.company_id == vehicle.company_id,
                Trip.client_trip_id == client_trip_id,
            )
            .first()
        )
        if existing_trip is not None:
            return existing_trip

    start_time = datetime.now()
    close_open_vehicle_trips(db, trip_data.vehicle_id, start_time)

    try:
        trip = Trip(
            vehicle_id=trip_data.vehicle_id,
            company_id=vehicle.company_id,
            client_trip_id=client_trip_id,
            categoria=trip_data.categoria,
            start_time=start_time,
            status="active",
            is_manual=False,
        )
        db.add(trip)
        db.commit()
        db.refresh(trip)
        return trip
    except IntegrityError:
        db.rollback()
        if client_trip_id:
            existing_trip = (
                db.query(Trip)
                .filter(
                    Trip.company_id == vehicle.company_id,
                    Trip.client_trip_id == client_trip_id,
                )
                .first()
            )
            if existing_trip is not None:
                return existing_trip
        raise


@router.post("/{trip_id}/stop", response_model=TripStopResponse)
def stop_trip(
    trip_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_active_user),
):
    require_edit_trips(current_user)
    trip = get_accessible_trip_or_404(db, current_user, trip_id)

    if trip.status == "finished" or trip.end_time is not None:
        return trip

    stop_time = datetime.now()
    close_open_vehicle_trips(db, trip.vehicle_id, stop_time, exclude_trip_id=trip.id)

    finalize_open_trip_stop(db, trip.id, end_time=stop_time)
    trip.end_time = stop_time
    trip.status = "finished"
    db.commit()
    db.refresh(trip)
    return trip
