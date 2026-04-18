from datetime import datetime

from pydantic import BaseModel


class VehicleBase(BaseModel):
    nombre: str
    matricula: str
    descripcion: str | None = None


class VehicleCreate(VehicleBase):
    pass


class VehicleResponse(VehicleBase):
    id: int

    class Config:
        from_attributes = True


class LiveVehiclePositionResponse(BaseModel):
    vehicle_id: int
    vehicle_name: str
    trip_id: int
    categoria: str | None = None
    lat: float
    lon: float
    speed: float | None = None
    timestamp: datetime
    is_active: bool
