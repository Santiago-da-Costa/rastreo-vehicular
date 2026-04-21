# Rastreo Vehicular

Sistema de rastreo vehicular con backend en FastAPI, frontend web simple y base de datos SQLite local. El backend queda preparado para usar PostgreSQL en produccion sin reescribir modelos.

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

## Configuracion por entorno

El backend carga variables desde `backend/.env` cuando existe. Ese archivo queda para uso local y no debe subirse al repositorio.

Archivos de referencia:

- `backend/.env.example`: configuracion local de ejemplo.
- `backend/.env.production.example`: variables esperadas para Render, Railway o un servicio similar.
- `docs/despliegue_supabase_render.md`: guia breve para conectar Supabase/PostgreSQL desde Render.

Variables principales:

- `ENVIRONMENT`: usar `local` en desarrollo y `production` en despliegue.
- `DATABASE_URL`: por defecto `sqlite:///./rastreo.db`. En produccion debe apuntar a PostgreSQL.
- `JWT_SECRET_KEY`: en local puede estar en `.env`; en produccion es obligatorio configurarlo como secreto. El backend no lee `SECRET_KEY`.
- `ACCESS_TOKEN_EXPIRE_MINUTES`: duracion del token JWT en minutos. Valor simple recomendado: `60`.
- `API_PUBLIC_URL`: URL publica del backend desplegado.
- `FRONTEND_PUBLIC_URL`: URL publica del frontend si se sirve separado.
- `CORS_ORIGINS`: origenes permitidos separados por coma, por ejemplo `https://mi-frontend.com,https://otro-dominio.com`.
- `CREATE_INITIAL_ADMIN`: usar `true` solo si se quiere crear un admin inicial durante el arranque.
- `INITIAL_ADMIN_USERNAME` e `INITIAL_ADMIN_PASSWORD`: obligatorias cuando `CREATE_INITIAL_ADMIN=true`.

## Base de datos

Localmente sigue funcionando SQLite con:

```env
DATABASE_URL=sqlite:///./rastreo.db
```

Para produccion, el codigo acepta URLs PostgreSQL como las que suelen entregar Supabase, Render o Railway:

```env
DATABASE_URL=postgres://user:password@host:5432/database
```

El codigo normaliza `postgres://` y `postgresql://` para usar el driver `psycopg`. No hay migracion obligatoria en esta fase.

Para Supabase en Render, ver `docs/despliegue_supabase_render.md`.

## CORS y URL del frontend

Si el frontend se sirve desde el mismo backend, no hace falta cambiar nada: el frontend usa `window.location.origin`.

Si el frontend queda en otro dominio, configurar en el backend:

```env
API_PUBLIC_URL=https://mi-backend.onrender.com
FRONTEND_PUBLIC_URL=https://mi-frontend.com
CORS_ORIGINS=https://mi-frontend.com
```

En el frontend, la URL base se centraliza en `frontend/js/config.js`. Para apuntar a un backend externo, puede definirse antes de cargar `js/config.js`:

```html
<script>
    window.RASTREO_API_BASE_URL = "https://mi-backend.onrender.com";
</script>
<script src="js/config.js"></script>
```

## Arranque en produccion

Comando recomendado:

```bash
uvicorn app.main:app --host 0.0.0.0 --port $PORT
```

El archivo `backend/Procfile` ya incluye ese comando para servicios que lo detectan automaticamente:

```Procfile
web: uvicorn app.main:app --host 0.0.0.0 --port $PORT
```

En Render o Railway, usar como directorio/base del servicio la carpeta `backend/rastreo_vehicular/backend` o configurar los comandos para ejecutarse desde ahi.
