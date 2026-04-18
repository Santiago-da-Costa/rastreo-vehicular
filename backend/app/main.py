from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from sqlalchemy import inspect, text

from app.database import Base, engine
from app.models import trip, trip_point, vehicle
from app.routes import trip_points, trips, vehicles

Base.metadata.create_all(bind=engine)


def ensure_runtime_schema():
    inspector = inspect(engine)
    trip_columns = {column["name"] for column in inspector.get_columns("trips")}
    if "is_manual" in trip_columns:
        return

    default_value = "0" if engine.dialect.name == "sqlite" else "false"
    with engine.begin() as connection:
        connection.execute(
            text(f"ALTER TABLE trips ADD COLUMN is_manual BOOLEAN NOT NULL DEFAULT {default_value}")
        )


ensure_runtime_schema()


def ensure_trip_vehicle_integrity():
    with engine.begin() as connection:
        missing_vehicle_ids = connection.execute(
            text(
                """
                SELECT DISTINCT t.vehicle_id
                FROM trips t
                LEFT JOIN vehicles v ON v.id = t.vehicle_id
                WHERE v.id IS NULL
                ORDER BY t.vehicle_id
                """
            )
        ).scalars().all()

        for vehicle_id in missing_vehicle_ids:
            connection.execute(
                text(
                    """
                    INSERT INTO vehicles (id, nombre, matricula, descripcion)
                    VALUES (:id, :nombre, :matricula, :descripcion)
                    """
                ),
                {
                    "id": vehicle_id,
                    "nombre": f"Vehiculo {vehicle_id}",
                    "matricula": f"SIN-MAT-{vehicle_id}",
                    "descripcion": "Creado automaticamente para reparar trips existentes.",
                },
            )


ensure_trip_vehicle_integrity()

FRONTEND_DIR = Path(__file__).resolve().parents[2] / "frontend"

app = FastAPI(title="Sistema de Rastreo Vehicular")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # mantener abierto por ahora
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(trip_points.router)
app.include_router(trips.router)
app.include_router(vehicles.router)


@app.get("/")
def root():
    return {"message": "Backend funcionando correctamente"}


app.mount("/", StaticFiles(directory=FRONTEND_DIR), name="frontend")
