from pydantic import BaseModel


class VehicleBase(BaseModel):
    plate: str
    brand: str
    model: str
    year: int | None = None


class VehicleCreate(VehicleBase):
    pass


class VehicleUpdate(BaseModel):
    plate: str | None = None
    brand: str | None = None
    model: str | None = None
    year: int | None = None


class Vehicle(VehicleBase):
    id: int
