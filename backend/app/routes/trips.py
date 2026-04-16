from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.database import get_db
from app.models.trip import Trip
from app.schemas.trips import TripResponse, TripStart, TripStopResponse

router = APIRouter(prefix="/trips", tags=["trips"])


@router.get("", response_model=list[TripResponse])
def list_trips(db: Session = Depends(get_db)):
    return db.query(Trip).all()


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
