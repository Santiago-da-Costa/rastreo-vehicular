from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.database import get_db
from app.dependencies.auth import get_current_active_user
from app.models.user import User
from app.schemas.auth import LoginRequest, TokenResponse, UserMeResponse
from app.utils.permissions import get_accessible_vehicle_ids, get_permissions_for_role
from app.utils.security import create_access_token, verify_password

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/login", response_model=TokenResponse)
def login(login_data: LoginRequest, db: Session = Depends(get_db)):
    user = db.query(User).filter(User.username == login_data.username).first()
    if user is None or not verify_password(login_data.password, user.password_hash):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid username or password",
            headers={"WWW-Authenticate": "Bearer"},
        )

    if not user.is_active:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Inactive user",
        )

    access_token = create_access_token(subject=user.username)
    return TokenResponse(access_token=access_token)


@router.get("/me", response_model=UserMeResponse)
def read_current_user(
    current_user: User = Depends(get_current_active_user),
    db: Session = Depends(get_db),
):
    return UserMeResponse(
        id=current_user.id,
        username=current_user.username,
        full_name=current_user.full_name,
        email=current_user.email,
        role=current_user.role,
        is_active=current_user.is_active,
        created_at=current_user.created_at,
        updated_at=current_user.updated_at,
        permissions=get_permissions_for_role(current_user.role),
        vehicle_ids=get_accessible_vehicle_ids(db, current_user),
    )
