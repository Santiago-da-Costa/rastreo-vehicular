from fastapi import FastAPI

from app.database import Base, engine
from app.models import trip, vehicle
from app.routes import trips, vehicles

Base.metadata.create_all(bind=engine)

app = FastAPI(title="Sistema de Rastreo Vehicular")

app.include_router(trips.router)
app.include_router(vehicles.router)


@app.get("/")
def root():
    return {"message": "Backend funcionando correctamente"}
