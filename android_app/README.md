# Android App V1

Base minima para captura de ubicacion en Android reutilizando el backend actual de `rastreo_vehicular`.

## Alcance actual

- Login con `POST /auth/login`
- Sesion con `GET /auth/me`
- Lista de vehiculos con `GET /vehicles`
- Inicio de recorrido con `POST /trips/start`
- Envio periodico de puntos con `POST /trips/{trip_id}/points`
- Detencion de recorrido con `POST /trips/{trip_id}/stop`

## Notas V1

- El tracking corre en foreground mientras la app esta abierta.
- No incluye aun servicio robusto en segundo plano.
- La URL base se puede editar desde la propia app y queda guardada localmente.
- Para emulador Android, `http://10.0.2.2:8000/` apunta al host local.
- Para celular real en la misma red, usar la IP LAN del backend, por ejemplo `http://192.168.1.50:8000/`.

## Abrir en Android Studio

Abrir la carpeta `android_app` como proyecto Gradle.

## Sincronizacion recomendada

- Usar Android Studio con JDK 17.
- Si Android Studio pregunta por Gradle, sincronizar desde el IDE usando la configuracion del proyecto.
- El proyecto no trae wrapper `gradlew` en este estado, asi que la primera prueba conviene hacerla directamente desde Android Studio.

## Orden sugerido de prueba

1. Probar primero contra Render para validar compilacion, login y contratos HTTP sin depender de red local.
2. Si eso funciona, probar contra backend local.
3. Recien despues validar tracking en celular real con GPS.

## Base URL segun entorno

- Render: `https://rastreo-vehicular.onrender.com/`
- Emulador Android: `http://10.0.2.2:8000/`
- Celular real en la misma red: `http://IP_LAN_DEL_BACKEND:8000/`

## Prueba manual minima

1. Abrir `android_app` en Android Studio y dejar terminar el `Sync Project with Gradle Files`.
2. Ejecutar la app en un emulador o en un celular con permisos de ubicacion habilitados.
3. En la pantalla inicial, dejar la URL de Render para la primera pasada y tocar `Guardar`.
4. Iniciar sesion con un usuario valido del backend actual.
5. Verificar que cargue `Sesion` y `Vehiculos`.
6. Seleccionar un vehiculo.
7. Tocar `Iniciar`, aceptar permiso de ubicacion y confirmar que se cree un `Trip actual`.
8. Esperar al menos un intervalo de envio y revisar que cambien `Ultima ubicacion` y `Ultimo intento`.
9. Tocar `Detener` y confirmar que el trip se cierre sin error.

## Notas para backend local

- En emulador Android, `10.0.2.2` apunta a la maquina host.
- En celular real, `10.0.2.2` no sirve; hay que usar la IP LAN del equipo donde corre FastAPI.
- Para backend local por HTTP ya esta habilitado `usesCleartextTraffic` en el manifest.
