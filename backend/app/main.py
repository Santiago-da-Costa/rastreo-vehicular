from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.database import Base, engine
from app.models import trip, trip_point, vehicle
from app.routes import trip_points, trips, vehicles

Base.metadata.create_all(bind=engine)

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
