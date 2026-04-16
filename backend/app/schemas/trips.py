from datetime import datetime

from pydantic import BaseModel


class TripStart(BaseModel):
    vehicle_id: int
    categoria: str


class TripResponse(BaseModel):
    id: int
    vehicle_id: int
    categoria: str
    start_time: datetime
    end_time: datetime | None = None
    status: str

    class Config:
        from_attributes = True


class TripStopResponse(TripResponse):
    pass
