from fastapi import APIRouter, Depends, status
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.database import get_db
from app.models.trip import Trip
from app.models.trip_point import TripPoint
from app.models.vehicle import Vehicle
from app.schemas.vehicles import LiveVehiclePositionResponse, VehicleCreate, VehicleResponse

router = APIRouter(prefix="/vehicles", tags=["vehicles"])


@router.get("", response_model=list[VehicleResponse])
def list_vehicles(db: Session = Depends(get_db)):
    return db.query(Vehicle).all()


@router.get("/live", response_model=list[LiveVehiclePositionResponse])
def list_live_vehicle_positions(db: Session = Depends(get_db)):
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

    current_trips = (
        db.query(Trip, Vehicle)
        .join(Vehicle, Vehicle.id == Trip.vehicle_id)
        .join(
            latest_trip_per_vehicle,
            latest_trip_per_vehicle.c.trip_id == Trip.id,
        )
        .filter(latest_trip_per_vehicle.c.trip_rank == 1)
        .filter(Trip.status == "active", Trip.end_time.is_(None))
        .all()
    )
    live_positions = []

    for trip, vehicle in current_trips:
        last_point = (
            db.query(TripPoint)
            .filter(TripPoint.trip_id == trip.id)
            .order_by(TripPoint.timestamp.desc(), TripPoint.id.desc())
            .first()
        )
        if last_point is None:
            continue

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
        })

    return sorted(live_positions, key=lambda item: item["vehicle_name"])


@router.post("", response_model=VehicleResponse, status_code=status.HTTP_201_CREATED)
def create_vehicle(vehicle_data: VehicleCreate, db: Session = Depends(get_db)):
    vehicle = Vehicle(**vehicle_data.model_dump())
    db.add(vehicle)
    db.commit()
    db.refresh(vehicle)
    return vehicle
