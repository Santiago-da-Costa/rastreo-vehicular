# Rastreo Vehicular

Sistema local de rastreo vehicular con backend en FastAPI, frontend web simple y base de datos PostgreSQL.

## Objetivo

La primera etapa se enfoca en rastreo con celular. La estructura queda lista para crecer luego hacia integraciones con OBD y otros dispositivos GPS.

## Estructura

- `backend/`: API en Python con FastAPI.
- `frontend/`: pantallas HTML, CSS y JavaScript.
- `docs/`: notas e ideas iniciales del proyecto.

## Ejecutar backend

```bash
cd backend
pip install -r requirements.txt
uvicorn app.main:app --reload
```

Para probar desde un celular en la misma red Wi-Fi, levantar el backend escuchando en toda la red local:

```bash
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

El endpoint inicial responde en `GET /` con un mensaje de estado.
