from datetime import datetime

from pydantic import BaseModel

from app.schemas.trip_points import TripPointResponse


class TripStart(BaseModel):
    vehicle_id: int
    categoria: str


class TripCategoryUpdate(BaseModel):
    categoria: str


class TripSplitRequest(BaseModel):
    point_id: int


class TripManualPointCreate(BaseModel):
    latitude: float
    longitude: float


class TripManualCreate(BaseModel):
    vehicle_id: int
    categoria: str
    points: list[TripManualPointCreate]


class TripResponse(BaseModel):
    id: int
    vehicle_id: int
    categoria: str
    start_time: datetime
    end_time: datetime | None = None
    status: str
    is_manual: bool = False

    class Config:
        from_attributes = True


class TripStopResponse(TripResponse):
    pass


class TripSplitResponse(BaseModel):
    original_trip: TripResponse
    new_trip: TripResponse
    split_point_id: int
    duplicated_point_id: int


class TripManualResponse(BaseModel):
    trip: TripResponse
    points: list[TripPointResponse]
