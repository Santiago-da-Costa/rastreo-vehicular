from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.database import get_db
from app.dependencies.auth import require_admin
from app.models.user import User, UserVehicleAccess
from app.models.vehicle import Vehicle
from app.schemas.users import (
    UserCreate,
    UserResponse,
    UserUpdate,
    UserVehicleAssignment,
)
from app.utils.security import hash_password

router = APIRouter(prefix="/users", tags=["users"])


def get_user_or_404(db: Session, user_id: int) -> User:
    user = db.query(User).filter(User.id == user_id).first()
    if user is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="User not found",
        )
    return user


def ensure_username_available(db: Session, username: str, user_id: int | None = None) -> None:
    query = db.query(User).filter(User.username == username)
    if user_id is not None:
        query = query.filter(User.id != user_id)

    if query.first() is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Username already exists",
        )


@router.get("", response_model=list[UserResponse])
def list_users(
    db: Session = Depends(get_db),
    _admin: User = Depends(require_admin),
):
    return db.query(User).order_by(User.id).all()


@router.post("", response_model=UserResponse, status_code=status.HTTP_201_CREATED)
def create_user(
    user_data: UserCreate,
    db: Session = Depends(get_db),
    _admin: User = Depends(require_admin),
):
    ensure_username_available(db, user_data.username)

    user = User(
        username=user_data.username,
        password_hash=hash_password(user_data.password),
        full_name=user_data.full_name,
        email=user_data.email,
        role=user_data.role,
        is_active=user_data.is_active,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


@router.patch("/{user_id}", response_model=UserResponse)
def update_user(
    user_id: int,
    user_data: UserUpdate,
    db: Session = Depends(get_db),
    _admin: User = Depends(require_admin),
):
    user = get_user_or_404(db, user_id)
    update_data = user_data.model_dump(exclude_unset=True)

    if "username" in update_data:
        ensure_username_available(db, update_data["username"], user_id=user.id)
        user.username = update_data["username"]

    if "password" in update_data:
        user.password_hash = hash_password(update_data["password"])

    for field in ("full_name", "email", "role", "is_active"):
        if field in update_data:
            setattr(user, field, update_data[field])

    db.commit()
    db.refresh(user)
    return user


@router.delete("/{user_id}", response_model=UserResponse)
def delete_user(
    user_id: int,
    db: Session = Depends(get_db),
    _admin: User = Depends(require_admin),
):
    user = get_user_or_404(db, user_id)
    user.is_active = False
    db.commit()
    db.refresh(user)
    return user


@router.get("/{user_id}/vehicles", response_model=UserVehicleAssignment)
def get_user_vehicles(
    user_id: int,
    db: Session = Depends(get_db),
    _admin: User = Depends(require_admin),
):
    get_user_or_404(db, user_id)
    vehicle_ids = [
        vehicle_id
        for (vehicle_id,) in (
            db.query(UserVehicleAccess.vehicle_id)
            .filter(UserVehicleAccess.user_id == user_id)
            .order_by(UserVehicleAccess.vehicle_id)
            .all()
        )
    ]
    return UserVehicleAssignment(vehicle_ids=vehicle_ids)


@router.put("/{user_id}/vehicles", response_model=UserVehicleAssignment)
def replace_user_vehicles(
    user_id: int,
    assignment: UserVehicleAssignment,
    db: Session = Depends(get_db),
    _admin: User = Depends(require_admin),
):
    get_user_or_404(db, user_id)
    vehicle_ids = list(dict.fromkeys(assignment.vehicle_ids))

    existing_vehicle_ids = {
        vehicle_id
        for (vehicle_id,) in db.query(Vehicle.id).filter(Vehicle.id.in_(vehicle_ids)).all()
    }
    missing_vehicle_ids = sorted(set(vehicle_ids) - existing_vehicle_ids)
    if missing_vehicle_ids:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Vehicles not found: {missing_vehicle_ids}",
        )

    db.query(UserVehicleAccess).filter(UserVehicleAccess.user_id == user_id).delete()
    for vehicle_id in vehicle_ids:
        db.add(UserVehicleAccess(user_id=user_id, vehicle_id=vehicle_id))

    db.commit()
    return UserVehicleAssignment(vehicle_ids=vehicle_ids)
