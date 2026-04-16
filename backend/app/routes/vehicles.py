from fastapi import APIRouter, status
from pydantic import BaseModel

router = APIRouter(prefix="/vehicles", tags=["vehicles"])


class VehicleCreate(BaseModel):
    nombre: str
    matricula: str
    descripcion: str


vehicles: list[dict] = []
next_vehicle_id = 1


@router.get("")
def list_vehicles():
    return vehicles


@router.post("", status_code=status.HTTP_201_CREATED)
def create_vehicle(vehicle_data: VehicleCreate):
    global next_vehicle_id

    vehicle = {
        "id": next_vehicle_id,
        "nombre": vehicle_data.nombre,
        "matricula": vehicle_data.matricula,
        "descripcion": vehicle_data.descripcion,
    }
    vehicles.append(vehicle)
    next_vehicle_id += 1

    return vehicle
