from fastapi import HTTPException, status
from sqlalchemy import false

from app.models.trip import Trip
from app.models.user import User, UserVehicleAccess

ALLOWED_ROLES = {"admin", "user"}


def normalize_role(role: str) -> str:
    normalized_role = role.strip().lower()
    if normalized_role not in ALLOWED_ROLES:
        allowed_roles = ", ".join(sorted(ALLOWED_ROLES))
        raise ValueError(f"Invalid role. Allowed roles: {allowed_roles}")
    return normalized_role


def get_permissions_for_role(role: str) -> dict[str, bool]:
    role_permissions = {
        "admin": {
            "manage_users": True,
            "manage_vehicles": True,
            "view_all_vehicles": True,
            "edit_trips": True,
            "delete_trips": True,
        },
        "user": {
            "manage_users": False,
            "manage_vehicles": False,
            "view_all_vehicles": False,
            "edit_trips": False,
            "delete_trips": False,
        },
    }
    return role_permissions.get(role, role_permissions["user"])


def user_has_permission(user: User, permission_name: str) -> bool:
    return get_permissions_for_role(user.role).get(permission_name, False)


def require_permission(user: User, permission_name: str) -> None:
    if not user_has_permission(user, permission_name):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=f"Permission required: {permission_name}",
        )


def require_edit_trips(user: User) -> None:
    require_permission(user, "edit_trips")


def require_delete_trips(user: User) -> None:
    require_permission(user, "delete_trips")


def get_accessible_vehicle_ids(db, user: User) -> list[int]:
    if user_has_permission(user, "view_all_vehicles"):
        from app.models.vehicle import Vehicle

        return [
            vehicle_id
            for (vehicle_id,) in db.query(Vehicle.id).order_by(Vehicle.id).all()
        ]

    return [
        vehicle_id
        for (vehicle_id,) in (
            db.query(UserVehicleAccess.vehicle_id)
            .filter(UserVehicleAccess.user_id == user.id)
            .order_by(UserVehicleAccess.vehicle_id)
            .all()
        )
    ]


def user_can_access_vehicle(db, user: User, vehicle_id: int) -> bool:
    if user_has_permission(user, "view_all_vehicles"):
        return True

    return (
        db.query(UserVehicleAccess.id)
        .filter(
            UserVehicleAccess.user_id == user.id,
            UserVehicleAccess.vehicle_id == vehicle_id,
        )
        .first()
        is not None
    )


def user_can_access_trip(db, user: User, trip_id: int) -> bool:
    trip_vehicle = db.query(Trip.vehicle_id).filter(Trip.id == trip_id).first()
    if trip_vehicle is None:
        return False

    return user_can_access_vehicle(db, user, trip_vehicle.vehicle_id)


def get_accessible_trip_or_404(db, user: User, trip_id: int) -> Trip:
    trip = db.query(Trip).filter(Trip.id == trip_id).first()
    if trip is None or not user_can_access_vehicle(db, user, trip.vehicle_id):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Trip not found",
        )

    return trip


def require_vehicle_access_or_404(db, user: User, vehicle_id: int) -> None:
    if not user_can_access_vehicle(db, user, vehicle_id):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Vehicle not found",
        )


def filter_vehicle_query_for_user(query, db, user: User):
    if user_has_permission(user, "view_all_vehicles"):
        return query

    accessible_vehicle_ids = get_accessible_vehicle_ids(db, user)
    if not accessible_vehicle_ids:
        return query.filter(false())

    from app.models.vehicle import Vehicle

    return query.filter(Vehicle.id.in_(accessible_vehicle_ids))


def filter_trip_query_for_user(query, db, user: User):
    if user_has_permission(user, "view_all_vehicles"):
        return query

    accessible_vehicle_ids = get_accessible_vehicle_ids(db, user)
    if not accessible_vehicle_ids:
        return query.filter(false())

    return query.filter(Trip.vehicle_id.in_(accessible_vehicle_ids))
