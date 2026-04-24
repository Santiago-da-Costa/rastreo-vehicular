from datetime import datetime

from pydantic import BaseModel


class TripStopResponse(BaseModel):
    id: int
    trip_id: int
    latitude: float
    longitude: float
    start_time: datetime
    end_time: datetime | None = None
    duration_seconds: int
    status: str

    class Config:
        from_attributes = True
