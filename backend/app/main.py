from fastapi import FastAPI

app = FastAPI(
    title="Rastreo Vehicular API",
    description="Backend inicial para un sistema de rastreo vehicular.",
    version="0.1.0",
)


@app.get("/")
def read_root():
    return {"message": "Backend funcionando correctamente"}
