# Guia operativa final: Supabase + Render

Objetivo: conectar el backend FastAPI de `rastreo_vehicular` a PostgreSQL en Supabase desde Render, sin tocar frontend, sin cambiar contratos de API y sin agregar Alembic.

Esta guia asume que el servicio Render sirve este backend desde:

```txt
backend/rastreo_vehicular/backend
```

## A. Paso a paso en Supabase

### 1. Crear el proyecto

1. Entrar a Supabase.
2. Ir a `New project`.
3. Elegir la organizacion.
4. Completar:
   - `Project name`: `rastreo-vehicular-prod` o similar.
   - `Database Password`: generar una password fuerte y guardarla fuera del repo.
   - `Region`: elegir la region mas cercana al servicio de Render.
5. Crear el proyecto y esperar a que quede activo.

### 2. Obtener la URL correcta

1. Entrar al proyecto creado.
2. Click en `Connect`, arriba en el dashboard del proyecto.
3. Buscar las connection strings de Postgres.
4. Para este caso simple en Render, usar `Session pooler`.

URL esperada:

```env
postgres://postgres.PROJECT_REF:[YOUR-PASSWORD]@aws-0-REGION.pooler.supabase.com:5432/postgres
```

Por que esta opcion:

- Render ejecuta una app backend persistente.
- `Session pooler` soporta IPv4 e IPv6.
- Evita problemas frecuentes de la conexion directa si el host no tiene IPv6.
- Evita la complejidad del `Transaction pooler`, que puede exigir ajustes por prepared statements.

No usar para esta etapa:

- `Transaction pooler`, salvo que sea necesario por un caso puntual.
- URL directa si falla por red/IPv6.
- Data API de Supabase, porque este backend usa SQLAlchemy contra Postgres.

### 3. Reemplazos manuales en la URL

Supabase normalmente entrega casi toda la URL lista. Revisar estas partes:

```txt
postgres://postgres.PROJECT_REF:[YOUR-PASSWORD]@aws-0-REGION.pooler.supabase.com:5432/postgres
```

Reemplazar:

- `[YOUR-PASSWORD]`: por la password real de la base creada en Supabase.
- Si Supabase muestra `PROJECT_REF` y `REGION` ya resueltos, no tocarlos.
- Si la password contiene caracteres especiales, usar la URL que muestra Supabase ya preparada. Si la escribis a mano, codificar caracteres como `@`, `#`, `/`, `?`, `:` o espacios.

Ejemplo de forma final:

```env
DATABASE_URL=postgres://postgres.abcdefghijklmnopqrst:MiPasswordFuerte123@aws-0-us-east-1.pooler.supabase.com:5432/postgres
```

El backend acepta `postgres://` y `postgresql://`; internamente lo normaliza a `postgresql+psycopg://` para SQLAlchemy.

## B. Paso a paso en Render

### 1. Configurar el servicio

En el Web Service de Render:

```txt
Root Directory: backend/rastreo_vehicular/backend
Build Command: pip install -r requirements.txt
Start Command: uvicorn app.main:app --host 0.0.0.0 --port $PORT
```

El `Procfile` del backend ya tiene el comando de arranque, pero dejar el `Start Command` explicito en Render evita dudas.

### 2. Cargar variables de entorno

1. Entrar al dashboard de Render.
2. Abrir el Web Service del backend.
3. Ir a `Environment`.
4. En `Environment Variables`, usar `+ Add Environment Variable` para cargar cada variable.
5. Guardar con `Save, rebuild, and deploy` si esta disponible.

Variables minimas recomendadas para produccion:

```env
ENVIRONMENT=production
DATABASE_URL=postgres://postgres.PROJECT_REF:PASSWORD@aws-0-REGION.pooler.supabase.com:5432/postgres
JWT_SECRET_KEY=GENERAR_UN_SECRETO_LARGO_Y_UNICO
ACCESS_TOKEN_EXPIRE_MINUTES=60
CREATE_INITIAL_ADMIN=true
INITIAL_ADMIN_USERNAME=admin
INITIAL_ADMIN_PASSWORD=GENERAR_PASSWORD_ADMIN_FUERTE
INITIAL_ADMIN_FULL_NAME=Administrador
INITIAL_ADMIN_EMAIL=admin@tu-dominio.com
CORS_ORIGINS=https://tu-servicio.onrender.com
FRONTEND_PUBLIC_URL=https://tu-servicio.onrender.com
API_PUBLIC_URL=https://tu-servicio.onrender.com
```

Valores concretos de ejemplo:

```env
ENVIRONMENT=production
DATABASE_URL=postgres://postgres.abcdefghijklmnopqrst:MiPasswordFuerte123@aws-0-us-east-1.pooler.supabase.com:5432/postgres
JWT_SECRET_KEY=Gz2uP6rbXqN9qR2sM8tK4vY1cH7aB5eD9fL3pQ6wZ0x
ACCESS_TOKEN_EXPIRE_MINUTES=60
CREATE_INITIAL_ADMIN=true
INITIAL_ADMIN_USERNAME=admin
INITIAL_ADMIN_PASSWORD=Cambiar-Esta-Password-2026
INITIAL_ADMIN_FULL_NAME=Administrador
INITIAL_ADMIN_EMAIL=admin@example.com
CORS_ORIGINS=https://rastreo-vehicular.onrender.com
FRONTEND_PUBLIC_URL=https://rastreo-vehicular.onrender.com
API_PUBLIC_URL=https://rastreo-vehicular.onrender.com
```

Notas importantes:

- No cargar `SECRET_KEY`; el backend usa `JWT_SECRET_KEY`.
- No dejar `DATABASE_URL=sqlite:///...` en Render.
- `JWT_ALGORITHM` no hace falta cargarlo si se usa `HS256`, porque ya es el valor por defecto.
- Si frontend y backend se sirven desde el mismo dominio Render, `CORS_ORIGINS` y `FRONTEND_PUBLIC_URL` pueden apuntar al mismo `https://...onrender.com`.
- Si despues el frontend queda en otro dominio, agregar ese dominio exacto a `CORS_ORIGINS`, separado por coma.

### 3. Despues de guardar variables

1. Render debe iniciar un nuevo deploy si elegiste `Save, rebuild, and deploy` o `Save and deploy`.
2. Abrir `Logs`.
3. Confirmar que no aparecen errores de:
   - `DATABASE_URL debe configurarse en produccion.`
   - `JWT_SECRET_KEY debe configurarse en produccion.`
   - `password authentication failed`
   - `connection refused`
4. Abrir la URL publica:

```txt
https://tu-servicio.onrender.com/
```

Debe responder:

```json
{"message":"Backend funcionando correctamente"}
```

### 4. Forzar redeploy si hace falta

Desde el Web Service:

1. Abrir `Manual Deploy`.
2. Usar `Deploy latest commit` para redeploy normal.
3. Usar `Clear build cache & deploy` si cambiaste dependencias, comandos de build o sospechas cache viejo.
4. Usar `Restart service` solo para reiniciar el mismo build con las variables actuales.

## C. Checklist de validacion post-deploy

Usar estos placeholders:

```txt
API=https://tu-servicio.onrender.com
ADMIN_USER=admin
ADMIN_PASS=la_password_real
TOKEN=token_devuelto_por_login
VEHICLE_ID=id_devuelto_al_crear_vehiculo
TRIP_ID=id_devuelto_al_iniciar_trip
```

### 1. La app arranco

Abrir:

```txt
https://tu-servicio.onrender.com/
```

Esperado:

```json
{"message":"Backend funcionando correctamente"}
```

En Render `Logs`, no debe haber traceback de arranque.

### 2. No esta usando SQLite

Confirmar en Render:

- `ENVIRONMENT=production`
- `DATABASE_URL` empieza con `postgres://` o `postgresql://`
- No existe `DATABASE_URL=sqlite:///./rastreo.db`

Confirmar en logs:

- No debe aparecer `DATABASE_URL debe configurarse en produccion.`
- Si `ENVIRONMENT=production` y falta `DATABASE_URL`, el backend falla rapido. Eso es correcto y evita caer en SQLite.

### 3. Las tablas se crearon en PostgreSQL

En Supabase:

1. Ir a `SQL Editor`.
2. Ejecutar:

```sql
select table_name
from information_schema.tables
where table_schema = 'public'
order by table_name;
```

Esperado: ver tablas como:

```txt
trip_points
trips
user_vehicle_access
users
vehicles
```

Comprobacion extra:

```sql
select count(*) as users_count from public.users;
select count(*) as vehicles_count from public.vehicles;
select count(*) as trips_count from public.trips;
select count(*) as trip_points_count from public.trip_points;
```

### 4. El admin inicial se creo

En Supabase SQL Editor:

```sql
select id, username, role, is_active, email
from public.users
where username = 'admin';
```

Esperado:

- existe una fila;
- `role` debe permitir administracion;
- `is_active` debe ser `true`.

Si ya existe el usuario, el arranque no lo duplica.

Despues de validar el admin, se puede cambiar en Render:

```env
CREATE_INITIAL_ADMIN=false
```

Luego hacer redeploy. No borra el usuario existente.

### 5. Login funciona

Desde una terminal:

```bash
curl -X POST "https://tu-servicio.onrender.com/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"la_password_real"}'
```

Esperado:

```json
{"access_token":"...","token_type":"bearer"}
```

Guardar el token para las pruebas siguientes.

### 6. JWT funciona

```bash
curl "https://tu-servicio.onrender.com/auth/me" \
  -H "Authorization: Bearer TOKEN"
```

Esperado: datos del usuario admin, permisos y lista de vehiculos accesibles.

Si responde `401`, revisar token, password o `JWT_SECRET_KEY`.

### 7. Se puede crear un vehiculo

```bash
curl -X POST "https://tu-servicio.onrender.com/vehicles" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer TOKEN" \
  -d '{"nombre":"Camion prueba","matricula":"TEST-001","descripcion":"Validacion PostgreSQL"}'
```

Esperado:

```json
{"nombre":"Camion prueba","matricula":"TEST-001","descripcion":"Validacion PostgreSQL","id":1}
```

Guardar `id` como `VEHICLE_ID`.

Validar en Supabase:

```sql
select id, nombre, matricula
from public.vehicles
order by id desc
limit 5;
```

### 8. Se puede iniciar un trip

```bash
curl -X POST "https://tu-servicio.onrender.com/trips/start" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer TOKEN" \
  -d '{"vehicle_id":1,"categoria":"prueba"}'
```

Esperado: respuesta con `id`, `vehicle_id`, `status:"active"` e `is_manual:false`.

Guardar `id` como `TRIP_ID`.

### 9. Se pueden guardar puntos GPS

```bash
curl -X POST "https://tu-servicio.onrender.com/trips/TRIP_ID/points" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer TOKEN" \
  -d '{"latitude":-34.9011,"longitude":-56.1645,"timestamp":"2026-04-20T12:00:00Z","accuracy":5,"speed":12}'
```

Esperado: respuesta con `id`, `trip_id`, `latitude`, `longitude`, `timestamp`, `accuracy` y `speed`.

Validar en Supabase:

```sql
select id, trip_id, latitude, longitude, timestamp, speed
from public.trip_points
order by id desc
limit 5;
```

### 10. No hay errores CORS

Desde navegador:

1. Abrir el frontend en su dominio final.
2. Hacer login.
3. Abrir DevTools > Console y Network.
4. Confirmar que no aparecen errores tipo:
   - `CORS policy`
   - `No 'Access-Control-Allow-Origin' header`
   - preflight `OPTIONS` fallando

Si aparecen, revisar `CORS_ORIGINS` en Render. Debe contener el origen exacto del frontend, por ejemplo:

```env
CORS_ORIGINS=https://rastreo-vehicular.onrender.com
```

Sin slash final.

### 11. No hay errores JWT

Confirmar:

- `JWT_SECRET_KEY` existe en Render.
- No se cambio `JWT_SECRET_KEY` entre login y uso del token.
- Si se cambio, hacer logout/login o pedir un token nuevo.
- `JWT_ALGORITHM` no esta configurado con algo distinto de `HS256`.

## D. Diagnostico rapido de fallas comunes

| Sintoma | Causa probable | Solucion rapida |
|---|---|---|
| App no arranca en Render | Falta `DATABASE_URL` con `ENVIRONMENT=production` | Cargar `DATABASE_URL` en el servicio correcto y redeploy |
| App no arranca y log dice `JWT_SECRET_KEY debe configurarse en produccion.` | Falta `JWT_SECRET_KEY` | Cargar `JWT_SECRET_KEY`; no usar `SECRET_KEY` |
| `password authentication failed` | Password de Supabase incorrecta o `[YOUR-PASSWORD]` sin reemplazar | Pegar de nuevo la URL desde `Connect` y reemplazar solo la password |
| `connection refused`, timeout o no conecta | URL directa con problema de IPv6/red | Usar `Session pooler` en vez de direct connection |
| Error por caracteres raros en password | Password no codificada en URL | Usar la URL generada por Supabase o cambiar password por una sin caracteres conflictivos |
| Render arranca pero no persiste datos | Esta usando SQLite o otro servicio/entorno | Revisar `ENVIRONMENT=production`, `DATABASE_URL` y que las variables esten en el Web Service correcto |
| Tablas no aparecen en Supabase | La app no llego a conectar a esa DB o no arranco | Revisar logs, confirmar que `DATABASE_URL` apunta al proyecto Supabase correcto y redeploy |
| Admin no se crea | `CREATE_INITIAL_ADMIN=false` o faltan credenciales | Poner `CREATE_INITIAL_ADMIN=true`, `INITIAL_ADMIN_USERNAME` e `INITIAL_ADMIN_PASSWORD`, luego redeploy |
| Login falla con `401` | Usuario/password incorrectos o admin no existe | Verificar usuario en `public.users`; revisar password cargada en Render |
| Login funciona pero `/auth/me` da `401` | Token vencido, mal copiado o cambiaste `JWT_SECRET_KEY` | Hacer login de nuevo y usar el token nuevo |
| CORS bloqueado en navegador | `CORS_ORIGINS` no contiene el origen exacto del frontend | Agregar dominio exacto con `https://`, sin slash final, y redeploy |
| Crear vehiculo da `403` | Usuario sin permiso `manage_vehicles` | Usar admin inicial o revisar rol del usuario |
| Iniciar trip da `404 Vehicle not found` | `vehicle_id` incorrecto | Usar el `id` devuelto al crear vehiculo |
| Guardar punto GPS da `404 Trip not found` | `TRIP_ID` incorrecto o usuario no accede al trip | Usar el `id` devuelto por `/trips/start` con el mismo usuario |
| Error `relation ... does not exist` | Tablas no creadas por arranque incompleto | Ver logs de arranque y confirmar conexion PostgreSQL |

## E. Confirmacion de alcance

No hace falta tocar codigo antes del deploy real.

El backend ya esta preparado para:

- leer `DATABASE_URL` en produccion;
- fallar rapido si falta `DATABASE_URL`;
- fallar rapido si falta `JWT_SECRET_KEY`;
- aceptar URLs `postgres://` y `postgresql://`;
- usar `psycopg`;
- crear tablas con `Base.metadata.create_all(bind=engine)`;
- crear admin inicial con variables de entorno;
- servir el frontend existente sin cambiar contratos de API.

No agregar Alembic en esta etapa. No redisenar despliegue. No tocar frontend. No reescribir backend.

Fuentes operativas verificadas:

- Supabase: `Connect` del proyecto entrega las connection strings; para apps persistentes con soporte IPv4/IPv6 conviene `Session pooler`. Ver: https://supabase.com/docs/guides/database/connecting-to-postgres
- Render: las variables se cargan en `Environment`; al guardar se puede elegir deploy/rebuild. Ver: https://render.com/docs/configure-environment-variables
- Render: `Manual Deploy` permite `Deploy latest commit`, `Clear build cache & deploy` y `Restart service`. Ver: https://render.com/docs/deploys
