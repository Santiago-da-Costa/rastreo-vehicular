from datetime import datetime

from pydantic import BaseModel


class TripPointCreate(BaseModel):
    client_point_id: str | None = None
    latitude: float
    longitude: float
    timestamp: datetime
    accuracy: float | None = None
    speed: float | None = None


class TripPointResponse(TripPointCreate):
    id: int
    trip_id: int

    class Config:
        from_attributes = True
