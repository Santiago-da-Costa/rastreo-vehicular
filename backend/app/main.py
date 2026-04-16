from fastapi import FastAPI

from app.routes import vehicles

app = FastAPI(title="Sistema de Rastreo Vehicular")

app.include_router(vehicles.router)


@app.get("/")
def root():
    return {"message": "Backend funcionando correctamente"}
