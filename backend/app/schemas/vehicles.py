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
