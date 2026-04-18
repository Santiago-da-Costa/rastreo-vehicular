from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.database import get_db
from app.models.trip import Trip
from app.models.trip_point import TripPoint
from app.schemas.trip_points import TripPointCreate, TripPointResponse

router = APIRouter(prefix="/trips/{trip_id}/points", tags=["trip_points"])


@router.post("", response_model=TripPointResponse, status_code=status.HTTP_201_CREATED)
def create_trip_point(
    trip_id: int,
    point_data: TripPointCreate,
    db: Session = Depends(get_db),
):
    trip = db.query(Trip).filter(Trip.id == trip_id).first()
    if trip is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Trip not found",
        )

    point = TripPoint(trip_id=trip_id, **point_data.model_dump())
    db.add(point)
    db.commit()
    db.refresh(point)
    return point


@router.get("", response_model=list[TripPointResponse])
def list_trip_points(trip_id: int, db: Session = Depends(get_db)):
    return (
        db.query(TripPoint)
        .filter(TripPoint.trip_id == trip_id)
        .order_by(TripPoint.timestamp.asc(), TripPoint.id.asc())
        .all()
    )
