from pathlib import Path
import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from sqlalchemy import inspect, text

from app.config import CORS_ORIGINS
from app.database import Base, SessionLocal, engine
from app.models import company, gps_device, gps_point_raw, plan, refresh_token, trip, trip_point, trip_stop, user, vehicle
from app.routes import auth, companies, gps_ingest, trip_points, trips, users, vehicles
from app.services.bootstrap import ensure_initial_admin

logger = logging.getLogger(__name__)

Base.metadata.create_all(bind=engine)


def ensure_runtime_schema():
    inspector = inspect(engine)
    dialect = engine.dialect.name
    trip_columns = {column["name"] for column in inspector.get_columns("trips")}
    trip_point_columns = {column["name"] for column in inspector.get_columns("trip_points")}
    user_columns = {column["name"] for column in inspector.get_columns("users")}
    vehicle_columns = {column["name"] for column in inspector.get_columns("vehicles")}

    if "companies" in inspector.get_table_names():
        company_columns = {column["name"] for column in inspector.get_columns("companies")}
    else:
        company_columns = set()

    alter_statements = []
    boolean_default = "0" if dialect == "sqlite" else "false"

    if "is_manual" not in trip_columns:
        alter_statements.append(
            f"ALTER TABLE trips ADD COLUMN is_manual BOOLEAN NOT NULL DEFAULT {boolean_default}"
        )
    if "company_id" not in user_columns:
        alter_statements.append(
            "ALTER TABLE users ADD COLUMN company_id INTEGER REFERENCES companies(id)"
        )
    if "company_id" not in vehicle_columns:
        alter_statements.append(
            "ALTER TABLE vehicles ADD COLUMN company_id INTEGER REFERENCES companies(id)"
        )
    if "company_id" not in trip_columns:
        alter_statements.append(
            "ALTER TABLE trips ADD COLUMN company_id INTEGER REFERENCES companies(id)"
        )
    if "client_point_id" not in trip_point_columns:
        alter_statements.append(
            "ALTER TABLE trip_points ADD COLUMN client_point_id VARCHAR"
        )
    if company_columns and "owner_user_id" not in company_columns:
        alter_statements.append(
            "ALTER TABLE companies ADD COLUMN owner_user_id INTEGER REFERENCES users(id)"
        )

    with engine.begin() as connection:
        for statement in alter_statements:
            connection.execute(text(statement))
        connection.execute(
            text(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS uq_trip_points_trip_client_point_id
                ON trip_points (trip_id, client_point_id)
                """
            )
        )


def ensure_default_company_data():
    with engine.begin() as connection:
        connection.execute(
            text(
                """
                INSERT INTO plans (
                    code,
                    name,
                    max_users,
                    max_vehicles,
                    max_devices,
                    history_days,
                    features_json,
                    is_active,
                    created_at,
                    updated_at
                )
                VALUES (
                    :code,
                    :name,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    :is_active,
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP
                )
                ON CONFLICT (code) DO NOTHING
                """
            ),
            {
                "code": "default",
                "name": "Plan Default",
                "is_active": 1 if engine.dialect.name == "sqlite" else True,
            },
        )

        plan_id = connection.execute(
            text("SELECT id FROM plans WHERE code = :code"),
            {"code": "default"},
        ).scalar_one()

        connection.execute(
            text(
                """
                INSERT INTO companies (
                    name,
                    slug,
                    status,
                    plan_id,
                    owner_user_id,
                    created_at,
                    updated_at
                )
                VALUES (
                    :name,
                    :slug,
                    :status,
                    :plan_id,
                    NULL,
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP
                )
                ON CONFLICT (slug) DO NOTHING
                """
            ),
            {
                "name": "Empresa Default",
                "slug": "default",
                "status": "active",
                "plan_id": plan_id,
            },
        )

        company_id = connection.execute(
            text("SELECT id FROM companies WHERE slug = :slug"),
            {"slug": "default"},
        ).scalar_one()

        users_updated = connection.execute(
            text(
                """
                UPDATE users
                SET company_id = :company_id
                WHERE company_id IS NULL
                """
            ),
            {"company_id": company_id},
        ).rowcount or 0

        vehicles_updated = connection.execute(
            text(
                """
                UPDATE vehicles
                SET company_id = :company_id
                WHERE company_id IS NULL
                """
            ),
            {"company_id": company_id},
        ).rowcount or 0

        trips_updated = connection.execute(
            text(
                """
                UPDATE trips
                SET company_id = COALESCE(
                    (
                        SELECT vehicles.company_id
                        FROM vehicles
                        WHERE vehicles.id = trips.vehicle_id
                    ),
                    :company_id
                )
                WHERE trips.company_id IS NULL
                """
            ),
            {"company_id": company_id},
        ).rowcount or 0

    logger.info(
        "Multiempresa foundation ready: default_plan_code=%s default_company_slug=%s users_backfilled=%s vehicles_backfilled=%s trips_backfilled=%s",
        "default",
        "default",
        users_updated,
        vehicles_updated,
        trips_updated,
    )


ensure_runtime_schema()
ensure_default_company_data()


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

        if missing_vehicle_ids and engine.dialect.name == "postgresql":
            connection.execute(
                text(
                    """
                    SELECT setval(sequence_name::regclass, max_id, true)
                    FROM (
                        SELECT
                            pg_get_serial_sequence('vehicles', 'id') AS sequence_name,
                            MAX(id)::bigint AS max_id
                        FROM vehicles
                    ) AS vehicle_sequence
                    WHERE sequence_name IS NOT NULL AND max_id IS NOT NULL
                    """
                )
            )


ensure_trip_vehicle_integrity()


def bootstrap_initial_admin():
    db = SessionLocal()
    try:
        ensure_initial_admin(db)
    finally:
        db.close()


bootstrap_initial_admin()

BASE_DIR = Path(__file__).resolve().parent.parent.parent
FRONTEND_DIR = BASE_DIR / "frontend"

app = FastAPI(title="Sistema de Rastreo Vehicular")

app.add_middleware(
    CORSMiddleware,
    allow_origins=CORS_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(trip_points.router)
app.include_router(trips.router)
app.include_router(vehicles.router)
app.include_router(auth.router)
app.include_router(companies.router)
app.include_router(users.router)
app.include_router(gps_ingest.router)


@app.get("/")
def root():
    return {"message": "Backend funcionando correctamente"}


app.mount("/", StaticFiles(directory=str(FRONTEND_DIR), html=True), name="static")
