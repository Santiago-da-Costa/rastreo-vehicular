from datetime import datetime

from pydantic import BaseModel


class TripStart(BaseModel):
    vehicle_id: int
    categoria: str


class TripCategoryUpdate(BaseModel):
    categoria: str


class TripSplitRequest(BaseModel):
    point_id: int


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


class TripSplitResponse(BaseModel):
    original_trip: TripResponse
    new_trip: TripResponse
    split_point_id: int
    duplicated_point_id: int
