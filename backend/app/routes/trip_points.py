from fastapi import APIRouter, Depends, status
from sqlalchemy.orm import Session

from app.database import get_db
from app.dependencies.auth import get_current_active_user
from app.models.trip_point import TripPoint
from app.models.user import User
from app.schemas.trip_points import TripPointCreate, TripPointResponse
from app.services.trip_stops import update_trip_stops_for_new_point
from app.utils.permissions import (
    get_accessible_trip_or_404,
    require_edit_trips,
)

router = APIRouter(prefix="/trips/{trip_id}/points", tags=["trip_points"])


@router.post("", response_model=TripPointResponse, status_code=status.HTTP_201_CREATED)
def create_trip_point(
    trip_id: int,
    point_data: TripPointCreate,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_active_user),
):
    require_edit_trips(current_user)
    trip = get_accessible_trip_or_404(db, current_user, trip_id)

    point = TripPoint(trip_id=trip_id, **point_data.model_dump())
    db.add(point)
    db.flush()
    update_trip_stops_for_new_point(db, trip, point)
    db.commit()
    db.refresh(point)
    return point


@router.get("", response_model=list[TripPointResponse])
def list_trip_points(
    trip_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_active_user),
):
    get_accessible_trip_or_404(db, current_user, trip_id)

    return (
        db.query(TripPoint)
        .filter(TripPoint.trip_id == trip_id)
        .order_by(TripPoint.timestamp.asc(), TripPoint.id.asc())
        .all()
    )
