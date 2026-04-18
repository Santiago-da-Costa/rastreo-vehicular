from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.database import get_db
from app.models.trip import Trip
from app.models.trip_point import TripPoint
from app.schemas.trips import (
    TripCategoryUpdate,
    TripResponse,
    TripSplitRequest,
    TripSplitResponse,
    TripStart,
    TripStopResponse,
)

router = APIRouter(prefix="/trips", tags=["trips"])


@router.get("", response_model=list[TripResponse])
def list_trips(db: Session = Depends(get_db)):
    return db.query(Trip).all()


@router.get("/{trip_id}", response_model=TripResponse)
def get_trip(trip_id: int, db: Session = Depends(get_db)):
    trip = db.query(Trip).filter(Trip.id == trip_id).first()
    if trip is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Trip not found",
        )

    return trip


@router.patch("/{trip_id}/category", response_model=TripResponse)
def update_trip_category(
    trip_id: int,
    category_data: TripCategoryUpdate,
    db: Session = Depends(get_db),
):
    trip = db.query(Trip).filter(Trip.id == trip_id).first()
    if trip is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Trip not found",
        )

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


@router.post("/{trip_id}/split", response_model=TripSplitResponse)
def split_trip(
    trip_id: int,
    split_data: TripSplitRequest,
    db: Session = Depends(get_db),
):
    trip = db.query(Trip).filter(Trip.id == trip_id).first()
    if trip is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Trip not found",
        )

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
            categoria=trip.categoria,
            start_time=split_point.timestamp,
            end_time=last_point.timestamp,
            status=trip.status,
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
def start_trip(trip_data: TripStart, db: Session = Depends(get_db)):
    trip = Trip(
        vehicle_id=trip_data.vehicle_id,
        categoria=trip_data.categoria,
        start_time=datetime.now(),
        status="active",
    )
    db.add(trip)
    db.commit()
    db.refresh(trip)
    return trip


@router.post("/{trip_id}/stop", response_model=TripStopResponse)
def stop_trip(trip_id: int, db: Session = Depends(get_db)):
    trip = db.query(Trip).filter(Trip.id == trip_id).first()
    if trip is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Trip not found",
        )

    trip.end_time = datetime.now()
    trip.status = "finished"
    db.commit()
    db.refresh(trip)
    return trip
