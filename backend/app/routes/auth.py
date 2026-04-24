from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.database import get_db
from app.dependencies.auth import get_current_active_user
from app.models.refresh_token import RefreshToken
from app.models.user import User
from app.schemas.auth import (
    LoginRequest,
    RefreshTokenRequest,
    TokenResponse,
    UserMeResponse,
)
from app.utils.permissions import get_accessible_vehicle_ids, get_permissions_for_role
from app.utils.security import (
    create_access_token,
    create_refresh_token,
    hash_refresh_token,
    refresh_token_expires_at,
    verify_password,
)

router = APIRouter(prefix="/auth", tags=["auth"])


def _issue_token_pair(db: Session, user: User) -> TokenResponse:
    access_token = create_access_token(subject=user.username)
    refresh_token = create_refresh_token()
    refresh_token_record = RefreshToken(
        user_id=user.id,
        token_hash=hash_refresh_token(refresh_token),
        expires_at=refresh_token_expires_at().replace(tzinfo=None),
    )
    db.add(refresh_token_record)
    db.commit()
    return TokenResponse(
        access_token=access_token,
        refresh_token=refresh_token,
    )


def _invalid_refresh_credentials() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Invalid refresh token",
        headers={"WWW-Authenticate": "Bearer"},
    )


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

    return _issue_token_pair(db, user)


@router.post("/refresh", response_model=TokenResponse)
def refresh_token(refresh_data: RefreshTokenRequest, db: Session = Depends(get_db)):
    credentials_exception = _invalid_refresh_credentials()
    token_hash = hash_refresh_token(refresh_data.refresh_token)
    refresh_token_record = (
        db.query(RefreshToken)
        .filter(RefreshToken.token_hash == token_hash)
        .first()
    )

    if refresh_token_record is None:
        raise credentials_exception

    if refresh_token_record.revoked_at is not None:
        raise credentials_exception

    if refresh_token_record.expires_at <= datetime.utcnow():
        raise credentials_exception

    user = db.query(User).filter(User.id == refresh_token_record.user_id).first()
    if user is None:
        raise credentials_exception

    if not user.is_active:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Inactive user",
        )

    refresh_token_record.revoked_at = datetime.utcnow()
    response = _issue_token_pair(db, user)
    return response


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
